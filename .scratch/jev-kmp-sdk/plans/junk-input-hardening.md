# Junk the server could send: pin the decode paths

A plan, **not a ticket**. Dispatched by the parent session; there is no entry for this work in the tracker, and
`map.md`, `docs/adr/`, `research/22-*` and every `issues/*.md` file are out of bounds. This file is both the spec
and the record — append your outcomes to it.

## Why this exists

Mutation testing's post-filter run (`build/reports/pitest/mutations.xml`, `39eaccb`: 727 mutants, 697 covered,
118–122 survivors) was classified by whether a caller could hit a survivor without the server misbehaving.
Three were pinned at `34c9369`. About **61** sit in the decode seams: the paths that read a server body the SDK
did not expect. They are not a random sample — they cluster on exactly the guards that make a malformed response
survivable, which is the property a user is paying this SDK for.

## What is already true, and therefore what is owed

The decode seams are already written defensively. The guards, the field-path helpers (`fieldPath`, `Answers.kt:87`)
and `ResponseValidationException` (`Answers.kt:81`) exist. The contract is written down:

- `README.md:156` — "`APIResponseValidationError` is a 200 whose body did not match the wire contract."
- `Answers.kt:96` — "a malformed **known** answer fails with a `ResponseValidationException` naming the exact
  field path", while an unknown primitive becomes `UnknownAnswer` rather than an error.
- `Models.kt:20` — `list()` throws `APIResponseValidationError` "if a 200 body is not" the wire contract.
- The brief (`01-lock-v0.1-design-brief.md:117`) fixes the error class names, and `:121` fixes client-side
  validation at two things, everything else forwarded.

**So the work is to pin behaviour that is already defined, not to invent validation.** Expect tests only.
Production code changes only where the probe below finds a throwable escaping the `JevError` tree, and then only
as one guard at the funnel (see *If a leak is real*). Adding per-field validation, new error classes, or new
public API is out of scope and is a stop-and-report.

## The six seams, with their survivor counts

| seam | survivors | what the server would have to get wrong |
| --- | --- | --- |
| `ModelsKt.decodeModelCard` / `.stringFieldOrNull` / `ModelsApi.list` | 8 + 4 + 4 | `{models: [...]}` where an entry or a field is not what the contract says |
| `ErrorMappingKt`: `asPublicError` / `extractErrorMessage` / `parseBody` / `validationEntry` / `detailField` / `errorFor` / `messageFrom` | 5+4+4+4+2+2+1 | an error body that is not JSON, or is JSON in a shape we do not know |
| `RetryKt.parseRetryAfterMs` | 7 | a junk `Retry-After` / `retry-after-ms` header — `0`, negative, non-numeric, HTTP-date, huge |
| `ClientKt.decodeSystemOneResponse` / `.intOrNull` | 4 + 1 | `answers` missing or not an object, `usage` malformed |
| `AnswersKt.decodeAnswer` / `.doubleAt` / `.stringAt` / `.asDouble` | 3 + 2 + 2 + 1 | a *known* answer with a wrong-typed field |
| `SystemOneResponse.answerOrNull` + `QuestionsKt.questionAccepts` | 2 + 1 | a well-formed answer of the wrong primitive, or a key the request never asked for |

Out of scope, and **say so in your report rather than touching them**: `RetryPolicy.<init>`'s 9 (caller input, a
programmer error, not junk from the wire), `HeadersKt.assembleHeaders` 7 and `ConfigKt`/`ResolvedConfig` 4 (caller
and environment input), `Transport.request` 7 / `TypeSafeClientImpl` 10 / `TransportKt` 6 / logging 3 (transport
and logging, not decoding), the synthetic frames.

## Method

1. **Characterise before you assert.** One probe per seam, feeding the matrix below through a `MockEngine` and
   *recording* what comes back: the returned value, or the throwable's class, `field` and message. Do this first
   and write the table into the outcomes section — it is the evidence the classification rests on, and it is
   what makes this a check rather than a guess.
2. **Classify each cell** into exactly one of three:
   - **P — pin it.** Deterministic *and* defensible for a caller *and* consistent with the quoted contract:
     write the test.
   - **L — leak.** A throwable that is not a `JevError` (NPE, `ClassCastException`, `IllegalStateException`)
     reaches the caller, or a lookup returns a wrong-typed value that blows up at the caller's assignment.
     That contradicts `README.md:156`, so it is a bug: fix it, then pin the fixed behaviour.
   - **U — undefined.** Neither defensible nor clearly wrong (sibling divergence, an ambiguous field path, a
     behaviour the brief does not fix). **Do not decide it.** List it in the outcome with the evidence and stop
     there; the parent owns that call.
3. **Pin (P), tests first.** `kotlin.test` in `commonTest`, `MockEngine` for the body, no new fixtures. Each
   test asserts the *exact* outcome — the thrown class **and** the `field` path **and** a message fragment, or the
   exact decoded value. A test that asserts only "it throws" kills nothing.
4. **Fix (L) at the funnel, once.** The body of a funnel already funnels through one entry point
   (`decodeModelCard`, `decodeAnswers`, `decodeSystemOneResponse`, `extractErrorMessage`), so one guard per
   funnel covers its whole field matrix. Map only unexpected throwables; never wrap `JevError`, never wrap
   `CancellationException`. Reuse `APIResponseValidationError` — it exists, and no `apiDump` move should be
   needed. A `ponytail:` comment names the ceiling if you take a shortcut.
5. **Prove non-vacuity by perturbation, per seam.** For each seam, perturb the production code the way that
   seam's mutants do — invert the guard, coerce the field, skip the validation — and watch the new test(s) fail
   for the expected reason. Record the perturbation and the failing test names. Do **not** try to prove this with
   a `pitestJvm` run: repeat runs flip ~6 of 727 mutants, so a change this size is inside the noise floor. The
   perturbation is the proof. Restore the code afterwards and confirm the tree is byte-clean.

### The input matrix

Applied per seam, with the columns relevant to that seam: an entry that is not an object (`5`, `"x"`, `[]`, `null`),
a field of the wrong JSON type (string where number, number where string, object where array), a missing key, an
explicit `null`, an empty body, a body that is not JSON at all, a top-level array where an object is expected,
and for numbers: `0`, negative, huge, fractional-where-integer. For the retry headers: `0`, `-1`, `abc`, `3.5`,
an HTTP-date in the past and in the future, and a value above the cap. For answers: an unknown `type`, and a
known type with a wrong primitive.

## Constraints

- `kotlin.test` only; `commonTest` + `MockEngine`. No new source set, no platform-specific test — nothing here is
  platform-specific.
- No new dependency; versions live in `gradle/libs.versions.toml` and stay untouched.
- No new conformance fixture. ADR `0005` caps that set at 16 and it is deliberately small; these are robustness
  paths of ours, not the API's wire contract. If you believe a fixture is warranted, report it instead.
- `explicitApi` is `Strict`: nothing new is public. Public API nobody asked for is a bug.
- Leave `map.md`, `docs/adr/**`, `research/22-*` and every `issues/*.md` alone — parallel work and the parent own
  them. You are editing this file and test/source files only.
- `CHANGELOG.md` gets a line only if a user would notice the change. Pin-only tests: no line.
- The gate is `./gradlew check` and it must be green before you push. CI is disabled (org billing), so this local
  run is the only verification that happens.
- If a fix would change a *user-visible* outcome that the siblings (JS/Python) do differently, or would need new
  public API, **stop and report** — do not decide it. Leave the worktree in place if you stop.

## Deliverable and record

- Tests (and, only if step 3 found a real leak, the funnel guard) on branch `junk-input-hardening`, pushed with a
  PR into `main`. Do not merge.
- An `## Outcomes` section appended to this file, carrying: the characterisation table, the P/L/U classification
  per seam, the pinned list with the test names, the perturbation run for each seam, the survivors that remain
  alive on purpose with the reason (equivalent, undefined, or out of scope), and the `check` result.
- Report back: branch, PR, gate, files, and anything you stopped on.

## Outcomes

Branch `junk-input-hardening`, PR [#15](https://github.com/snevadalabs/jev-kmp/pull/15) into `main` (open, not
merged). Gate: `./gradlew check` **green**
(ktlint, `apiCheck`, the Dokka KDoc gate, the version/CHANGELOG check, the Java 8 bytecode assertion, the Kover
floor at 94, and every test target this host runs: JVM, Android host, Apple, Linux). 258 JVM tests, 0 failures.

**No production change.** The probe below fed 200 junk bodies through the real client and every throwable that
reached a caller was already a `JevError`; no seam leaked an `NPE`, `ClassCastException` or
`IllegalStateException`, and no seam returned a wrong-typed value. So the plan's "one guard at the funnel" step
has nothing to fix and the whole deliverable is tests. The `api/jvm/jev-kmp.api` dump is untouched and no new
public declaration exists.

### 1. The characterisation probe

A throwaway `src/jvmTest/.../JunkInputProbe.kt` (deleted after this table was recorded) built the client with
`createClient(TypeSafeConfig(apiKey, engine = MockEngine { … }, retry = RetryPolicy(maxRetries = 0)), env = { null })`
and fed the plan's matrix through the public surface — `models.list()` for seam 1, `systemOne` for seams 2/4/5/6,
and the parsed `RateLimitError.retryAfterMs` for the `Retry-After` seam (which is the only path a caller sees
`parseRetryAfterMs` through). The outcome column is the returned value, or the throwable's class, whether it is a
`JevError`, its `field` and its message. 200 rows.

Two probe findings that shaped the classification: `JsonPrimitive.content` preserves the JSON literal (so a bare
non-string primitive's text equals the raw fallback and that guard cannot change a message), and the
`when(type)` in `decodeAnswer` lowers to a `hashCode` switch with `String.equals` guards (so three of its
conditional mutants are fast-path checks no input can distinguish).

| seam | input | outcome |
| --- | --- | --- |
| `models` | `empty` | `FAIL APIResponseValidationError jev=true field=models msg=GET /v1/models: expected { models: [...] }` |
| `models` | `blank` | `FAIL APIResponseValidationError jev=true field=models msg=GET /v1/models: expected { models: [...] }` |
| `models` | `not-json` | `FAIL APIResponseValidationError jev=true field=models msg=GET /v1/models: expected { models: [...] }` |
| `models` | `obj-empty` | `FAIL APIResponseValidationError jev=true field=models msg=GET /v1/models: expected { models: [...] }` |
| `models` | `top-array` | `FAIL APIResponseValidationError jev=true field=models msg=GET /v1/models: expected { models: [...] }` |
| `models` | `top-string` | `FAIL APIResponseValidationError jev=true field=models msg=GET /v1/models: expected { models: [...] }` |
| `models` | `top-number` | `FAIL APIResponseValidationError jev=true field=models msg=GET /v1/models: expected { models: [...] }` |
| `models` | `top-null` | `FAIL APIResponseValidationError jev=true field=models msg=GET /v1/models: expected { models: [...] }` |
| `models` | `models-missing` | `FAIL APIResponseValidationError jev=true field=models msg=GET /v1/models: expected { models: [...] }` |
| `models` | `models-num` | `FAIL APIResponseValidationError jev=true field=models msg=GET /v1/models: expected { models: [...] }` |
| `models` | `models-str` | `FAIL APIResponseValidationError jev=true field=models msg=GET /v1/models: expected { models: [...] }` |
| `models` | `models-obj` | `FAIL APIResponseValidationError jev=true field=models msg=GET /v1/models: expected { models: [...] }` |
| `models` | `models-null` | `FAIL APIResponseValidationError jev=true field=models msg=GET /v1/models: expected { models: [...] }` |
| `models` | `models-empty` | `OK []` |
| `models` | `entry-num` | `FAIL APIResponseValidationError jev=true field=models.0.name msg=GET /v1/models: models.0.name: expected a string field 'name'` |
| `models` | `entry-str` | `FAIL APIResponseValidationError jev=true field=models.0.name msg=GET /v1/models: models.0.name: expected a string field 'name'` |
| `models` | `entry-arr` | `FAIL APIResponseValidationError jev=true field=models.0.name msg=GET /v1/models: models.0.name: expected a string field 'name'` |
| `models` | `entry-null` | `FAIL APIResponseValidationError jev=true field=models.0.name msg=GET /v1/models: models.0.name: expected a string field 'name'` |
| `models` | `entry-empty-obj` | `FAIL APIResponseValidationError jev=true field=models.0.name msg=GET /v1/models: models.0.name: expected a string field 'name'` |
| `models` | `name-num` | `FAIL APIResponseValidationError jev=true field=models.0.name msg=GET /v1/models: models.0.name: expected a string field 'name'` |
| `models` | `name-null` | `FAIL APIResponseValidationError jev=true field=models.0.name msg=GET /v1/models: models.0.name: expected a string field 'name'` |
| `models` | `name-bool` | `FAIL APIResponseValidationError jev=true field=models.0.name msg=GET /v1/models: models.0.name: expected a string field 'name'` |
| `models` | `name-arr` | `FAIL APIResponseValidationError jev=true field=models.0.name msg=GET /v1/models: models.0.name: expected a string field 'name'` |
| `models` | `desc-num` | `OK [ModelCard(name=a, description=null, releaseDate=null)]` |
| `models` | `desc-null` | `OK [ModelCard(name=a, description=null, releaseDate=null)]` |
| `models` | `desc-obj` | `OK [ModelCard(name=a, description=null, releaseDate=null)]` |
| `models` | `date-num` | `OK [ModelCard(name=a, description=null, releaseDate=null)]` |
| `models` | `date-null` | `OK [ModelCard(name=a, description=null, releaseDate=null)]` |
| `models` | `index-1-bad` | `FAIL APIResponseValidationError jev=true field=models.1.name msg=GET /v1/models: models.1.name: expected a string field 'name'` |
| `models` | `index-0-bad` | `FAIL APIResponseValidationError jev=true field=models.0.name msg=GET /v1/models: models.0.name: expected a string field 'name'` |
| `models` | `valid` | `OK [ModelCard(name=a, description=d, releaseDate=2026-01-01)]` |
| `error-body` | `empty` | `FAIL BadRequestError jev=true field=null msg=HTTP 400` |
| `error-body` | `blank` | `FAIL BadRequestError jev=true field=null msg=HTTP 400` |
| `error-body` | `not-json` | `FAIL BadRequestError jev=true field=null msg=not json` |
| `error-body` | `html` | `FAIL BadRequestError jev=true field=null msg=<html><body>oops</body></html>` |
| `error-body` | `top-array` | `FAIL BadRequestError jev=true field=null msg=[1,2]` |
| `error-body` | `top-number` | `FAIL BadRequestError jev=true field=null msg=42` |
| `error-body` | `top-null` | `FAIL BadRequestError jev=true field=null msg=null` |
| `error-body` | `top-true` | `FAIL BadRequestError jev=true field=null msg=true` |
| `error-body` | `top-string` | `FAIL BadRequestError jev=true field=null msg=bare` |
| `error-body` | `obj-empty` | `FAIL BadRequestError jev=true field=null msg={}` |
| `error-body` | `arr-empty` | `FAIL BadRequestError jev=true field=null msg=[]` |
| `error-body` | `err-string` | `FAIL BadRequestError jev=true field=null msg=boom` |
| `error-body` | `err-obj` | `FAIL BadRequestError jev=true field=null msg=boom` |
| `error-body` | `err-num` | `FAIL BadRequestError jev=true field=null msg={"error":123}` |
| `error-body` | `err-null` | `FAIL BadRequestError jev=true field=null msg={"error":null}` |
| `error-body` | `err-arr` | `FAIL BadRequestError jev=true field=null msg={"error":[1]}` |
| `error-body` | `msg-string` | `FAIL BadRequestError jev=true field=null msg=boom` |
| `error-body` | `msg-num` | `FAIL BadRequestError jev=true field=null msg={"message":123}` |
| `error-body` | `detail-string` | `FAIL BadRequestError jev=true field=null msg=boom` |
| `error-body` | `detail-obj` | `FAIL BadRequestError jev=true field=null msg=boom` |
| `error-body` | `detail-num` | `FAIL BadRequestError jev=true field=null msg={"detail":42}` |
| `error-body` | `detail-arr-empty` | `FAIL BadRequestError jev=true field=null msg={"detail":[]}` |
| `error-body` | `detail-arr-num` | `FAIL BadRequestError jev=true field=null msg={"detail":[5]}` |
| `error-body` | `detail-arr-no-msg` | `FAIL BadRequestError jev=true field=null msg={"detail":[{"loc":["body","questions"]}]}` |
| `error-body` | `detail-loc-int` | `FAIL BadRequestError jev=true field=null msg=questions.impact: m` |
| `error-body` | `detail-loc-nested` | `FAIL BadRequestError jev=true field=null msg=m` |
| `error-body` | `detail-loc-string` | `FAIL BadRequestError jev=true field=null msg=m` |
| `error-body` | `detail-mixed-entries` | `FAIL BadRequestError jev=true field=null msg=questions: m` |
| `error-body` | `len-200` | `FAIL BadRequestError jev=true field=null msg=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx` |
| `error-body` | `len-201` | `FAIL BadRequestError jev=true field=null msg=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx…` |
| `error-body` | `len-500` | `FAIL BadRequestError jev=true field=null msg=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx…` |
| `error-body` | `msg-exponent` | `FAIL BadRequestError jev=true field=null msg={"message":1e3}` |
| `error-body` | `bare-exponent` | `FAIL BadRequestError jev=true field=null msg=1e3` |
| `error-body` | `bare-exponent-cap` | `FAIL BadRequestError jev=true field=null msg=1E3` |
| `error-body` | `bare-decimal` | `FAIL BadRequestError jev=true field=null msg=1.0` |
| `error-body` | `bare-neg-zero` | `FAIL BadRequestError jev=true field=null msg=-0` |
| `error-body` | `bare-zero` | `FAIL BadRequestError jev=true field=null msg=0` |
| `error-body` | `err-exponent` | `FAIL BadRequestError jev=true field=null msg={"error":1e3}` |
| `error-body` | `detail-exponent` | `FAIL BadRequestError jev=true field=null msg={"detail":1e3}` |
| `error-body` | `bare-nested-array` | `FAIL BadRequestError jev=true field=null msg=[[]]` |
| `error-status` | `400` | `FAIL BadRequestError jev=true field=null msg=boom` |
| `error-status` | `401` | `FAIL AuthenticationError jev=true field=null msg=boom` |
| `error-status` | `403` | `FAIL PermissionDeniedError jev=true field=null msg=boom` |
| `error-status` | `404` | `FAIL NotFoundError jev=true field=null msg=boom` |
| `error-status` | `408` | `FAIL APIError jev=true field=null msg=boom` |
| `error-status` | `418` | `FAIL APIError jev=true field=null msg=boom` |
| `error-status` | `422` | `FAIL UnprocessableEntityError jev=true field=null msg=boom` |
| `error-status` | `429` | `FAIL RateLimitError jev=true field=null msg=boom` |
| `error-status` | `499` | `FAIL APIError jev=true field=null msg=boom` |
| `error-status` | `500` | `FAIL InternalServerError jev=true field=null msg=boom` |
| `error-status` | `599` | `FAIL InternalServerError jev=true field=null msg=boom` |
| `error-status` | `600` | `FAIL APIError jev=true field=null msg=boom` |
| `error-status` | `302` | `FAIL APIError jev=true field=null msg=boom` |
| `json-primitive-content` | `1e3` | `content=1e3` |
| `json-primitive-content` | `1E3` | `content=1E3` |
| `json-primitive-content` | `1.0` | `content=1.0` |
| `json-primitive-content` | `0` | `content=0` |
| `json-primitive-content` | `-0` | `content=-0` |
| `json-primitive-content` | `42` | `content=42` |
| `json-primitive-content` | `1e400` | `content=1e400` |
| `retry-header` | `retry-after-ms=empty` | `OK retryAfterMs=null` |
| `retry-header-ms` | `retry-after-ms=empty` | `OK retryAfterMs=null` |
| `retry-header` | `retry-after-ms=0` | `OK retryAfterMs=0` |
| `retry-header-ms` | `retry-after-ms=0` | `OK retryAfterMs=0` |
| `retry-header` | `retry-after-ms=-1` | `OK retryAfterMs=null` |
| `retry-header-ms` | `retry-after-ms=-1` | `OK retryAfterMs=null` |
| `retry-header` | `retry-after-ms=abc` | `OK retryAfterMs=null` |
| `retry-header-ms` | `retry-after-ms=abc` | `OK retryAfterMs=null` |
| `retry-header` | `retry-after-ms=3.5` | `OK retryAfterMs=3500` |
| `retry-header-ms` | `retry-after-ms=3.5` | `OK retryAfterMs=3` |
| `retry-header` | `retry-after-ms=1500` | `OK retryAfterMs=1500000` |
| `retry-header-ms` | `retry-after-ms=1500` | `OK retryAfterMs=1500` |
| `retry-header` | `retry-after-ms=Infinity` | `OK retryAfterMs=null` |
| `retry-header-ms` | `retry-after-ms=Infinity` | `OK retryAfterMs=null` |
| `retry-header` | `retry-after-ms=-Infinity` | `OK retryAfterMs=null` |
| `retry-header-ms` | `retry-after-ms=-Infinity` | `OK retryAfterMs=null` |
| `retry-header` | `retry-after-ms=NaN` | `OK retryAfterMs=null` |
| `retry-header-ms` | `retry-after-ms=NaN` | `OK retryAfterMs=null` |
| `retry-header` | `retry-after-ms=1e300` | `OK retryAfterMs=9223372036854775807` |
| `retry-header-ms` | `retry-after-ms=1e300` | `OK retryAfterMs=9223372036854775807` |
| `retry-header` | `retry-after-ms=MAX` | `OK retryAfterMs=9223372036854775807` |
| `retry-header-ms` | `retry-after-ms=MAX` | `OK retryAfterMs=9223372036854775807` |
| `retry-header` | `retry-after-ms=60000` | `OK retryAfterMs=60000000` |
| `retry-header-ms` | `retry-after-ms=60000` | `OK retryAfterMs=60000` |
| `retry-header` | `retry-after-ms=60001` | `OK retryAfterMs=60001000` |
| `retry-header-ms` | `retry-after-ms=60001` | `OK retryAfterMs=60001` |
| `retry-header` | `retry-after=0` | `OK retryAfterMs=0` |
| `retry-header-ms` | `retry-after=0` | `OK retryAfterMs=0` |
| `retry-header` | `retry-after=-1` | `OK retryAfterMs=null` |
| `retry-header-ms` | `retry-after=-1` | `OK retryAfterMs=null` |
| `retry-header` | `retry-after=abc` | `OK retryAfterMs=null` |
| `retry-header-ms` | `retry-after=abc` | `OK retryAfterMs=null` |
| `retry-header` | `retry-after=3.5` | `OK retryAfterMs=3500` |
| `retry-header-ms` | `retry-after=3.5` | `OK retryAfterMs=3` |
| `retry-header` | `retry-after=2` | `OK retryAfterMs=2000` |
| `retry-header-ms` | `retry-after=2` | `OK retryAfterMs=2` |
| `retry-header` | `retry-after=1e300` | `OK retryAfterMs=9223372036854775807` |
| `retry-header-ms` | `retry-after=1e300` | `OK retryAfterMs=9223372036854775807` |
| `retry-header` | `retry-after=MAX` | `OK retryAfterMs=9223372036854775807` |
| `retry-header-ms` | `retry-after=MAX` | `OK retryAfterMs=9223372036854775807` |
| `retry-header` | `retry-after=Infinity` | `OK retryAfterMs=null` |
| `retry-header-ms` | `retry-after=Infinity` | `OK retryAfterMs=null` |
| `retry-header` | `retry-after=overflow` | `OK retryAfterMs=null` |
| `retry-header-ms` | `retry-after=overflow` | `OK retryAfterMs=null` |
| `retry-header` | `retry-after=120(above cap)` | `OK retryAfterMs=120000` |
| `retry-header-ms` | `retry-after=120(above cap)` | `OK retryAfterMs=120` |
| `retry-header` | `date-future` | `OK retryAfterMs=3988` |
| `retry-header` | `date-past` | `OK retryAfterMs=0` |
| `retry-header` | `date-malformed` | `OK retryAfterMs=null` |
| `retry-header` | `neither` | `OK retryAfterMs=null` |
| `retry-header` | `ms-invalid+ra-valid` | `OK retryAfterMs=2000` |
| `retry-header` | `ms-negative+ra-valid` | `OK retryAfterMs=2000` |
| `retry-header` | `ms-valid+ra-valid` | `OK retryAfterMs=1500` |
| `systemone` | `empty` | `FAIL APIResponseValidationError jev=true field=null msg=expected a JSON object response body` |
| `systemone` | `not-json` | `FAIL APIResponseValidationError jev=true field=null msg=expected a JSON object response body` |
| `systemone` | `top-array` | `FAIL APIResponseValidationError jev=true field=null msg=expected a JSON object response body` |
| `systemone` | `top-number` | `FAIL APIResponseValidationError jev=true field=null msg=expected a JSON object response body` |
| `systemone` | `top-string` | `FAIL APIResponseValidationError jev=true field=null msg=expected a JSON object response body` |
| `systemone` | `top-null` | `FAIL APIResponseValidationError jev=true field=null msg=expected a JSON object response body` |
| `systemone` | `obj-empty` | `FAIL APIResponseValidationError jev=true field=answers msg=answers: expected an object field 'answers'` |
| `systemone` | `answers-num` | `FAIL APIResponseValidationError jev=true field=answers msg=answers: expected an object field 'answers'` |
| `systemone` | `answers-str` | `FAIL APIResponseValidationError jev=true field=answers msg=answers: expected an object field 'answers'` |
| `systemone` | `answers-arr` | `FAIL APIResponseValidationError jev=true field=answers msg=answers: expected an object field 'answers'` |
| `systemone` | `answers-null` | `FAIL APIResponseValidationError jev=true field=answers msg=answers: expected an object field 'answers'` |
| `systemone` | `answers-empty` | `OK model=null usage=null` |
| `systemone` | `model-num` | `OK model=null usage=null` |
| `systemone` | `model-null` | `OK model=null usage=null` |
| `systemone` | `usage-num` | `OK model=null usage=null` |
| `systemone` | `usage-arr` | `OK model=null usage=null` |
| `systemone` | `usage-null` | `OK model=null usage=null` |
| `systemone` | `usage-empty` | `OK model=null usage=Usage(inputTokens=null, outputTokens=null)` |
| `systemone` | `tokens-str` | `OK model=null usage=Usage(inputTokens=null, outputTokens=null)` |
| `systemone` | `tokens-frac` | `OK model=null usage=Usage(inputTokens=null, outputTokens=null)` |
| `systemone` | `tokens-whole-float` | `OK model=null usage=Usage(inputTokens=null, outputTokens=null)` |
| `systemone` | `tokens-neg` | `OK model=null usage=Usage(inputTokens=-1, outputTokens=null)` |
| `systemone` | `tokens-overflow` | `OK model=null usage=Usage(inputTokens=null, outputTokens=null)` |
| `systemone` | `tokens-bool` | `OK model=null usage=Usage(inputTokens=null, outputTokens=null)` |
| `systemone` | `tokens-null` | `OK model=null usage=Usage(inputTokens=null, outputTokens=null)` |
| `systemone` | `tokens-both` | `OK model=null usage=Usage(inputTokens=12, outputTokens=3)` |
| `systemone` | `usage-extra` | `OK model=null usage=Usage(inputTokens=null, outputTokens=null)` |
| `answers` | `not-object-num` | `FAIL APIResponseValidationError jev=true field=answers.urgent msg=answers.urgent: expected an answer object` |
| `answers` | `not-object-str` | `FAIL APIResponseValidationError jev=true field=answers.urgent msg=answers.urgent: expected an answer object` |
| `answers` | `not-object-arr` | `FAIL APIResponseValidationError jev=true field=answers.urgent msg=answers.urgent: expected an answer object` |
| `answers` | `not-object-null` | `FAIL APIResponseValidationError jev=true field=answers.urgent msg=answers.urgent: expected an answer object` |
| `answers` | `no-type` | `FAIL APIResponseValidationError jev=true field=answers.urgent.type msg=answers.urgent.type: expected a string field 'type'` |
| `answers` | `type-num` | `FAIL APIResponseValidationError jev=true field=answers.urgent.type msg=answers.urgent.type: expected a string field 'type'` |
| `answers` | `type-empty` | `OK no-throw` |
| `answers` | `unknown-type` | `OK no-throw` |
| `answers` | `noul-missing` | `FAIL APIResponseValidationError jev=true field=answers.urgent.noul msg=answers.urgent.noul: expected a numeric field 'noul'` |
| `answers` | `noul-str` | `FAIL APIResponseValidationError jev=true field=answers.urgent.noul msg=answers.urgent.noul: expected a numeric field 'noul'` |
| `answers` | `noul-bool` | `FAIL APIResponseValidationError jev=true field=answers.urgent.noul msg=answers.urgent.noul: expected a numeric field 'noul'` |
| `answers` | `noul-obj` | `FAIL APIResponseValidationError jev=true field=answers.urgent.noul msg=answers.urgent.noul: expected a numeric field 'noul'` |
| `answers` | `noul-arr` | `FAIL APIResponseValidationError jev=true field=answers.urgent.noul msg=answers.urgent.noul: expected a numeric field 'noul'` |
| `answers` | `noul-null` | `FAIL APIResponseValidationError jev=true field=answers.urgent.noul msg=answers.urgent.noul: expected a numeric field 'noul'` |
| `answers` | `noul-zero` | `OK no-throw` |
| `answers` | `noul-negative` | `OK no-throw` |
| `answers` | `noul-above-one` | `OK no-throw` |
| `answers` | `noul-huge` | `OK no-throw` |
| `answers` | `noul-frac` | `OK no-throw` |
| `answers` | `choice-ok` | `OK no-throw` |
| `answers` | `choice-shape-on-noul` | `FAIL APIResponseValidationError jev=true field=answers.urgent.noul msg=answers.urgent.noul: expected a numeric field 'noul'` |
| `answers` | `choice-prob-num` | `FAIL APIResponseValidationError jev=true field=answers.urgent.probabilities msg=answers.urgent.probabilities: expected an object field 'probabilities'` |
| `answers` | `choice-prob-null` | `FAIL APIResponseValidationError jev=true field=answers.urgent.probabilities msg=answers.urgent.probabilities: expected an object field 'probabilities'` |
| `answers` | `choice-prob-str-value` | `FAIL APIResponseValidationError jev=true field=answers.urgent.probabilities.a msg=answers.urgent.probabilities.a: expected a number` |
| `answers` | `choice-prob-huge` | `OK no-throw` |
| `answers` | `choice-conf-missing` | `FAIL APIResponseValidationError jev=true field=answers.urgent.confidence msg=answers.urgent.confidence: expected a numeric field 'confidence'` |
| `answers` | `score-missing-legend` | `FAIL APIResponseValidationError jev=true field=answers.urgent.legend msg=answers.urgent.legend: expected an object field 'legend'` |
| `answers` | `score-key-neg` | `OK no-throw` |
| `answers` | `score-key-frac` | `FAIL APIResponseValidationError jev=true field=answers.urgent.legend.1.5 msg=answers.urgent.legend.1.5: expected an integer score ordinal` |
| `answers` | `score-key-huge` | `FAIL APIResponseValidationError jev=true field=answers.urgent.legend.99999999999 msg=answers.urgent.legend.99999999999: expected an integer score ordinal` |
| `answers` | `score-key-empty` | `FAIL APIResponseValidationError jev=true field=answers.urgent.legend. msg=answers.urgent.legend.: expected an integer score ordinal` |
| `answers` | `score-legend-arr` | `FAIL APIResponseValidationError jev=true field=answers.urgent.legend msg=answers.urgent.legend: expected an object field 'legend'` |
| `answers` | `score-value-str` | `FAIL APIResponseValidationError jev=true field=answers.urgent.score msg=answers.urgent.score: expected a numeric field 'score'` |
| `typed-access` | `wrong-primitive.answerOrNull` | `OK null` |
| `typed-access` | `wrong-primitive.get` | `FAIL AnswerTypeMismatchException jev=false field=null msg=Question 'urgent' is a NoulQuestion but the server answered with ChoiceAnswer.` |
| `typed-access` | `absent.answerOrNull` | `OK null` |
| `typed-access` | `absent.get` | `FAIL NoSuchElementException jev=false field=null msg=The response has no answer for question 'absent'.` |
| `typed-access` | `unknown.answerOrNull` | `OK null` |
| `typed-access` | `unknown.get` | `FAIL AnswerTypeMismatchException jev=false field=null msg=Question 'mystery' is a NoulQuestion but the server answered with UnknownAnswer.` |
| `typed-access` | `never-asked.answerOrNull` | `OK null` |
| `typed-access` | `never-asked.get` | `FAIL NoSuchElementException jev=false field=null msg=The response has no answer for question 'nope'.` |
| `typed-access` | `raw-map-keys` | `[extra, mystery, urgent]` |
| `typed-access` | `raw-extra` | `NoulAnswer(noul=0.5)` |
| `typed-access` | `unknown-raw-type` | `sentiment` |

### 2. Classification

**L (leak): none.** Two non-`JevError` throwables appear — `AnswerTypeMismatchException` and
`NoSuchElementException` — and neither is reachable from server bytes alone. Both are the documented caller-side
contract of `SystemOneResponse.get` (`Answers.kt`'s KDoc and `SystemOneResponse.kt`'s KDoc), and both need the
caller's own `Question` to ask the wrong thing or for a key the caller asked for that the server omitted; that
second case is documented as "simply absent". Every other row, including every malformed answer, error body,
`models` body, `usage` field and retry header, lands on `APIResponseValidationError`, the mapped `APIError`
subclass, or `UnknownAnswer` — exactly `README.md:156` and `Models.kt:20`.

**U (undefined — listed, not decided; the parent owns the call):**

1. **Integer `loc` segment.** `{"detail":[{"loc":["body","questions",0,"impact"],"msg":"m"}]}` extracts
   `questions.impact: m`; Python stringifies the index and produces `questions.0.impact`. Ticket 21 already
   recorded this divergence and `ErrorMappingTest.extractsTheFastApiValidationEntriesAndTheirFieldPaths`
   deliberately does not assert it. Probe row `error-body / detail-loc-int`.
2. **Empty score ordinal key.** `{"legend":{"":"x"}}` fails with `field = "answers.urgent.legend."` — a trailing
   dot, deterministic but an ambiguous path. Probe row `answers / score-key-empty`. Neither clearly defensible
   nor clearly wrong, so no test pins it.

Everything else in the matrix is deterministic, defensible for a caller and consistent with the quoted contract,
so it is **P** and pinned:

| seam | pinned behaviour | test |
| --- | --- | --- |
| `decodeAnswer` | an entry that is not an object fails at `answers.<id>` with the exact message; an empty `type` degrades to `UnknownAnswer("")` like any other unknown primitive | `AnswerDecodingTest.anAnswerEntryThatIsNotAnObjectFailsNamingTheAnswerField`, `.anEmptyTypeStringDegradesToUnknownAnswerLikeAnyOtherUnknownPrimitive` |
| `.doubleAt` / `.asDouble` | `0`, negative, fractional and `1e400`→`Infinity` are forwarded verbatim (the brief fixes validation at two things); an object, array or explicit `null` fails at the exact field instead of coercing | `.anOutOfRangeOrHugeNumberIsForwardedVerbatimRatherThanRangeChecked`, `.aNumericFieldThatIsNotAJsonNumberFailsNamingTheFieldInsteadOfBeingCoerced` |
| `.asScoreOrdinal` | `-1` is a valid ordinal; `1.5` and `99999999999` fail naming the offending key | `.scoreOrdinalsThatDoNotFitAnIntFailNamingTheOffendingKey` |
| `decodeModelCard` / `stringFieldOrNull` | an entry that is not a card, or one whose `name` is not a string, fails at `models.<index>.name`; a wrong-typed optional `description`/`release_date` reads as absent | `ClientTest.modelsListRejectsAnEntryThatIsNotACardAtItsOwnPosition`, `.modelsListDropsAnOptionalFieldOfTheWrongTypeRatherThanCoercingIt` |
| `ModelsApi.list` | `models` missing, not an array, `null`, or under a non-object top-level body fails at `models`; the index in `models.<i>.name` is the entry's real position | `.modelsListNamesTheEndpointWhenTheShapeIsWrong` (extended), `.modelsListRejectsAnEntryThatIsNotACardAtItsOwnPosition` |
| `decodeSystemOneResponse` | a top-level array/number/string/`null`/non-JSON fails with no field to name; `answers` missing or not an object fails at `answers`; a malformed known answer carries the field path **in the message too** | `.systemOneRejectsATopLevelBodyThatIsNotAnObject`, `.decodeRejectsAnAnswersFieldThatIsNotAnObject` (extended), `.aMalformedAnswerFailsWithTheFieldPath` (extended) |
| `.intOrNull` | a wrong-typed `usage`, or a stringified/fractional/overflowing/boolean counter, reads as absent; a real integer, including `-1`, is kept | `ClientTest.systemOneToleratesAMalformedUsageAndModelWithoutFailing` |
| `parseBody` | blank and malformed bodies parse to `null` without throwing | `ErrorMappingTest.aBlankOrNonJsonBodyParsesToNothingWithoutThrowing` (extended) |
| `extractErrorMessage` | blank → `""`; the cap is exactly 200 with the ellipsis starting at 201; an unshapeable JSON body falls back to its own raw text | `.capsTheExtractedMessageExactlyAtTheLengthBoundary`, `.aWrongJsonTypeOnAMessageFieldFallsBackToTheRawText` (extended) |
| `validationEntry` | a non-object entry is skipped and the rest still join; a non-string `loc` segment is dropped | `.extractsTheFastApiValidationEntriesAndTheirFieldPaths` (extended) |
| `errorFor` | the 5xx window is exactly `500..599`: `499` and `600` are a bare `APIError`, `599` is `InternalServerError` | `.mapsStatusesToClassesInOnePlace` (extended) |
| `parseRetryAfterMs` | `retry-after-ms` absent/non-numeric/non-finite/negative/blank falls through to `Retry-After`; `0` is a zero delay; `3.5` truncates to `3 ms`; a huge finite value saturates to `Long.MAX_VALUE` while `Infinity`/`1e400` are rejected | `RetryDelayTest.anUnusableRetryAfterMsHeaderFallsThroughToRetryAfter`, `.aFractionalRetryAfterMsTruncatesAndAHugeOneSaturates`, `.aHugeRetryAfterSecondsSaturatesAndANonFiniteOneIsRejected` |
| `answerOrNull` / `questionAccepts` | a well-formed answer of the wrong primitive, an unknown primitive, and a key the request never asked for all read back as `null`; `get` on an unknown primitive names both types | `SystemOneResponseTest.getOnAnUnknownPrimitiveNamesBothTypes` plus the pre-existing pair |

`asPublicError` needed no new test: `ClientTest.mapsTransportFailuresToTheConnectionAndTimeoutBranch` already
pins both directions (including `connection !is APITimeoutError` and the engine's failure as `cause`).

### 3. Non-vacuity proof by perturbation

Each perturbation was applied alone to the production source, the affected test class(es) run, then the file
restored with `git checkout --`. Seventeen perturbations, every one caught by the expected test(s); the new tests
below are named, and `git status` confirmed `src/commonMain` byte-clean afterwards.

| # | perturbation | tests that failed |
| --- | --- | --- |
| 1 | `Models.stringFieldOrNull`: `takeIf { it.isString }` → `takeIf { true }` | `modelsListDropsAnOptionalFieldOfTheWrongTypeRatherThanCoercingIt` |
| 2 | `Models.decodeModelCard`: drop the `isString` guard on `name` | `modelsListRejectsAnEntryThatIsNotACardAtItsOwnPosition` |
| 3 | `ErrorMapping.extractErrorMessage`: cap `<=` → `<` | `capsTheExtractedMessageExactlyAtTheLengthBoundary` |
| 4 | `ErrorMapping.detailField`: `detail.takeIf { it.isString }?.content` → `detail.content` | `aWrongJsonTypeOnAMessageFieldFallsBackToTheRawText` |
| 5 | `ErrorMapping.errorFor`: `in 500..599` → `in 500..598` | `mapsStatusesToClassesInOnePlace` |
| 6 | `Retry.parseRetryAfterMs`: `milliseconds >= 0` → `> 0` | `aFractionalRetryAfterMsTruncatesAndAHugeOneSaturates` |
| 7 | `Retry.parseRetryAfterMs`: drop `* 1000` on the seconds form | `aHugeRetryAfterSecondsSaturatesAndANonFiniteOneIsRejected`, `anUnusableRetryAfterMsHeaderFallsThroughToRetryAfter`, `aRetryAfterAboveTheCapFallsBackToTheBackoff`, `parsesRetryAfterSeconds` |
| 8 | `Client.decodeSystemOneResponse`: top-level object guard `?: throw` → `?: JsonObject(emptyMap())` | `systemOneRejectsATopLevelBodyThatIsNotAnObject`, `anEmpty200BodyFailsLoudlyInsteadOfReadingAsAnEmptyResult` |
| 9 | `Client.intOrNull`: `takeIf { !it.isString }` → `takeIf { true }` | `systemOneToleratesAMalformedUsageAndModelWithoutFailing`, `decodeTreatsAWrongJsonTypeOnModelAndUsageAsAbsent` |
| 10 | `Client.decodeSystemOneResponse`: `answers` object guard `?: throw` → `?: JsonObject(emptyMap())` | `decodeRejectsAnAnswersFieldThatIsNotAnObject` |
| 11 | `Answers.decodeAnswer`: non-object guard → `JsonObject(emptyMap())` | `anAnswerEntryThatIsNotAnObjectFailsNamingTheAnswerField`, `aKnownAnswerFieldWithTheWrongJsonTypeFailsNamingTheFieldPath` |
| 12 | `Answers.decodeAnswer`: `"noul"` branch key renamed | 8 tests incl. `anOutOfRangeOrHugeNumberIsForwardedVerbatimRatherThanRangeChecked`, `decodesTheThreePrimitivesFromTheMixedFixture` |
| 13 | `Answers.decodeAnswer`: `else` degrade path → throw | `anEmptyTypeStringDegradesToUnknownAnswerLikeAnyOtherUnknownPrimitive`, `anUnknownAnswerTypeBecomesUnknownAnswerAndKeepsTheRawPayload`, `everyFixtureResponseDecodesOrFailsAtTheFieldTheFixtureNames` |
| 14 | `Answers.doubleAt`: `?: throw` → `?: 0.0` | 5 tests incl. `aNumericFieldThatIsNotAJsonNumberFailsNamingTheFieldInsteadOfBeingCoerced` |
| 15 | `Answers.asScoreOrdinal`: `toIntOrNull() ?: throw` → `?: 0` | `scoreOrdinalsThatDoNotFitAnIntFailNamingTheOffendingKey`, `aMalformedScoreKeyFailsNamingTheOffendingEntry` |
| 16 | `Questions.questionAccepts`: `is NoulQuestion -> answer is NoulAnswer` → `true` | `getOnAnUnknownPrimitiveNamesBothTypes` + 3 |
| 17 | `SystemOneResponse.answerOrNull`: drop the `questionAccepts` guard | `answerOrNullReturnsNullForAnUnknownPrimitive`, `answerOrNullReturnsNullWhereGetThrows` |

No `pitestJvm` run was used to prove any of this; the run below is a measurement taken afterwards.

### 4. Measurement

The same `pitestJvm` configuration as `39eaccb` (`STRONGER`, `excludedMethods = *lambda*`), before → after:

| | before | after |
| --- | --- | --- |
| mutants generated | 727 | 727 |
| killed | 580 | **610** |
| no coverage | 30 | 29 |
| survivors | 117 | **88** |
| test strength | 83% | **87%** |
| line coverage (mutated classes) | 612/636 (96%) | 618/636 (97%) |
| survivors in the six seams | 61 | **31** |

Per seam: Models 16→4, ErrorMapping 22→14, `parseRetryAfterMs` 7→1, `decodeSystemOneResponse`/`intOrNull` 5→2,
Answers 8→7, `answerOrNull`/`questionAccepts` 3→3. The three unchanged seams are the equivalent-mutant families
below, not gaps. Outside the six seams the count moved 56→57: one mutant flipped back to SURVIVED, which is
inside the ±6/run noise floor ticket 22 measured, and is why this run is reported as evidence and not used to
prove anything.

### 5. Survivors left alive on purpose

| seam | method | mutator | n |
| --- | --- | --- | --- |
| `Answers.kt` | `asDouble` | `RemoveConditionalMutator_EQUAL_IF` | 1 |
| `Answers.kt` | `decodeAnswer` | `RemoveConditionalMutator_EQUAL_ELSE` | 3 |
| `Answers.kt` | `doubleAt` | `RemoveConditionalMutator_EQUAL_IF` | 1 |
| `Answers.kt` | `stringAt` | `RemoveConditionalMutator_EQUAL_ELSE` | 1 |
| `Answers.kt` | `stringAt` | `RemoveConditionalMutator_EQUAL_IF` | 1 |
| `Client.kt` | `decodeSystemOneResponse` | `RemoveConditionalMutator_EQUAL_ELSE` | 1 |
| `Client.kt` | `intOrNull` | `RemoveConditionalMutator_EQUAL_IF` | 1 |
| `ErrorMapping.kt` | `asPublicError` | `RemoveConditionalMutator_EQUAL_ELSE` | 2 |
| `ErrorMapping.kt` | `asPublicError` | `RemoveConditionalMutator_EQUAL_IF` | 3 |
| `ErrorMapping.kt` | `extractErrorMessage` | `RemoveConditionalMutator_EQUAL_ELSE` | 2 |
| `ErrorMapping.kt` | `extractErrorMessage` | `RemoveConditionalMutator_EQUAL_IF` | 1 |
| `ErrorMapping.kt` | `messageFrom` | `RemoveConditionalMutator_EQUAL_IF` | 1 |
| `ErrorMapping.kt` | `parseBody` | `ConditionalsBoundaryMutator` | 1 |
| `ErrorMapping.kt` | `parseBody` | `RemoveConditionalMutator_EQUAL_IF` | 2 |
| `ErrorMapping.kt` | `parseBody` | `RemoveConditionalMutator_ORDER_IF` | 1 |
| `ErrorMapping.kt` | `validationEntry` | `RemoveConditionalMutator_EQUAL_ELSE` | 1 |
| `Models.kt` | `decodeModelCard` | `RemoveConditionalMutator_EQUAL_IF` | 1 |
| `Models.kt` | `list-k1IrOU0` | `RemoveConditionalMutator_EQUAL_IF` | 1 |
| `Models.kt` | `list-k1IrOU0` | `RemoveConditionalMutator_ORDER_ELSE` | 1 |
| `Models.kt` | `list-k1IrOU0` | `VoidMethodCallMutator` | 1 |
| `Questions.kt` | `questionAccepts` | `RemoveConditionalMutator_EQUAL_IF` | 1 |
| `Retry.kt` | `parseRetryAfterMs` | `RemoveConditionalMutator_EQUAL_IF` | 1 |
| `SystemOneResponse.kt` | `answerOrNull` | `RemoveConditionalMutator_EQUAL_IF` | 2 |
| | | **total** | **31** |

Reasons:

- **Models** — 3 equivalent (the entry/name guards are covered by the rows in §2 and no input changes the
  decoded value) and 1 mechanical (`CollectionsKt.throwIndexOverflow` on `mapIndexed`'s counter, unreachable for
  a catalogue of any size).
- **ErrorMapping** — all 14 equivalent. `asPublicError`'s 5 sit on `message ?: "…"`, and both `TransportException`
  subclasses set that message in their own constructor, so the elvis never sees `null` (and the rest of the
  sealed `when` is unreachable). `extractErrorMessage`'s early empty return is redundant: the fall-through path
  parses `""`, fails inside `runCatching` and lands on `trimmed` == `""` anyway. `messageFrom`'s guard is
  equivalent because a bare non-string primitive's `.content` is its literal text, which is the raw fallback.
  `parseBody`'s 4 are equivalent for the same reason — every path that could differ is inside `runCatching`, so
  the empty and malformed bodies both yield `null`. `validationEntry`'s remaining one is the covered
  non-object-entry guard.
- **`parseRetryAfterMs`** — 1 equivalent: the header matrix in §2 covers every form, and the remaining
  short-circuit cannot be reached by a different header value.
- **Client** — 2 equivalent. The `decodeSystemOneResponse` one is the `failure.message ?: failure.fieldPath`
  elvis: `ResponseValidationException.message` is built from the field path and is never `null`, and the message
  is now pinned exactly. The `intOrNull` one is covered by the stringified/fractional/overflow rows.
- **Answers** — 7 equivalent. The three `decodeAnswer` ones are the `when(String)` hashCode/equals fast-path
  checks; the branch selection itself is pinned (perturbation 12 fails 8 tests). The `doubleAt`/`stringAt`/
  `asDouble` ones are the shape guards covered by the wrong-type matrix, with no input that changes the value.
- **`answerOrNull` / `questionAccepts`** — 3 equivalent. `answerOrNull`'s elvis returns `null` for a missing key
  through either path, and the `questionAccepts` arm is the last of an exhaustive sealed `when`, so a
  non-`ScoreQuestion` cannot reach it.

**Out of scope, deliberately not touched** (per the plan): `RetryPolicy.<init>` 9, `HeadersKt.assembleHeaders` 7,
`ConfigKt`/`ResolvedConfig` 5, `Transport.request`/`toTransportResponse`/`responseLine`/`failureLine`/
`asTransportFailure` 13, `TypeSafeClientImpl`/`createClient` plumbing 11, `Logging` 1, `Platform.jvm.platformEnv` 1.
`Questions.toWireJson` 1 and `Retry.transportFailureKind` 4 also remain and are request encoding / transport
classification rather than decode seams. No conformance fixture was added (ADR 0005's cap stands) and
`CHANGELOG.md` is unchanged (pin-only tests are not user-visible).

### 6. Files

`src/commonTest/.../AnswerDecodingTest.kt`, `ClientTest.kt`, `ErrorMappingTest.kt`, `RetryDelayTest.kt`,
`SystemOneResponseTest.kt`. No file under `src/commonMain` changed; the probe file was deleted. A later ticket
could pick up the two **U** items above and the out-of-scope survivor families, and could decide whether the
`decodeAnswer` fast-path survivors are worth an arcmutate Kotlin filter.
