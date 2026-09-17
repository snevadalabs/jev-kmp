# Implement the client and error tree

Type: task
Status: resolved
Blocked by: 10, 11

## Question

Nothing to decide — assemble the public entry point and the error hierarchy on top of the two layers below it.

**Config resolution.** `explicit → env → default` with the siblings' env names and defaults, via `expect fun platformEnv`. JVM `System.getenv`, Apple `NSProcessInfo`, Android reuses the JVM actual. Blank or whitespace-only env values are ignored, not treated as set — both siblings do this and it is worth a test.

**`TypeSafeClient`.** A regular class, **not a `data class`**. Holds `apiKey` such that it appears in no generated `toString()`, no `toString()` we write, no logging path, and no exception message. Write the test that asserts this before writing the class. Implements `AutoCloseable`: closes the engine it created, never one it was handed, and closing twice is safe. Owns `systemOne(...)` and a `models` accessor.

`systemOne` takes `state` (String or JsonElement), the questions, an optional per-call `model`, an optional per-call timeout, and an optional per-call `RetryPolicy`. It returns `SystemOneResponse` carrying `model`, `answers`, `usage`, `requestId`, `status`, and `headers: Map<String, String>`.

**`Models`.** `client.models.list()` → `List<ModelCard>` from `GET /v1/models`. A response that is not `{models: [...]}` fails with a clear message naming the endpoint, not a cast error. Reached through the transport seam so it never sees the API key directly.

**Errors.** The 12-class tree in `com.sierranevadalabs.jev.sdk.errors`. **[amended by the parent before dispatch: the first dispatch stopped here, correctly — "names verbatim from the siblings" is not decidable as written, because JS's classes are already de-prefixed (`BadRequestError`…) while its *root* is `TypeSafeError`, and brief §11 says `TypeSafeClient` is the only `TypeSafe`-prefixed public name. The tree below is JS's 12 with the two names that cannot survive both rules resolved.]**

| class | extends |
|---|---|
| `JevError` | `Exception` — the root; JS calls this one `TypeSafeError` |
| `APIError` | `JevError` — carries `status`, the parsed body, the request id |
| `BadRequestError`, `AuthenticationError`, `PermissionDeniedError`, `NotFoundError`, `UnprocessableEntityError`, `RateLimitError`, `InternalServerError` | `APIError` |
| `APIConnectionError` | `JevError` |
| `APITimeoutError` | `APIConnectionError` |
| `APIResponseValidationError` | `APIError` |

`JevError` is the root because §11 forbids the `TypeSafe` prefix on anything but `TypeSafeClient`, and `Jev` is the project's own identifier prefix — the same convention as openai-kotlin's `OpenAIError`. If you would rather match JS literally on that one name, take the rename and say so in your `## Answer`; **do not stop on it again**, the count is 12 either way. **Not ported: JS's `APIUserAbortError`** — a Kotlin caller abort is `kotlin.coroutines.cancellation.CancellationException`, which is not ours to wrap, and the brief already treats cancellation as never-retryable and never-wrapped. All ten names ADR 0005 declares canonical for the fixtures are in this tree, so the fixture mapping is a pure name match. `RateLimitError` carries `retryAfterMs`. The status→class mapping lives in exactly one place. Message extraction handles all four body shapes the fixtures encode, including FastAPI's `detail: [{loc, msg}]` flattened into a readable message, with the raw body length-capped and non-JSON bodies passed through as text. The `Authorization` header must never appear in an exception's message or `toString()`.

**Tests.** A call-level round trip through `MockEngine` asserting the exact request body and headers, plus the exact response surface. Error mapping for every status. The API-key-leak assertions. Lifecycle: close-what-you-own, idempotent close, and that an injected engine survives the client.

Deliverable: client, models, errors, and tests.

## Answer

**Built, gated, green.** Branch `issue-12-client-and-error-tree`, shipped unmerged as PR #6.

### What landed

`src/commonMain/kotlin/com/sierranevadalabs/jev/sdk/`

- **`Client.kt`** — `interface TypeSafeClient : AutoCloseable` with `val models` and the one abstract
  `systemOne(JsonElement, vararg Question<*>, model, timeout, retry)`, the `String`-state extension beside it, a
  top-level `fun TypeSafeClient(config)` factory over an internal `createClient(config, env, engineFactory)`, and
  the internal impl. The brief §9/§12 amendments fixed this shape, so the ticket's "regular class" line was read
  as the anti-`data class` rule it is: nothing generated or written prints the key, and the interface is what
  ticket 16 froze. `close()` is graceful and idempotent; the engine is closed only when the client created it.
- **`Config.kt`** — `TypeSafeConfig` (plain class; its `toString()` omits `apiKey` *and* `defaultHeaders`),
  `ResolvedConfig` for `explicit → env → default` with the siblings' names and defaults, blank/whitespace-only
  env ignored, missing key → `JevError`.
- **`Models.kt`** — `interface Models`, `client.models.list()` → `List<ModelCard>`, `ModelCard`, `Usage`.
  `ModelsApi` holds a closure over the client's request path, never the transport, so the key is out of reach.
- **`errors/`** — the 12-class tree with the parent's names (`JevError` root, `APIError`, the seven status
  classes, `APIConnectionError`/`APITimeoutError`, `APIResponseValidationError`); every constructor is `internal`
  (catch-only — nothing asked for constructible errors); the single `errorFor` status mapping; and
  `extractErrorMessage` covering every shape ADR 0005 lists, capped at 200 chars with an ellipsis.
- `Platform.kt` + all four actuals grew `expect fun platformEnv` (JVM/Android `System.getenv`, Apple
  `NSProcessInfo`, Linux `getenv`, opted into `ExperimentalForeignApi`). `Transport.kt` grew an internal
  `engineFactory` so engine ownership is observable. `SystemOneResponse` gained `model`, `usage`, `requestId`,
  `status` and `headers`, all defaulted so ticket 11's tests are untouched.

### Evidence

`./gradlew check` → **BUILD SUCCESSFUL** (51 tasks). The 83 tests are green on all four runnable targets —
`jvmTest`, `testAndroidHostTest`, `macosArm64Test`, `iosSimulatorArm64Test`, 83 / 0 failures each. `linuxX64Test`
compiles and links and is SKIPPED on this macOS host, as CI's Linux lane expects; no simulator contention.
`dokkaGenerate` and `apiCheck` green. `api/jvm/jev-kmp.api` reviewed and committed as a deliberate diff.

26 new tests in three files: `ClientTest` (fixture round trip, per-call model/timeout/retry, the full status
class table, the transport-failure branch, malformed answer, usage absent, local validation, models happy and
bad shape, the API-key leak set, lifecycle), `ErrorMappingTest` (all five error fixtures' `expect.message`
extracted from their own bytes, the ADR 0005 shapes, the truncation cap, the status table), `ConfigTest`
(precedence, blank env, names/defaults, missing key, non-positive timeout). The API-key test was written first:
`compileTestKotlinJvm` failed on unresolved references, then the class made it pass.

Two measured corrections, recorded in the tests rather than argued away:

- **Ktor carries `Content-Type` on the outgoing body, not in the request's headers.** At the `MockEngine`
  boundary `request.headers["Content-Type"]` is `null` while `request.body.contentType` is
  `application/json`, and the engine writes the header on the wire. `assembleHeaders` still force-sets it, so a
  caller still cannot substitute one; the round-trip test pins the body property (`ClientTest.kt:66`).
- **The public error's `cause` is the original engine failure, not the internal `TransportException` wrapper**, so
  no internal type appears in a caller's `cause` chain.

### Deliberately left undone

- **The per-call log line and `TYPESAFE_LOG_LEVEL`.** The map lists them as graduating from the client ticket,
  but neither is in this ticket's deliverable, the transport already emits the per-retry line, and the per-call
  line needs a clock seam. No `logLevel` field ships in `TypeSafeConfig`.
- **The `Usage.billing_units` shim.** `Usage` reads the two fields the wire sends and does not require
  `billing_units` — which is what the fixture pins — so the shim question stays with ticket 13.
- **`map.md` was not touched**, per the parent's instruction.

### Picked up by ticket 13

The fixture mapping is now a pure name match: `expect.error` → the class name, `expect.message` →
`Exception.message` (all five error fixtures already assert the extraction against their own bytes), and
`expect.field` → `APIResponseValidationError.field` (the malformed-answer fixture's `answers.urgent.noul` is
pinned by `ClientTest.aMalformedAnswerFailsWithTheFieldPath`). `ClientTest` already drives the real client over
`MockEngine` using a fixture's own request and response, so the loader's stub in `conformance/Conformance.kt`
can be replaced with the same call.
