# jev-kmp

Kotlin Multiplatform SDK for the TypeSafe / Jev System One API. Jev answers typed questions about a state:
whether something is true (`noul`), which of a set of options it is (`choice`), and where it falls on an ordered
scale (`score`) — with calibrated probabilities rather than generated text. Learn what TypeSafe can do in the
[TypeSafe docs](https://docs.typesafe.ai/).

> **Status: unreleased.** `0.1.0` is under construction. The public API is not yet stable and no artifact has
> been published, so the coordinates above are what `0.1.0` will publish, not something to depend on today.

| | |
|---|---|
| Coordinates | `com.sierranevadalabs:jev-kmp` |
| Package | `com.sierranevadalabs.jev.sdk` |
| Targets | JVM, Android, Apple (iOS `arm64`/`simulatorArm64`/`x64`, macOS `arm64`), Linux `x64` |
| License | MIT |

## Quickstart

Install the SDK (Gradle Kotlin DSL):

```kts
dependencies {
    implementation("com.sierranevadalabs:jev-kmp:0.1.0")
}
```

The HTTP client is built on [Ktor](https://ktor.io), but Ktor types appear in exactly two opt-in configuration
parameters (`engine` and `httpClientConfig`) and nowhere else in the public surface.

Set `TYPESAFE_API_KEY` in your environment, then ask one question of each primitive and read the answers back:

```kotlin
import com.sierranevadalabs.jev.sdk.ChoiceAnswer
import com.sierranevadalabs.jev.sdk.ScoreAnswer
import com.sierranevadalabs.jev.sdk.TypeSafeClient
import com.sierranevadalabs.jev.sdk.TypeSafeConfig
import com.sierranevadalabs.jev.sdk.choice
import com.sierranevadalabs.jev.sdk.noul
import com.sierranevadalabs.jev.sdk.score
import com.sierranevadalabs.jev.sdk.systemOne

// Reads TYPESAFE_API_KEY, TYPESAFE_BASE_URL, TYPESAFE_DEFAULT_MODEL and TYPESAFE_LOG_LEVEL; see TypeSafeConfig
// for precedence. Pass apiKey = "…", baseUrl = "…" or defaultModel = "…" to override the environment.
val client = TypeSafeClient(TypeSafeConfig())

// The question carries its own id, so the question object is also the key its answer comes back under.
val category = choice("category", "What is this about?", mapOf("billing" to null, "technical" to null))
val urgent = noul("urgent", "Does this convey urgency?")
val tone = score("tone", "What is the tone?", listOf("calm", "neutral", "angry"))

client.use {
    val response = it.systemOne("I was charged twice. Please fix this ASAP.", category, urgent, tone)

    val c: ChoiceAnswer = response[category] // typed: throws AnswerTypeMismatchException, never a wrong type
    val s: ScoreAnswer? = response.answerOrNull(tone) // the non-throwing twin: null instead of throwing
    println("${c.choice} at confidence ${c.confidence}; tone ${s?.score}")
}
```

Every call is `suspend`, so wrap it in your own coroutine scope; a JVM caller who wants a blocking call writes
`runBlocking { }`. `TypeSafeClient` is `AutoCloseable` and closes the HTTP engine it created, never one you
passed in.

### Logging

Logging is **off by default**. Turn it on with `TypeSafeConfig(logLevel = LogLevel.Info)`, or by setting
`TYPESAFE_LOG_LEVEL` to one of `debug`, `info`, `warn`, `error` or `off` (read case-insensitively; an
unrecognised value is rejected with an error naming the variable rather than silently turning logging off).
`info` writes one line per call — method, path, status, duration and request id — and one line per retry; `debug`
writes the same lines today and is reserved for the byte-level logging of a later logger abstraction; `warn` and
`error` write nothing, and exist so a `TYPESAFE_LOG_LEVEL` that works against the Python or JavaScript SDK does
not throw here. A failed call is logged too, at the level that is on, with the status it failed on.

No level writes a header, a body, the query string, or the API key. Every line is built from the method, path,
status, duration and request id alone, so there is no redaction table that can be incomplete.

## Parity and differences from the Python and JavaScript SDKs

- **Noul criteria are ported.** A `noul` question describes its yes and no outcomes with `NoulCriteria`, encoded
  exactly as the siblings encode them: a `criteria` object whose undescribed sides are omitted, never `null`.
- **Suspend-only.** There is no blocking client, and no `AsyncTypeSafeClient` twin. Python's sync/async pair is
  two near-verbatim clients kept in step by hand; Kotlin needs one.
- **Typed question keys.** The question object *is* the key: `choice("category", …)` is both the request and the
  statically typed key `response[category]` returns a `ChoiceAnswer` from. The siblings put the id in the outer
  key of the `questions` map instead, which Kotlin cannot mirror while keeping the answer statically typed.
- **Retry is Ktor's built-in `HttpRequestRetry`, driven by our `RetryPolicy`.** One retry loop, not two, and a
  `Retry-After` larger than `maxRetryAfter` falls back to the backoff rather than waiting for it — Python honours
  the server's value uncapped.

  ```kotlin
  import com.sierranevadalabs.jev.sdk.RetryPolicy
  import com.sierranevadalabs.jev.sdk.TypeSafeClient
  import com.sierranevadalabs.jev.sdk.TypeSafeConfig
  import com.sierranevadalabs.jev.sdk.noul
  import com.sierranevadalabs.jev.sdk.systemOne
  import kotlin.time.Duration.Companion.seconds

  val client = TypeSafeClient(TypeSafeConfig(retry = RetryPolicy(maxRetries = 4, maxRetryAfter = 30.seconds)))
  val urgent = noul("urgent", "Does this convey urgency?")

  // A single call can replace the retry policy and the per-attempt timeout without touching the client.
  client.systemOne(
      "Help! My payouts have been failing for 3 days.",
      urgent,
      retry = RetryPolicy(maxRetries = 0),
      timeout = 30.seconds,
  )

  // The model catalogue takes the same two per-call overrides.
  client.models.list(timeout = 30.seconds, retry = RetryPolicy(maxRetries = 0))
  ```

- **A `score` question needs at least two levels.** Fewer is rejected locally with an `IllegalArgumentException`
  instead of spending a `422`; both SDKs require it on the wire.
- **The client reports its resolved settings.** `baseUrl`, `defaultModel`, `timeout`, `retry`, `logLevel` and
  `defaultHeaders` read back the value that actually took effect after `explicit → environment → default` — six
  of the settings the JavaScript SDK exposes. The API key has no accessor, here or there.
- **No log line can carry a header, a body or the API key.** Logging is off by default — a deliberate divergence
  from the JavaScript SDK, whose default is `warn` — and when it is enabled, `info` reports one line per call
  (method, path, status, duration, request id) and one line per retry. Nothing else is formatted at all, so no
  blacklist of header names can be incomplete.
- **The public API dump is committed.** `api/` is compared on every build, so the surface cannot change without
  a reviewed diff.

## Error handling

Every failure the SDK raises is a `JevError`. The classes match the Python and JavaScript SDKs by name, so an
existing `catch` block ports by name; only the root is ours.

```kotlin
import com.sierranevadalabs.jev.sdk.TypeSafeClient
import com.sierranevadalabs.jev.sdk.TypeSafeConfig
import com.sierranevadalabs.jev.sdk.errors.AuthenticationError
import com.sierranevadalabs.jev.sdk.errors.JevError
import com.sierranevadalabs.jev.sdk.errors.RateLimitError
import com.sierranevadalabs.jev.sdk.noul
import com.sierranevadalabs.jev.sdk.systemOne

val client = TypeSafeClient(TypeSafeConfig())
val urgent = noul("urgent", "Does this convey urgency?")

try {
    client.systemOne("Help! My payouts have been failing for 3 days.", urgent)
} catch (e: RateLimitError) {
    println("throttled; the server asked for ${e.retryAfterMs} ms, request id ${e.requestId}")
} catch (e: AuthenticationError) {
    println("the key was rejected: ${e.message}")
} catch (e: JevError) {
    println("the call failed: ${e.message}")
}
```

`APIError` carries `status`, `body` and `requestId`; its subclasses are `BadRequestError`,
`AuthenticationError`, `PermissionDeniedError`, `NotFoundError`, `UnprocessableEntityError`, `RateLimitError`
and `InternalServerError`. `APIResponseValidationError` is a 200 whose body did not match the wire contract.
`APIConnectionError` — with `APITimeoutError` as its subclass — covers the failures where no response arrived,
and they carry the engine's own failure as `cause`. A caller's cancellation is a `CancellationException` and is
never wrapped.

## Running the tests

```bash
./gradlew check
```

`check` runs ktlint, the public-API dump comparison, the Dokka KDoc gate, the version/CHANGELOG consistency
check, the Java 8 bytecode assertion, the coverage floor, and every test target the current host can execute.
The conformance suite replays `conformance/` — the shared cross-language wire fixtures — through the real client
over Ktor's `MockEngine`, so it needs no network and no API key.

The live tier hits the real API, so it is opt-in twice over — a Gradle property **and** an environment variable
— and a default `./gradlew check` can never reach the network or spend money:

```bash
TYPESAFE_API_KEY=… ./gradlew jvmTest -Ptypesafe.live=true
```

`integration.yml` runs the same command nightly and on every push to `main`, using the `JEV_API_KEY` repository
secret.

### Mutation testing, on demand

```bash
./gradlew pitestJvm
```

PIT mutates the JVM compilation of `commonMain` and writes `build/reports/pitest/` (read `mutations.xml`; the
HTML report mis-attributes line numbers for inlined Kotlin). It re-runs the suite once per mutant and takes
about two minutes, so it is deliberately **not** part of `check`. Run it before a release; read the test
strength it prints, then every survivor, because a surviving mutant is a behaviour no test observes.

## Documentation

Learn what TypeSafe can do in the [TypeSafe docs](https://docs.typesafe.ai/). This SDK's own API reference is
KDoc on every public declaration — `explicitApi(Strict)` and a Dokka `failOnWarning` gate keep it that way — and
Dokka HTML is built as a CI artifact. The fenced Kotlin blocks in this file are compiled by the test suite, so a
signature change that leaves them stale fails the build.

See [`CONTEXT.md`](CONTEXT.md) for the vocabulary this project uses for the API and the decisions behind it.

## Compatibility

The public API dump under `api/` is committed and compared on every build. Until `0.1.0` is tagged it is a review
gate rather than a promise: any public-surface change must show up as a deliberate, reviewed diff rather than a
silent `apiDump`. From the first tag onward it is the published baseline.
