# Code Context — `@typesafe-ai/sdk` v0.6.0 (JS/TS SDK study)

Root: `/tmp/ts-study/typesafe-sdk-js`. Single squashed commit (`66880cc Release v0.6.0`), no node_modules (tests not runnable in place without `npm ci`).

## 1. PACKAGE / LAYOUT

```
src/  index.ts client.ts api-promise.ts errors.ts retry.ts runtime.ts env.ts
      logging.ts questions.ts version.ts types.ts resources/models.ts
test/ client api-promise errors logging retry runtime reliability native-transport
      release-regressions types.test-d helpers.ts
      dist/{esm,cjs}.dist.ts dist/types.dist-d.ts dist/helpers.ts
      integration/api.integration.ts
scripts/ check-package.mjs check-version.mjs test-tarball.mjs publish-npm.mjs
.github/workflows/publish.yml  .github/scripts/release_notes.py
docs/ (separate private pkg: typedoc 0.28 + typescript 6)  examples/demo.ts
```

`src/index.ts` (all 22 lines) — only public surface:
- values: `APIPromise`, `TypeSafeClient`, `ENV`, 12 error classes, `LOG_LEVELS`, `choice|noul|score`, `VERSION`
- types: `WithResponse`, `EnvVar`, `export type * from "./types"`, `export type { Models }` (Models is **type-only**, not runtime-visible)

`package.json`: `"type":"module"`, `main ./dist/index.cjs`, `module ./dist/index.mjs`, `types ./dist/index.d.cts`; exports map = `{".":{import:{types:"./dist/index.d.mts",default:"./dist/index.mjs"}, require:{types:"./dist/index.d.cts",default:"./dist/index.cjs"}}, "./package.json":"./package.json"}`; `files:["dist","LICENSE","README.md"]`; `sideEffects:false`; `engines.node>=20`; `packageManager npm@11.19.0`.

Build: `tsdown.config.ts` → `entry:["src/index.ts"], format:["esm","cjs"], platform:"neutral", target:"node20", dts:true, sourcemap:true, clean:true, fixedExtension:true` (fixedExtension is what produces `.mjs/.cjs/.d.mts/.d.cts`).

`tsconfig.json`: ES2022 target/lib, `"types":["node"]`, strict + `noUncheckedIndexedAccess`, `isolatedModules`, `isolatedDeclarations`, `verbatimModuleSyntax`, `noEmit`, include `src|examples|test`, exclude `test/dist`. `tsconfig.dist-test.json` re-includes only `test/dist` so dist smoke tests type-check against **emitted .d.mts**, not src.

`jsr.json`: name/version duplicated (0.6.0), `exports:"./src/index.ts"` → JSR ships raw TS source, not dist. `push:jsr = npm run check && jsr publish`; no CI workflow publishes to JSR (manual only).

Docs: `docs/typedoc.json` → entryPoints `../src/index.ts`, plugin `typedoc-plugin-markdown`, `out:"../build/docs"`, `navigationJson:"../build/docs/navigation.json"`, `readme:"none"`, `treatWarningsAsErrors:true`, excludes private/protected/internal/externals and sources. `docs/tsconfig.json` extends root but uses `typeRoots:./node_modules/@types` and is pinned to TS 6 because TypeDoc lacks TS 7 support (see `docs/README.md`). `.gitignore` ignores `/build/docs/`, `/release/`, `release-notes.md`.

Scripts: `build|typecheck|lint|test|test:watch|test:integration|test:coverage|test:dist|check:version|check:package|demo|docs`, and
`check = lint && typecheck && check:version && test:coverage && build && test:dist && check:package`; `prepublishOnly = npm run check`. **`scripts/test-tarball.mjs` is not in package.json at all** — only invoked from publish.yml.

## 2. DESIGN

**Client** `src/client.ts:234-470`. `#apiKey` private field (never a property → untestable-leak-proof). Public readonly: `baseURL, defaultModel, logLevel, logger, retry, timeout, defaultHeaders, fetch, models`. Resolution order = explicit config → env (`TYPESAFE_API_KEY|BASE_URL|DEFAULT_MODEL|LOG_LEVEL`, blank ignored) → default (`https://api.typesafe.ai`, `jev-latest`, 10000ms, logLevel `warn`).

Constructor guards: browser (`isBrowser()` from `src/runtime.ts:15`) → throws unless `dangerouslyAllowBrowser`; missing apiKey → throw; missing global fetch → throw. Defaults validated by `assertNonNegativeInteger/assertNonNegativeMs/assertFraction/assertStatusSet` (`client.ts:70-109`) via `resolveRetryPolicy` (which **copies** `httpStatuses` into a new Set) and `assertPositiveMs("timeout", …)`.

`Transport` seam (`client.ts:214-218`) — `{ request<T>(method:"GET"|"POST", path, options?): APIPromise<T>; readonly defaultModel }`; client hands `Models` a closure (`client.ts:284-288`) so the resource never sees the key.

Request pipeline: `#request` (`:328`) builds `ResolvedRequest`, tags `#${++this.#requestCount} ${method} ${path}`, wraps in `APIPromise<T>(fetchWithRetries(...), parseBody)`. `fetchWithRetries` (`:350-401`): header merge where **user headers first, SDK headers second** (so auth/JSON content type can't be clobbered), adds `Authorization: Bearer`, `Accept`, `User-Agent`/`X-TypeSafe-SDK: typesafe-sdk/${VERSION}`, `X-TypeSafe-Runtime: RUNTIME`, `Content-Type` only when body present, and clears `X-TypeSafe-Retry-Count` on attempt 0. Retry loop increments attempt; non-ok → `APIError.fromResponse`; retryable → `backOff`. `attempt` (`:407-448`) creates its own AbortController, forwards caller signal, arms a per-attempt timer, then `bufferResponse(response, controller.signal)` (`:181`) drains a **clone** so the original Response keeps `bodyUsed === false` and is readable by the caller; distinguishes caller-abort (`APIUserAbortError`) vs timer (`APITimeoutError`) vs anything else (`APIConnectionError`). `backOff` (`:451`) logs and awaits `sleep(delay, signal)`; abort during wait → `APIUserAbortError`. `parseBody` (`:472`): empty → undefined; JSON if content-type json, else lenient JSON-then-text.

**Errors** `src/errors.ts`: `TypeSafeError` (sets `this.name = new.target.name`) ← `APIError` (fields `status, headers, body, requestId`, `describe()` message with 200-char truncation, `fromResponse` maps 400/401/403/404/422/429/5xx → BadRequest/Authentication/PermissionDenied/NotFound/UnprocessableEntity/RateLimit/InternalServer) ; `RateLimitError.retryAfterMs = parseRetryAfter(this.headers)`; separate branch `APIConnectionError` → `APITimeoutError` (`timeoutMs`), and `APIUserAbortError`. `extractMessage` handles `error:string`, `error.message`, `message`, `detail:string`, `detail.message`, and FastAPI-style `detail:[{loc,msg}]` → `"questions.q.score.criteria: …; questions: …"` (loc `body` stripped).

**Retry** `src/retry.ts`: defaults `{maxRetries:2, backoffInitialMs:500, backoffMaxMs:5000, backoffJitter:0.25, httpStatuses:{408,429,500..599}, respectRetryAfter:true, maxRetryAfterMs:60000, apiConnectionError:true, apiTimeoutError:true}`. `parseRetryAfter` prefers `retry-after-ms`, then seconds, then HTTP-date (clamped ≥0). `retryDelayMs(attempt, headers, policy, random=Math.random)` = server delay if ≤ `maxRetryAfterMs`, else `round(min(init*2^attempt, max) * (1 - random()*jitter))`. `sleep(ms, signal)` rejects with `signal.reason`.

**Logging** `src/logging.ts`: `LOG_LEVELS = [debug,info,warn,error,off]`, `consoleLogger` with `[typesafe-sdk]` prefix, `withLevel(sink, level)` swaps disabled methods for a no-op. `redactHeaders`: `authorization|proxy-authorization|x-api-key` → scheme + `***` + last 4 chars (only if secret length > 8), `cookie|set-cookie` → `***`. Client logs: `debug` request line + redacted headers + raw body; `info` `"<- status in Xms (request req_…)"`, retry lines, aborts/timeouts/connection errors; **never logs bodies at info**. Bodies are logged unredacted at debug (documented in `TypeSafeClientConfig.logLevel`).

**APIPromise** `src/api-promise.ts:21-78`: `class APIPromise<T> extends Promise<T>` with `super(resolve => resolve(undefined as T))` (the inherited promise is deliberately unused), private `#responsePromise`, `#parseResponse`, cached `#parsed`; overrides `then/catch/finally` to delegate to `#parse()`; `asResponse()` returns the raw buffered Response; `withResponse()` → `{data, response, requestId}`; `map(fn)` re-wraps sharing the same response + single parse. `REQUEST_ID_HEADER = "x-typesafe-request-id"`.

**Questions** `src/questions.ts`: `noul(instructions=null, criteria?)`, `score<const T extends ScoreCriteria>(instructions, criteria)` (throws if criteria is not an array), `choice<const T extends ChoiceCriteria>(instructions, criteria)` (throws on array), plus `validateQuestions` (nonempty map; score criteria must be array with ≥2 entries). Wire objects are the literal object (`{type, instructions, criteria}`), no rewriting.

**Types** `src/types.ts`: `JsonValue`, `EntryType = string|obj|array|null`, `NoulQuestion/ChoiceQuestion<T>/ScoreQuestion<T>`, `ScoreCriteria = readonly [EntryType, EntryType, ...EntryType[]]`, `Question` union, `Questions` map. Results: `NoulResponse`, `ChoiceResponse<T>` (`choice: keyof T & string`, `probabilities` mapped over T), `ScoreOf<T>` (`number extends T["length"] ? number : Extract<keyof T, \`${number}\`>`) → `ScoreLegend<T>`, `ScoreResponse<T>`, and `ResultFor<T extends Question>` conditional (falls through to `never`). `SystemOneResult<Q>` = `{model, answers: {[K in keyof Q]: ResultFor<Q[K]>}, usage:{input_tokens,output_tokens}}`. `SystemOneRequest<Q>` = `{state, questions:Q, model?}` with `SystemOneRequestPayload` pinning `model: string`. Also `RetryPolicy`, `RequestOptions`, `Fetch`, `Logger`, `LogLevel`, `ModelCard`, `TypeSafeClientConfig`.

**Models** `src/resources/models.ts`: `list(options)` → `transport.request<ModelsWire>("GET","/v1/models",options).map(unwrapModels)`; `unwrapModels` throws `TypeSafeError("Unexpected response shape from GET /v1/models; expected { models: [...] }.")` on anything but an array. Note the wire response is `{models:[…]}` but the SDK returns the bare array.

`src/version.ts` `VERSION = "0.6.0"` with a comment pointing at `npm run check:version`.

## 3. WIRE PROTOCOL

`POST {baseURL}/v1/systemone`, body = the caller's request object spread plus resolved model:
```json
{"state": <EntryType>, "questions": {"name": {"type":"noul|choice|score","instructions":<EntryType>|omitted,"criteria":…}}, "model":"jev-latest"}
```
Headers: `Authorization: Bearer <key>`, `Accept: application/json`, `User-Agent`/`X-TypeSafe-SDK: typesafe-sdk/0.6.0`, `X-TypeSafe-Runtime: node/24.x.y (darwin; arm64)`, `Content-Type: application/json` (only with a body), `X-TypeSafe-Retry-Count: n` on attempts > 0. `GET /v1/models` sends no Content-Type and no retry header.

Response (from tests, `test/dist/helpers.ts:30-42`, `test/integration/api.integration.ts:62-84`):
```json
{"model":"…","answers":{"ok":{"type":"noul","noul":0.9},
 "tone":{"type":"choice","choice":"warm","confidence":0.8,"probabilities":{"warm":0.8,"cold":0.2}},
 "urgency":{"type":"score","score":1.5,"confidence":0.9,"legend":{"0":"a","1":"b"},"probabilities":{"0":0.1,"1":0.9}}},
 "usage":{"input_tokens":10,"output_tokens":2}}
```
`GET /v1/models` → `{"models":[{"name","description","release_date"}]}`; extra fields (e.g. `tags`) pass through untouched. Errors: `x-typesafe-request-id: req_…`, bodies `{error:{message}}|{error:string}|{message}|{detail:string}|{detail:{message}}|{detail:[{loc,msg}]}`; live-API messages are `400 Unknown model: x` and `422 questions.q.score.criteria.0 …`.

## 4. TESTS

**Harness `test/helpers.ts` (27 lines, quoted in full effect):**
```ts
export const mockFetch = (respond: (req: RecordedRequest) => Response | Promise<Response>) => {
  const requests: RecordedRequest[] = [];
  const fetch: Fetch = async (url, init) => {
    const body = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
    const req = { url, init, body };
    requests.push(req);
    return respond(req);
  };
  return { fetch, requests };
};
export const json = (data: unknown, init: ResponseInit = {}): Response =>
  new Response(JSON.stringify(data), {...init, headers: {"content-type":"application/json", ...init.headers}});
```
Technique: **hand-rolled fetch stub injected via `config.fetch`** (no msw, no global interception except `vi.stubGlobal("fetch", …)` in two places). No `vi.mock` of modules anywhere; `vi.useFakeTimers` + `vi.spyOn(Math,"random").mockReturnValue(0)` for backoff math. Shared fake `Response` objects are reused per-client, hence the comment in `errors.test.ts:16`: "Retries are off here… a retry would find its body consumed."

| File | Covers / technique |
|---|---|
| `test/client.test.ts` (427) | config resolution & precedence, blank env, trailing-slash strip (both sources), all 5 log levels + invalid-level message naming source, `"apiKey" in client === false` and `JSON.stringify(client)` leak check, header identity set, header merge precedence incl. `Authorization` override, models list happy/unwrapped + `it.each` of 6 bad shapes, raw `asResponse()` keeping `tags`, systemOne payload exactness (`criteria: undefined` serializes away), model precedence, abort wrapping + signal propagation, connection error cause, builders (choice/score type guards, rich non-string descriptions), wire-format null/array preservation, forward-compat extra fields, score/empty validation with `expect(requests).toHaveLength(0)` |
| `test/reliability.test.ts` (598) | fake-timer retry matrix: Retry-After honored at exact ms, exponential 500/1000, maxRetries, non-retryable status, connection-error retry, no retry on caller abort, abort **during** backoff, per-call overrides, `X-TypeSafe-Retry-Count` values; policy isolation (`Reflect.set` mutation of client A doesn't affect B), caller-Set copying, in-flight policy snapshot, custom status sets, independent `apiConnectionError`/`apiTimeoutError` toggles, backoff cap, `respectRetryAfter:false` + `maxRetryAfterMs` cap, per-field numeric validation table; timeouts (exact firing, per-attempt timer, timer cleanup via `vi.getTimerCount()`, abort-vs-timeout disambiguation); `RateLimitError.retryAfterMs`; browser guard via `vi.stubGlobal("window"/"navigator")`, `dangerouslyAllowBrowser`, "Illegal invocation" receiver test with a `function`-form `vi.fn`, missing-fetch error |
| `test/api-promise.test.ts` (108) | `instanceof APIPromise && Promise`, await/catch/finally/then chains, `withResponse` data+response+requestId (present/absent), `asResponse` unconsumed body, **single parse across `Promise.all([p, p.then(x=>x), p.withResponse()])`** asserted via `respond` call count, rejects-with-APIError on all three paths (`p`, `.withResponse()`, `.asResponse()`), `map()` sharing response/requestId and one fetch |
| `test/errors.test.ts` (134) | `APIError.fromResponse` status→class table incl. `err.name === cls.name`; message extraction from 5 body shapes incl. FastAPI `loc` joining; raw-body fallback + 200-char truncation; text/html body kept as text; empty body `"429 status code (no body)"`; JSON parsed without content-type |
| `test/logging.test.ts` (217) | console spies (silent at default level, prefix at debug), `withLevel` filtering incl. `off`, exact info line regex, full debug detail object incl. `Authorization: "Bearer ***cdef"`, "never logs the raw API key" via `JSON.stringify(logger.calls)`, concurrent request numbering, 404 logged as info+debug with **no warn/error**, connection-error log carrying the cause object, caller-abort log; `redactHeaders` unit tests (tail suppression for short secrets, no mutation) |
| `test/retry.test.ts` (141) | pure-function tests: default policy shape + full 500-599 status list, `isRetryableStatus` `it.each`, `parseRetryAfter` (seconds, `retry-after-ms` preference, HTTP-date, garbage/negative), `retryDelayMs` exponential/jitter/Retry-After/ceiling via injected `random`, `sleep` reject-with-reason (mid-sleep and pre-aborted) |
| `test/runtime.test.ts` (39) | `describeRuntime()` for node/bun/deno/vercel-edge/cloudflare-workers/browser/unknown via `vi.stubGlobal` |
| `test/native-transport.test.ts` (172) | **real `node:http` server** on `127.0.0.1:0` (`withServer(handle, run)` helper: listen(0), run(baseURL), `closeAllConnections()` + close in finally). Asserts on-wire headers, stalled 200/503 bodies time out on all three consumer paths with a counting wrapper fetch, caller abort after headers never retries, broken body (`res.destroy()` after `"["`) is retried and the success response metadata (`url`, `bodyUsed:true`, per-attempt request id `req_2`) surfaces, delayed chunks are buffered before raw handoff and body stays readable after abort |
| `test/release-regressions.test.ts` (154) | mixed-case protected-header clobber attempts across attempts; no Content-Type/retry-count on GET; `JSON.parse('{"__proto__":…}')` own-property survival + no mutation; stalled body with a signal-ignoring custom fetch + stream `cancel` called twice; stream-error body → `APIConnectionError` with cause; signal aborted just before fetch returns headers; 204 null-body response |
| `test/types.test-d.ts` (242) | vitest `typecheck` (`*.test-d.ts`) with `expectTypeOf`/`@ts-expect-error`: literal criteria keys, tuples, `ScoreResponse<readonly ["bad","ok"]>`, non-literal list degradation to `{[score:number]:…}`, readonly results/models/client, retry-policy typing (Set not array, removed top-level `maxRetries`), rejected criteria shapes, `APIPromise`/`WithResponse` types |
| `test/dist/*.dist.ts` + `types.dist-d.ts` | tarball-free **built-artifact** tests: ESM/CJS import the real `dist/index.mjs|cjs`, assert exact `Object.keys(sdk).sort()` equals `EXPECTED_VALUE_EXPORTS` (hand-maintained list in `dist/helpers.ts:45-66`), `VERSION === pkg.version`, real round trip through the bundle, bundle's own error classes (`instanceof` across bundle boundary, `retryAfterMs`), **no `node:` imports in the bundle** (regex over dist source), exports-map paths asserted against `pkg.exports`, and `types.dist-d.ts` re-checks inference against emitted `index.d.mts` |
| `test/integration/api.integration.ts` (148) | live API, `describeLive = process.env[ENV.apiKey] ? describe : describe.skip`, `timeout: 120_000`, console dumps of raw payloads; asserts model card fields, `requestId` `/^req_/`, noul∈[0,1], choice probabilities key set and `toBeCloseTo(1, 1)`, score `legend` echo, rich descriptions, bad key → `AuthenticationError`, `"400 Unknown model: no-such-model"`, 422 message prefix `questions.q.score.criteria.0`, and pre-flight client-side rejections |

Configs: `vitest.config.ts` — `include:["test/**/*.test.ts"]`, `typecheck.enabled` with `include:["test/**/*.test-d.ts"]` (separate glob, so `types.test-d.ts` runs in the same `vitest run`), coverage v8 over `src/**` with thresholds `{statements:95, branches:90, functions:95, lines:95}` and a comment "Floors, not targets… never lower them to make a build pass." `vitest.dist.config.ts` — `include:["test/dist/**/*.dist.ts"]` + typecheck `*.dist-d.ts` with `tsconfig.dist-test.json`. `vitest.integration.config.ts` — `include:["test/integration/**/*.integration.ts"]`, `testTimeout/hookTimeout: 120_000`.

## 5. TEST GAPS / SEAMS

- **No CI runs the unit suite.** Only `.github/workflows/publish.yml` exists (tag push + manual dispatch). It runs `check-version.mjs`, `npm run build`, `test:dist`, `check:package`, `npm pack` + `scripts/test-tarball.mjs` — **never `npm run test`, `lint`, `typecheck`, `test:integration`, or the `check` script**. The 95%/90% coverage floors are only enforced when a human runs `npm run check` (which is `prepublishOnly`, and `npm publish <tarball>` bypasses lifecycle scripts → `prepublishOnly` never fires in the release path either). JSR publishing has no workflow at all.
- `scripts/check-version.mjs` has a `--committed` mode (git show HEAD:<path>) that **nothing calls** — dead branch, and the git-snapshot validation it implements is therefore untested.
- `scripts/test-tarball.mjs` — not referenced from package.json; expects exactly one `.tgz` in `release/`; it is the only test of a clean-consumer install. Also `--offline` install means it validates only local tarball resolution.
- Untested branches I found by reading src: `readEnv` when `process` is undefined (`env.ts:17`); already-aborted caller signal at call time (`client.ts:415` pre-check; tests only abort inside fetch); `extractMessage` when the parsed body is a bare JSON string (`errors.ts:17`) and when `detail` is an array with no usable entries (`describeValidationErrors` returning `undefined`); `parseBody` invalid JSON under `application/json` content-type (only the text/html branch is tested); `stripTrailingSlashes` on `"/"` → `""` (relative URL) ; `systemOne` called with a pre-typed `SystemOneRequest<Q>` variable (the "extra props forwarded" contract is only tested with inferred inline literals); `ResultFor`'s `never` fallback; `Models` type-only export means no user-facing construction path is asserted.
- Brittle / shallow: `X-TypeSafe-Runtime` asserted with `/^node\/\d+\.\d+\.\d+ \(\w+; \w+\)$/` (`client.test.ts:145`, `runtime.test.ts:8`) — fails under Bun/Deno or unusual platform strings, so it pins the test runner rather than the contract. Header assertions cast `init.headers as Record<string,string>` (only `release-regressions.test.ts` normalizes through `new Headers(...)`). `test/dist/helpers.ts:EXPECTED_VALUE_EXPORTS` is a hand-maintained duplicate of `src/index.ts` — adding an export silently requires editing the list (intentional strictness, but a maintenance trap with no auto-generation). `errors.test.ts` relies on one-shot Response objects, so any retry default change would break it in a confusing way (already commented). The `it.each` bad-shape list in `client.test.ts:176-185` is shallow for `{models:{models:[]}}`; `unwrapModels` only checks `Array.isArray`.
- Awkward seams the design creates: `Transport` (`client.ts:214`) exists only so `Models` can be injected without holding the key — it is exported from `client.ts` but not from `index.ts`, so deep-import (`src/…`, legal on JSR) is the only way for consumers to see it. `APIPromise extends Promise` with a dummy `super(resolve => …)` is a known sharp edge: `Promise.all`/`await` route through the overridden `then`, but `.map()` returns `APIPromise` while `.then()`/`.catch()`/`.finally()` return plain `Promise`, so chaining silently drops `.withResponse()`. `asResponse()`'s contract ("don't also await the parsed result on the same promise", `api-promise.ts:36-40`) is enforced only by JSDoc, and correctness depends on `bufferResponse` cloning — a custom always-cloned or streaming fetch would break it. `systemOne`'s `satisfies SystemOneRequestPayload` + spread means arbitrary extra keys (incl. `__proto__` own props) are forwarded to the server by design.
- Not exercised at all: `dangerouslyAllowBrowser: true` interaction beyond construction, `VERSION` drift (only via `check:version`), `examples/demo.ts` (needs a live key), `scripts/publish-npm.mjs` integrity/idempotency logic (no test).

## 6. CI / BUILD VERIFICATION

- `scripts/check-package.mjs`: spawns `publint` then `attw --pack .` with `env.npm_config_dry_run` deleted (comment explains attw's nested `npm pack` produces no tarball when npm exports `npm_config_dry_run=true`, e.g. under `npm publish --dry-run`); exits with the failing child's status; `shell: true` only on Windows.
- `scripts/check-version.mjs`: reads `package.json`, `package-lock.json` (`.version` **and** `.packages[""].version`), `jsr.json`, and `src/version.ts` via regex `VERSION = "([^"]+)"`; requires exactly one distinct value, prints the mismatch table and exits 1 otherwise; `--committed` swaps `readFileSync` for `git show HEAD:<path>` (unused). Current values are all `0.6.0`.
- `scripts/test-tarball.mjs`: expects exactly one `release/*.tgz`, makes a temp consumer with `{"private":true}`, `npm install --offline --ignore-scripts` the tarball, then runs `node --input-type=module` and `--input-type=commonjs` evals asserting `typeof sdk.TypeSafeClient === "function"`, `typeof sdk.choice === "function"`, and `new sdk.TypeSafeClient({apiKey:"package-smoke-test"})`; temp dir removed in `finally`.
- `scripts/publish-npm.mjs`: hashes the tarball (`sha512-…`), `npm view <name>@<version> dist.integrity` — if it exists and differs → throw, if identical → skip; if `E404` → `npm publish ./release/<file> --access public --tag latest --provenance`. `./` prefix is deliberate (avoids GitHub shorthand interpretation).
- `publish.yml` sequence: pin-SHA actions, Python 3.13 for `.github/scripts/release_notes.py` (requires exactly one changelog heading `vX.Y.Z (YYYY-MM-DD)`, must be the latest non-Unreleased section, else `ValueError`), Node 24 + global npm 11.19.0, tag/version validation with `check-version.mjs`, `npm ci`, `build`+`test:dist`+`check:package`, `npm pack --pack-destination release`, `test-tarball.mjs`, upload artifact, publish, then create/edit the GitHub Release from `release/release-notes.md` and attach `release/*.tgz`. Guardrails: `repository must be public` for provenance, tag must be on `origin/main`, `DRY_RUN` path validated from a manual dispatch.

## Start Here

1. `src/client.ts` — everything (config → retry → abort/timeout → logging → header merge) lives here; read `:234-470` first, then `src/api-promise.ts` for the buffering contract.
2. `test/helpers.ts` + `test/reliability.test.ts` — the harness and the fake-timer retry matrix are the template for any new test; `test/native-transport.test.ts` is the only real-socket coverage.
3. `package.json` `check` script + `.github/workflows/publish.yml` — to see which gates actually run and which are aspirational.
