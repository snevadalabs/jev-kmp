# Lock the v0.1 design brief

Type: grilling
Status: resolved
Blocked by:

## Question

What is this SDK, precisely — what does it ship, on which targets, with which stack, with what public surface, and by what standard is it tested? Every other ticket on this map hangs off these answers, so they are settled before anything else is charted.

Resolved across three grilling rounds against a full recon of the TypeSafe docs and both official SDKs (v0.6.0).

## Answer

Eighteen locked decisions.

**Amended after research.** Ten sections have been revised in place where later tickets' evidence overruled them; each is marked `[amended by …]`, with the logs in [Reconcile the client shape and answer access with the Kotlin SDK survey](16-reconcile-client-shape-and-answer-access.md), [Reconcile retry against Ktor's built-in HttpRequestRetry](17-reconcile-retry-vs-ktor-plugin.md) and [Scaffold the repo and its CI gate](09-scaffold-repo-and-ci-gate.md). §10 is settled by [Reconcile retry against Ktor's built-in HttpRequestRetry](17-reconcile-retry-vs-ktor-plugin.md): the hand-rolled loop was rejected in favour of Ktor's built-in plugin with our own delay policy.

### 1. Effort shape

The map **builds the SDK**, it does not stop at a spec — this is an explicit override of wayfinder's "plan, don't do" default, recorded in the map's Notes. It ends at a publishable `0.1.0` on Maven Central, not at feature parity with Python and JS.

### 2. Identity

- **Coordinates:** `com.sierranevadalabs:jev-kmp`. Neutral, ours, shippable day one, transferable upstream later as a Maven-only change.
- **Package:** `com.sierranevadalabs.jev.sdk`. The requested `com.sierranevadalabs.jev-sdk` is not a legal Kotlin package (`-` is not permitted in a package segment); hyphen → dot preserves the intent and mirrors the coordinate.
- **Artifact name:** `jev-kmp`. **Public class name:** `TypeSafeClient`, matching both siblings.
- **Version source:** one place (`gradle.properties`), asserted against `CHANGELOG.md` by a CI script — the pattern both siblings use.

### 3. Targets

Tier 1: **JVM, Android, Apple (`iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64`), Linux x64**. Written as a real KMP build from line one so JS/Wasm is additive later, which means the API surface must avoid JVM-only idioms even where only the JVM is tested.

**[amended by *Scaffold the repo and its CI gate*]** `macosX64` is **not buildable** on the pinned toolchain: Kotlin deprecated the x86_64 macOS target in 2.3.20 and KGP 2.3.21 rejects the declaration as an error, so it is dropped and `macosArm64` is the Apple desktop target. `iosX64` is **Tier 3** in Kotlin's own tier table rather than Tier 1 — it compiles and ships, but it is compile-only and never test-executed, because no arm64 runner can run it. The honest tier split is Tier 1 `macosArm64`/`iosSimulatorArm64`/`iosArm64`, Tier 2 `linuxX64`, Tier 3 `iosX64`, plus JVM and Android.

### 4. Stack

**Ktor client + kotlinx.serialization + kotlinx.coroutines.** Ktor is reached through an **injectable engine seam** (`HttpClientEngine`), which is what makes `MockEngine`-in-`commonTest` possible and lets a caller bring their own engine without our semver contract swallowing Ktor's.

**[amended by *Reconcile the client shape and answer access with the Kotlin SDK survey*]** The original rule — "Ktor types are **internal**" — contradicted itself, since `HttpClientEngine` *is* a Ktor type and the same paragraph requires it. Restated as a count rather than a vibe: **Ktor types appear in exactly two opt-in config parameters and nowhere else** — `engine: HttpClientEngine?` and `httpClientConfig: HttpClientConfig<*>.() -> Unit = {}` — with no `HttpResponse`, `HttpClient`, `HttpRequestBuilder`, or `HttpStatusCode` in any signature. The escape hatch is included because without it a caller cannot install `Logging` or custom auth; hiding Ktor entirely was rejected because it would cost us our own `MockEngine` test tier to protect a principle nobody asked for. **[amended by *Reconcile retry against Ktor's built-in HttpRequestRetry*]** The caller's block runs **before** our own installs, so `HttpRequestRetry` and `HttpTimeout` are installed last and the required `HttpRequestRetry` → `HttpTimeout` ordering cannot be broken from a caller's config block. The consequence is accepted: a caller-installed `Logging` sits *outside* the retry loop and logs one line per request, not per attempt — per-attempt lines are ours.

A **default engine** ships so callers configure nothing: OkHttp on JVM/Android, Darwin on Apple, CIO on Linux, resolved via `expect/actual`. `TypeSafeClient` is `AutoCloseable` and closes the engine it created, never one it was handed.

### 5. Parity scope

Core parity with the siblings: client, all three primitives, `models.list()`, retry policy, the full 12-class error tree, `apiKey`/`baseUrl`/`defaultModel`/`timeout`/`defaultHeaders`/`retry` config, and request-id surfacing.

**[amended by *Reconcile the client shape and answer access with the Kotlin SDK survey*]** The carve-out list above is the rule for what is *out*, so per-call `model: String? = null` and `timeout: Duration? = null` are in scope by omission. **[amended by *Reconcile retry against Ktor's built-in HttpRequestRetry*]** Per-call `retry` is **in** scope for 0.1.0 as `retry: RetryPolicy? = null`, where `null` means the client's policy. The transport translates it into a complete per-request `HttpRequestRetry` configuration; Ktor's `HttpRequestRetry.request { }` is not exposed, because a partial use of it silently restores Ktor's own predicate and delay curve.

**Deferred:** `extra_body`, pluggable `Logger` abstraction, per-call extra headers, structured logging conventions.

**Carve-out — logging is not deferrable.** Logging is off by default; when enabled it logs method, path, status, duration and request id and **never headers or bodies**. That is fewer lines than the siblings' redaction tables and cannot be defeated by a header name nobody thought to blacklist. **[amended by *Reconcile retry against Ktor's built-in HttpRequestRetry*]** A retry also logs one line per attempt — attempt number, status or cause — from a subscription to Ktor's `HttpRequestRetryEvent`. Still no headers, still no bodies, which is why per-attempt error bodies are not a requirement anywhere in the design.

### 6. Concurrency

**Suspend-only.** No blocking facade. Python's dual sync/async clients are its single largest source of duplication (two near-verbatim 190-line clients, two models resources) and a blocking API is a deadlock footgun on Android's main thread. `runBlocking { }` is documented for JVM callers who want it.

### 7. Answer model and typed access

A **`sealed interface Answer` hierarchy** — `Noul`, `Choice`, `Score`, and `Unknown(type: String, raw: JsonElement)` — with `data class` subtypes for value equality in tests. The `Unknown` variant is what lets a future server-side primitive degrade instead of crashing a shipped client; Python does this by skipping with a warning, a sealed type needs the explicit variant.

**[amended by *Reconcile the client shape and answer access with the Kotlin SDK survey*]** The typed key **is** the wire question; the `"id" to …` infix is gone, because it built a public `Pair` only to glue an id to a question that can hold the id itself.

```kotlin
val category = choice("category", "What is this about?", mapOf("billing" to null, "technical" to null))
val urgent   = noul("urgent", "Does this convey urgency?")

val r = client.systemOne("Help! My payouts have been failing for 3 days.", category, urgent)
val c: ChoiceAnswer = r[category]      // throws AnswerTypeMismatchException on mismatch
val m = r.answerOrNull(category)       // null on mismatch
```

`ChoiceQuestion : Question<ChoiceAnswer>`; `operator fun <T : Answer> get(q: Question<T>): T` throws, and `answerOrNull` returns `null` for both a type mismatch and an unknown primitive — supabase's `decodeAs`/`decodeAsOrNull` twin exactly. `AnswerTypeMismatchException` lives in the root package, **not** in `.errors`: it is a programming error, and nothing from the network can produce it.

`r.answers: Map<String, Answer>` stays available as the raw escape hatch, and it is where `UnknownAnswer` surfaces. No codegen.

**Consequence, accepted deliberately:** because `when (r.answers[id])` is exhaustive, the day the server adds a primitive and we add a variant, every consumer's exhaustive `when` stops compiling. That is the right failure — a compile error beats silently dropping data — but **adding a primitive is a source-breaking change** and is documented and released as one, never in a patch.

**Divergence from both siblings, forced by the language.** Both put the question's name in the *container key* (`{"billing": noul("Is this about billing?")}`); we put it inside the question. JS types its response off its request using TypeScript mapped types (`SystemOneRequest<Q>` → `SystemOneResult<Q>`), which is what makes the container-key form viable there. **Kotlin has no equivalent** — which is exactly why this brief reached for a typed key object, and a key object can only work if the question knows its own name. Sibling-literal request syntax and statically typed access are mutually exclusive here; typed access wins.

### 8. Value representation

`JsonElement` in the core, with `String` and `Map<String, String?>` convenience overloads so the common call site has zero ceremony:

```kotlin
client.systemOne("some text", category)                        // convenience
client.systemOne(buildJsonObject { /* ... */ }, category)      // full power
```

No `Entry` wrapper type — `JsonElement` is already the KMP JSON currency. `Any?` was rejected: it gives up compile-time checking on the thing users type most.

### 9. Config resolution

`explicit → env → default`, with the siblings' env names (`TYPESAFE_API_KEY`, `TYPESAFE_BASE_URL`, `TYPESAFE_DEFAULT_MODEL`, `TYPESAFE_LOG_LEVEL`) and defaults (`https://api.typesafe.ai`, `jev-latest`, 10s). Env access via `expect fun platformEnv(name: String): String?` — JVM `System.getenv`, Apple `NSProcessInfo`, Android falls back to the JVM actual.

**[amended by *Reconcile the client shape and answer access with the Kotlin SDK survey*]** `TypeSafeClient` is an **interface** with an `internal class TypeSafeClientImpl` and a top-level `fun TypeSafeClient(config): TypeSafeClient` factory — the shape every mature KMP SDK uses, and the reason the committed api dump contains an interface rather than an implementation. The factory reads identically to a constructor at the call site.

**Non-negotiable and unchanged:** it is **not a `data class`**. A generated `toString()` prints `apiKey` verbatim; both siblings go out of their way to keep the key out of `toString()`, `repr()` and JSON serialization, and their tests assert it. No implementation type gets a generated `toString()` over the config either.

**[amended — see *Reconcile the client shape and answer access with the Kotlin SDK survey*]** Note that the fakeability this shape is usually justified by is **not demonstrated anywhere**: across openai-kotlin, supabase-kt, Python and JS, no fake client exists in any test suite. The interface is kept for api-dump hygiene and convention, not for a benefit nobody exercises.

### 10. Retry

**[amended by *Reconcile retry against Ktor's built-in HttpRequestRetry*] Ktor's built-in `HttpRequestRetry`, with our own delay policy.** The original hand-rolled loop is rejected. Three of the four gaps that justified it do not exist: `delayMillis(respectRetryAfterHeader = false) { }` stores our block verbatim and its `HttpRetryDelayContext` exposes the response headers, so the whole JS delay policy drops in; our retryable predicate never reads a body, so the non-`suspend` predicate costs nothing; and §5 forbids logging bodies, so per-attempt error bodies have no consumer. We also *gain* `HttpRequestRetryEvent`, which a loop we owned could not give us.

- `RetryPolicy` — a `data class` with `Duration`-typed fields, the siblings' field set with the `Ms` suffix dropped (`backoffInitial`, `backoffMax`, `maxRetryAfter`, `backoffJitter`, `maxRetries`, `httpStatuses: Set<Int>`, `respectRetryAfter`, `apiConnectionError`, `apiTimeoutError`), `require`-validated in `init`, status set copied. Defaults mirror the siblings: `maxRetries = 2`, initial `500ms`, max `5s`, jitter `0.25`, statuses `{408, 429, 500..599}`, `respectRetryAfter = true`.
- **Delay policy matches JS exactly** — subtractive jitter, `round(min(initial * 2^attempt, max) * (1 - random * jitter))`, whole milliseconds, exponent base 0 at the first retry, injectable `random`. A server `Retry-After` above `maxRetryAfter` falls back to our backoff (JS), deliberately diverging from Python's uncapped honour.
- **Retryable** = configured status codes, or a transport failure: the three timeout classes gated by `apiTimeoutError`, every other `IOException` by `apiConnectionError`, `CancellationException` never.
- **Determinism** comes from Ktor's public `delay { }` seam with an `internal` injection point — the same seam Ktor's own tests use — not from a public field on `RetryPolicy`, which would break its value equality.
- **Per-call `retry: RetryPolicy? = null`** is in scope, translated into a complete per-request configuration on every request.
- **Per-attempt `timeout` plus JS's `maxRetryAfter` cap.** Python's 30s whole-loop budget is not ported; it adds a second timeout concept to explain.
- **Accepted and documented**: OkHttp's `retryOnConnectionFailure` can invisibly re-send a POST after a non-timeout `IOException`, and the attempt count is not caller-visible.
- **Score validation matches JS, not Python.** The API requires at least two score levels; JS enforces `>= 2`, Python only enforces non-empty and eats a 422. We enforce `>= 2`.

### 11. Errors

Kotlin package `com.sierranevadalabs.jev.sdk.errors`, class names **verbatim** as the siblings (`BadRequestError`, `AuthenticationError`, `PermissionDeniedError`, `NotFoundError`, `UnprocessableEntityError`, `RateLimitError`, `InternalServerError`, plus the connection/timeout/validation branch). `TypeSafeClient` is the only `TypeSafe`-prefixed public name. Verbatim names mean an existing `catch` block ports by name.

**Client-side validation is exactly two things**: a non-empty question map, and score criteria with at least two levels. Everything else is forwarded to the server, matching both siblings.

`RateLimitError` carries `retryAfterMs`, parsed from `retry-after-ms` then `retry-after` (seconds or HTTP-date), rejecting non-finite and negative values. **[amended by *Reconcile retry against Ktor's built-in HttpRequestRetry*]** That parser is one **internal** function shared with the retry delay policy — neither sibling exports its own — and `respectRetryAfter = false` disables both header forms.

### 12. Observability surface

`SystemOneResponse` carries `model`, `answers`, `usage`, `requestId`, `status`, and `headers: Map<String, String>`. **No raw response accessor** — that would put `HttpResponse` in our public API and contradict §4. `models.list()` returns a **bare `List<ModelCard>`**; a one-field wrapper is an abstraction with one caller.

**[amended by *Reconcile the client shape and answer access with the Kotlin SDK survey*]** `models` is a nested resource object, not a flat method:

```kotlin
public interface TypeSafeClient : AutoCloseable {
    public val models: Models
    public suspend fun systemOne(...): SystemOneResponse
}
public interface Models { public suspend fun list(): List<ModelCard> }
```

The alternative — `TypeSafeClient : SystemOne, Models, AutoCloseable`, openai-kotlin's grouping-by-inheritance — was rejected because it makes the call site `client.list()`, which reads as *list what?*; openai-kotlin only gets away with it because its resource names are pluralized per resource (`assistants()`, `batches()`, `models()`). Two resources is not enough to justify three public interfaces.

**One abstract method per endpoint; convenience overloads are extension functions.** So the interface's `systemOne` is a single `JsonElement` member and the `String` overload is an extension forwarding to it. The implementor contract stays minimal and the convenience surface can grow without breaking external implementors.

`systemOne` takes `vararg questions`; Kotlin permits `vararg` with **zero** arguments, so the non-empty check in §11 remains a runtime check despite the signature implying otherwise. Non-null `state`, with JS's `state: null` covered by `JsonNull` rather than a third overload.

### 13. Wire fingerprint

`X-TypeSafe-SDK: typesafe-sdk-kotlin/<version>`, `X-TypeSafe-Runtime: <platform>/<version>`, distinct from the siblings so upstream can see a Kotlin SDK exists. `X-TypeSafe-Retry-Count: <n>` on retries only. Caller-supplied protected headers — including that retry header — are stripped and re-set. **[amended by *Reconcile retry against Ktor's built-in HttpRequestRetry*]** The count is written by `HttpRequestRetry`'s `modifyRequest`, which only runs on retries, so a header-free attempt 0 is guaranteed by construction rather than by our own bookkeeping.

### 14. Toolchain

Single Gradle module; `libs.versions.toml`; `explicitApi()`; **binary-compatibility-validator with a committed `api/` dump**; ktlint; Dokka. The API dump is the one convention that turns the compatibility promise into a CI failure instead of a user's problem — and it is precisely the gate neither predecessor has.

**[amended by *Reconcile the client shape and answer access with the Kotlin SDK survey*]** Two refinements. First, **no `@InternalJev` opt-in marker at 0.1.0** — it only earns its place when a `@PublishedApi internal` member must be reachable from a `public inline` function, and nothing here is `inline` or reified; add it the day the first one appears. Second, before `0.1.0` tags the dump is a **stop-and-look gate, not a compatibility promise**: any public-surface change must appear as a deliberate reviewed diff — never a silent `apiDump` — with no semver promise until the first tag, at which point the dump becomes the published baseline. Pinned versions for every tool named here are in [Pin the KMP toolchain and target matrix](02-pin-kmp-toolchain-and-targets.md).

### 15. The CI gate

GitHub Actions on every push and PR running `ktlintCheck + apiCheck + allTests` (JVM on Linux, Apple on macOS). Tag-driven Maven Central publish via the vanniktech plugin and the Sonatype Central Portal, with PGP signing and CHANGELOG-validated release notes.

This exists because **both siblings fail here**: Python has 534 tests and CI never runs them; JS has a 95% coverage floor inside a `prepublishOnly` that `npm publish <tarball>` never triggers.

### 16. Conformance fixtures

A **shared cross-language wire-conformance fixture set** — 10–15 hand-written JSON cases — that this SDK's tests consume and that the Python and JS suites can adopt unchanged.

Cases: happy-path request bodies (all three primitives, `String` and structured `state`), happy-path responses, unknown answer `type`, missing `usage`, `billing_units`-absent, all four error body shapes (`{error:{message}}`, `{error:string}`, `{detail:string}`, FastAPI `{detail:[{loc,msg}]}`), a non-JSON error body, and a `Retry-After` case.

They live **in this repo** at `conformance/` — plain JSON plus a `manifest.json` plus a short ADR defining the format. We cannot create repos in the upstream org, and a shared repo would make all three suites depend on a second repo before anyone agreed to maintain it. Declarative JSON means a Go or Ruby SDK can adopt the same files. If upstream blesses it, `git mv` the folder and nothing breaks.

### 17. Live-API tests

Gated behind an **explicit opt-in flag**, not merely the presence of a key. The Python suite's accident — a developer with `TYPESAFE_API_KEY` exported silently firing ~29 live doc examples in the default test run — is exactly the trap to not reproduce.

### 18. Docs

README (quickstart, parity with the siblings' READMEs) + KDoc on every public declaration + Dokka HTML as a CI artifact. `explicitApi()` makes missing KDoc a compile error, so coverage is enforced rather than hoped for. README code blocks are compiled as tests, which is how Python's `test_docs.py` catches doc rot — roughly 15 lines with `kotlin-test`.
