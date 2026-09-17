# Survey established Kotlin SDK patterns

Researched: 2026-09-17

Method: shallow clones read directly. No blog posts, no memory. Every claim below cites a file path
in the clone (line numbers at the time of reading).

| Library | Commit | HEAD date | What it is |
|---|---|---|---|
| `aallam/openai-kotlin` | `9574ca3` | 2026-02-07 | Kotlin/KMP port of a sibling SDK (OpenAI). **Closest analog to us.** 213 `commonMain` `.kt` files, 2 published modules + BOM |
| `supabase-community/supabase-kt` | `0a23260` | 2026-09-15 | KMP client SDK, plugin-per-feature. 337 `commonMain` `.kt` files, 6 published modules + serializers + samples |
| `gitliveapp/firebase-kotlin-sdk` | `8164bb1` | 2026-09-08 | KMP wrapper over many native SDKs, `expect`/`actual` heavy. 55 `commonMain` `.kt` files, 15 modules |
| `awslabs/aws-sdk-kotlin` | `77c5b80` | 2026-09-16 | Generated + hand-written runtime. Codegen pipeline, runtime core delegated to `smithy-kotlin` |
| `ktorio/ktor` (`ktor-client-core`, `ktor-client-mock`, `ktor-utils`) | `37a29f9` | 2026-09-15 | The engine-injection and attribute-key reference implementation |

Clone root used: `/tmp/kotlin-sdks/`.

---

## TL;DR verdict

The mature Kotlin SDKs converge on a **small, boring shape**:

- One public `interface X` + one `internal class XImpl`, plus a top-level factory function. **Zero** of the
  four product SDKs expose a public concrete client class.
- A **config class** (constructor params with defaults) — not a builder, not a DSL — except where the
  number of knobs genuinely explodes (supabase, which uses `@SupabaseDsl`).
- **Exceptions, not `Result`** — but a *sealed base* exception so callers can exhaustively branch, with
  exactly one translation site that converts transport exceptions into SDK exceptions.
- For "typed value keyed by a name": **`AttributeKey<T>`-style typed key + `getOrNull`**, or a
  **`@JvmInline value class` wrapping the raw string** with companion constants. Never `enum class` on a
  string the server owns — the single violation found in the whole survey is flagged in §2e and it throws
  `NoSuchElementException`.
- Unknown server variant from the wire: **do not throw**. Either let the string through (value class) or
  return `null` / drop with a log line. Only throw for *malformed*, not *unrecognised*.
- Everything is `AutoCloseable`/`Closeable` and **does not close a dependency instance it was handed** —
  Ktor formalises this as `manageEngine = true|false`.

The single most copyable, least-over-engineered file in the whole survey is
`openai-kotlin/openai-client/src/commonMain/kotlin/com.aallam.openai.client/OpenAI.kt` — 62 lines that
define the entire public entry point.

---

## Comparative table

| Question | openai-kotlin | supabase-kt | firebase-kotlin-sdk | aws-sdk-kotlin | ktor-client |
|---|---|---|---|---|---|
| **1. Construction** | `interface OpenAI` + `internal class OpenAIApi`; factory `fun OpenAI(config: OpenAIConfig)`; also a param-per-option overload | `interface SupabaseClient` + `internal class SupabaseClientImpl`; `createSupabaseClient(url, key) { }` DSL builder | `object Firebase` + `expect fun Firebase.app(...)`; data-class options | Generated `XxxClient` per service + `SdkClient`; runtime split into separate library | `class HttpClient(engine, config)` + `fun HttpClient(engineFactory, block)` |
| **1. Closes injected dep?** | `AutoCloseable`; closes its *own* internal `HttpClient`; injected `HttpClientEngine` survives because ktor's `manageEngine=false` | `suspend fun close()` closes http client + plugins | delegates to native SDK | `SdkClient.close()` | **Explicit**: `manageEngine = true` for factory, `false` for instance |
| **2. Typed accessor keyed by name** | None. 45 `@JvmInline value class` wrappers instead (`Status`, `FinishReason`, `ModelId`, `Endpoint`, `RequestId`) | `SupabasePluginProvider.key: String` → `inline fun <reified Plugin> getPlugin(provider)`; `PostgrestResult.decodeAs<T>()`; sealed `PostgresAction` | `object Firebase` + `expect` extension functions; sealed `ActionCodeResult` | `AttributeKey<String>` constants on `AwsClientOption` (same class as ktor's) | `AttributeKey<T>` data class + `Attributes.getOrNull(key)` |
| **2. Unknown variant from server** | `ignoreUnknownKeys=true`; value-class strings pass through; custom serializers **throw** `SerializationException` for unknown *object* shapes | `RealtimeEvent.resolveEvent()` → `null`, caller logs and returns; `decodeAsOrNull()` → `null` | `else -> throw SerializationException(...)` in serializers | non-matching error code → `UnknownRestException`-equivalent / generic `ServiceException` | n/a (attribute keys are local) |
| **3. Retry** | Ktor `HttpRequestRetry` plugin, 429 only, exponential; config `RetryStrategy(maxRetries, base, maxDelay)` | none at HTTP layer; only auth session-refresh retry with `retryDelay: Duration` | none (native SDK retries) | own `RetryStrategy`/`StandardRetryStrategy`/`AdaptiveRetryStrategy` in `smithy-kotlin`; `AwsRetryPolicy` decides what is retryable | `HttpRequestRetry` plugin; `delayMillis{}` + public `delay{ suspend (Long) -> Unit }` override |
| **4. Errors** | Sealed `OpenAIException` → `OpenAIAPIException` (sealed) → 5 concrete; `OpenAIServerException`; `OpenAIIOException` (sealed) → 2 concrete. **One** translation site | `open class RestException` → 4 subclasses; `HttpRequestException : IOException`; one `parseErrorResponse` boundary | `expect open class FirebaseAuthException` + 11 subclasses; platform error *code* string | `AwsServiceException` / `ClientException` / `ConfigurationException`; error metadata bag, not a type per error | `HttpCallValidator`, `ResponseException`; `expectSuccess` opt-in |
| **5. Surface discipline** | `explicitApi()`, BCV, `apiCheck` in CI, 4 opt-in markers (`Beta/Experimental/Internal/Legacy`) | **No** `explicitApi()`, **no** BCV, **no** api dump. 3 markers (`SupabaseInternal/Dsl/Experimental`), detekt, `sdk-compliance.yaml` | `explicitApi()`, BCV, per-target dumps (`api/jvm/`, `api/android/`) | BCV + `apiValidation { ignoredPackages += ... }`, `@InternalSdkApi`, `@GeneratedApi` non-public marker | BCV with **both** `*.api` (JVM) and `*.klib.api` (KMP ABI) |
| **6. Modules** | `openai-core` (models) + `openai-client` (transport) + `openai-client-bom`; `sample/{jvm,js,native}` | one module per feature + `Supabase` core + `serializers/*` + `test-common` + separate `integration-test` | one module per Firebase product + `firebase-common`, `firebase-common-internal`, `test-utils` | `aws-runtime/{aws-core,aws-config,aws-http,aws-endpoint}` + one module **per AWS service** + `codegen/*` + `tests/*` | one module per engine/plugin, huge split |
| **7. Tests** | bulk in `commonTest`, live tests gated by `OPENAI_LIVE_TESTS` env var, engine deps in `jvmTest`/`jsTest`/`nativeTest` | `src/commonTest` per module; **MockEngine** in commonTest; separate `integration-test` module | `src/commonTest` + per-target test source sets | `common/test`, `jvm/test`, per-service `e2eTest` source sets | `common/test` + `jvm/test` |

---

## 1. Client construction

### openai-kotlin — the pattern to copy verbatim

`openai-client/src/commonMain/kotlin/com.aallam.openai.client/OpenAI.kt:13-62`

```kotlin
public interface OpenAI : Completions, Files, Edits, Embeddings, Models, Moderations, FineTunes, Images, Chat,
    Audio, FineTuning, Assistants, Threads, Runs, Messages, VectorStores, Batch, Responses, AutoCloseable

public fun OpenAI(config: OpenAIConfig): OpenAI {
    val httpClient = createHttpClient(config)
    val transport = HttpTransport(httpClient)
    return OpenAIApi(transport)
}
```

Note the shape:

- **Public surface = one interface.** The concrete `internal class OpenAIApi` is nowhere in `api/openai-client.api`.
- **The interface is composed of feature interfaces and those are delegated**, not hand-written:
  `internal class OpenAIApi(private val requester: HttpRequester) : OpenAI, Completions by CompletionsApi(requester), ... AutoCloseable by requester`
  (`openai-client/.../internal/OpenAIApi.kt:13-33`). 19 delegate clauses; each `*Api` is `internal class ModelsApi(private val requester: HttpRequester) : Models`.
  Fake-ability for free (an `OpenAI` is 19 small interfaces), zero inheritance ceremony.
- **Two factories, one config.** `fun OpenAI(token: String, ... 9 defaulted params)` and `fun OpenAI(config: OpenAIConfig)`. The first just builds the second. `OpenAIConfig` itself is a plain class with defaulted params — **not** a builder, **not** a DSL.
  (`openai-client/.../OpenAIConfig.kt:13-38`)
- **Engine injection is a nullable instance, not a factory**: `public val engine: HttpClientEngine? = null` (`OpenAIConfig.kt:35`), then
  `openai-client/.../internal/HttpClient.kt:89-91`:
  ```kotlin
  return if(config.engine != null) { HttpClient(config.engine, configuration) } else { HttpClient(configuration) }
  ```
  A `HttpClientConfig<*>.() -> Unit = {}` escape hatch is also exposed (`OpenAIConfig.kt:37`) so callers can `install(plugin)`.

### Ownership of an injected dependency — Ktor's answer is the definitive one

Ktor does not guess; it encodes ownership in the constructor:

`ktor-client/ktor-client-core/common/src/io/ktor/client/HttpClient.kt`

```kotlin
// :646-653  injected FACTORY -> client owns the engine
public fun <T : HttpClientEngineConfig> HttpClient(engineFactory: HttpClientEngineFactory<T>, ...): HttpClient {
    val engine = engineFactory.create(config.engineConfig)
    return HttpClient(engine, config, manageEngine = true)
}

// :962-965  injected INSTANCE -> caller owns the engine
public fun HttpClient(engine: HttpClientEngine, block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(engine, HttpClientConfig<HttpClientEngineConfig>().apply(block), manageEngine = false)
```

and `close()` (`:1476-1491`) closes plugins + `clientJob.complete()` — the engine is closed only via the
`manageEngine` completion hook (`:1289-1302`). The KDoc on `close()` says it outright:

> `Manual Engine Management`: If a custom `engine` was manually created, it must be closed explicitly
> after calling `client.close()` to release all resources.

**Consequence for openai-kotlin**: because `RetryStrategy`/`engine` go through ktor's instance overload,
`HttpTransport.close() { httpClient.close() }` (`internal/http/HttpTransport.kt:62-64`) plus `AutoCloseable by requester`
(`OpenAIApi.kt:33`) yields correct ownership **without any code**. That is the lazy win: delegate
`AutoCloseable` to the transport and let the HTTP library do the bookkeeping.

### supabase-kt — same shape, replaced config class with a DSL builder

`Supabase/src/commonMain/kotlin/io/github/jan/supabase/SupabaseClient.kt:54-61, 78-79`
```kotlin
suspend fun close()   // suspicious: suspend close is a plugin-lifecycle smell
interface SupabaseClient { val config: SupabaseClientConfig; ... }
internal class SupabaseClientImpl(override val config: SupabaseClientConfig) : SupabaseClient
```
Construction is `createSupabaseClient(url, key) { useHTTPS = ...; httpEngine = engine; install(Postgrest) }`,
a `@SupabaseDsl class SupabaseClientBuilder` with ~20 `var`s (`SupabaseClientBuilder.kt:26-120`). Engine
injection: `var httpEngine: HttpClientEngine? = null` (line 46) → `if(engine != null) HttpClient(engine) {...} else HttpClient {...}`
(`network/KtorSupabaseHttpClient.kt:64-66`). Same ownership semantics as openai-kotlin.

There is also a `@PublishedApi internal constructor` / `@PublishedApi internal fun build()` on the builder:
`SupabaseClientBuilder.kt:29, 107`. That is the standard trick to keep a DSL entry point public while the
constructor stays internal — worth knowing, but only if you need a DSL.

### firebase-kotlin-sdk — a singleton object, not a client instance

`firebase-app/src/commonMain/kotlin/dev/gitlive/firebase/Firebase.kt:18-52`
```kotlin
public object Firebase
public expect fun Firebase.app(name: String): FirebaseApp
```
Features attach as `expect` extension properties on that object. This is a wrapper-shape (there is a real
native SDK underneath), **not** applicable to a plain HTTP client.

### aws-sdk-kotlin — one client class per service, runtime in another library

`aws-runtime/aws-core/build.gradle.kts` depends on `api(libs.smithy.kotlin.runtime.core)` — the actual
client runtime (retries, HTTP, attributes) is *not* in this repo. `AwsClientOption` only declares
keys (`aws-runtime/aws-core/common/src/aws/sdk/kotlin/runtime/client/AwsClientOption.kt`).

### Verdict for us (Q1)

`interface X : FeatureA, FeatureB, AutoCloseable` + `internal class XImpl` + `fun X(config: XConfig)`.
One config class, defaulted params, nullable `HttpClientEngine?` for injection, one `HttpClientConfig<*>.() -> Unit`
escape hatch. Delegate `AutoCloseable` to the transport. **Do not** build a DSL builder for 15 files.

---

## 2. Typed accessors keyed by a name — the deepest dive

There are exactly **four** distinct established answers. They are not interchangeable; pick by whether the
name is *yours* (compile-time) or *the server's* (runtime).

### 2a. `AttributeKey<T>` — your own name, compile-time-known type (ktor, aws)

`ktor-utils/common/src/io/ktor/util/Attributes.kt:19,31-40`
```kotlin
public inline fun <reified T : Any> AttributeKey(name: String): AttributeKey<T> =
    AttributeKey(name, typeInfo<T>())

public data class AttributeKey<T : Any> @JvmOverloads constructor(
    public val name: String,
    private val type: TypeInfo = typeInfo<Any>(),
) {
    init { require(name.isNotBlank()) { "Name can't be blank" } }
}
```
plus `interface Attributes` with `get`/`getOrNull`/`put`. The `TypeInfo` second field is a reified-type
token used for diagnostics; for a hand-written SDK you do not need it.

aws-sdk-kotlin uses **the same pattern** via its runtime dependency:
`aws-runtime/aws-core/common/src/aws/sdk/kotlin/runtime/client/AwsClientOption.kt:21,26`
```kotlin
public object AwsClientOption {
    public val Region: AttributeKey<String> = AttributeKey("aws.smithy.kotlin#AwsRegion")
    public val AccountId: AttributeKey<String> = AttributeKey("aws.sdk.kotlin#AccountId")
}
```
Note the namespaced string (`"aws.smithy.kotlin#AwsRegion"`) — because two libraries share one attributes bag.

**Cost/benefit**: `AttributeKey` buys you *one* untyped bag holding N typed values, which is the right
answer when a call carries request-scoped options (timeouts, region, tracing) across layers you do not
control. It costs a public generic class, a type token, and a name-collision policy.
`openai-kotlin` looked at this and **rejected it** — see `RequestOptions` below.

### 2b. `@JvmInline value class` wrapping the server's string (openai-kotlin) — best fit for server-defined variants

`openai-kotlin` has **45** `@JvmInline` value classes in `openai-core/src/commonMain` and only **3** `enum class`
(`LogLevel`, `Logger`, `AssistantStreamEventType` — the third is a server-owned set and is its one bug; see §2e).

`openai-core/src/commonMain/kotlin/com.aallam.openai.api/core/Status.kt:8-40`
```kotlin
@Serializable
@JvmInline
public value class Status(public val value: String) {
    public companion object {
        public val Succeeded: Status = Status("succeeded")
        public val Processed: Status = Status("processed")
        ...
        public val Running: Status = Status("running")
        public val Expired: Status = Status("expired")
    }
}
```
Same for `FinishReason` (`core/FinishReason.kt`), `ModelId`, `Endpoint`, `RequestId`, `OrganizationId`.

Why this is the right shape for a *server-owned* variant set:
- An unrecognised `"cancelling_2"` from the server round-trips as `Status("cancelling_2")` instead of
  blowing up the whole deserialization of a response envelope.
- It serialises as a bare JSON string, so nothing changes on the wire.
- The `case` can be handled with `==` against the constants, and an exhaustive `when` is impossible —
  which is correct, because the set is not closed.

The one thing it does *not* give you is exhaustiveness. If you need exhaustive handling, that is a
different pattern (2d).

### 2c. `String` key → reified typed lookup, with an explicit `OrNull` variant (supabase-kt)

`Supabase/src/commonMain/kotlin/io/github/jan/supabase/plugins/SupabasePluginProvider.kt:14` and
`Supabase/src/commonMain/kotlin/io/github/jan/supabase/plugins/PluginManager.kt:18-34`
```kotlin
interface SupabasePluginProvider<Config, PluginInstance : SupabasePlugin<Config>> {
    val key: String     // "This key is used to identify the plugin within the PluginManager"
    ...
}

inline fun <reified Plugin: SupabasePlugin<Config>, Config, Provider : SupabasePluginProvider<Config, Plugin>>
    getPluginOrNull(provider: Provider): Plugin? = installedPlugins[provider.key] as? Plugin

inline fun <reified Plugin: SupabasePlugin<Config>, Config, Provider : SupabasePluginProvider<Config, Plugin>>
    getPlugin(provider: Provider): Plugin =
    getPluginOrNull(provider) ?: error("Plugin ${provider.key} not installed or not of type ${Plugin::class.simpleName}...")
```
Consumed as an ergonomic extension property, `Postgrest/src/commonMain/.../Postgrest.kt:146-147`:
```kotlin
val SupabaseClient.postgrest: Postgrest get() = pluginManager.getPlugin(Postgrest)
```
The whole registry is `Map<String, SupabasePlugin<*>>` and the lookup is a **cast + null**, not a
reflective registry.

**The key lesson is the paired API**: `getPlugin` (throws, message names the missing key) *and*
`getPluginOrNull` (returns null). Both. Every time.

### 2d. Sealed hierarchy keyed by the server's discriminator, with a `null`-tolerant resolver

`Realtime/src/commonMain/kotlin/io/github/jan/supabase/realtime/PostgresAction.kt:45-90`
```kotlin
sealed interface PostgresAction: SerializableData {
    override val columns: List<Column>
    override val commitTimestamp: Instant
    data class Insert(...) : PostgresAction, HasRecord
    data class Update(...) : PostgresAction, HasRecord, HasOldRecord
    data class Delete(...) : PostgresAction, HasOldRecord
    data class Select(...) : PostgresAction, HasRecord
}
```
Note the capability interfaces `HasRecord` / `HasOldRecord` — `Insert` and `Select` both have a new record;
`Update` has both; `Delete` has only the old one. Callers `when` on the type and get exactly the fields that
exist. That is a genuinely good idea and it is *cheap*.

And the resolver that turns the wire string into that hierarchy, `Realtime/src/commonMain/kotlin/io/github/jan/supabase/realtime/event/RealtimeEvent.kt:18-53`:
```kotlin
internal sealed interface RealtimeEvent {
    suspend fun handle(channel: RealtimeChannel, message: RealtimeMessage)
    fun appliesTo(message: RealtimeMessage): Boolean
    companion object {
        private val EVENTS = setOf(RBroadcastEvent, RCloseEvent, ..., RSystemReplyEvent)
        fun resolveEvent(realtimeMessage: RealtimeMessage): RealtimeEvent? =
            EVENTS.firstOrNull { it.appliesTo(realtimeMessage) }
    }
}
```
consumed at `RealtimeChannelImpl.kt:126-132`:
```kotlin
val event = RealtimeEvent.resolveEvent(message)
if(event == null) { logger.e { "Received message without event: $message" }; return }
event.handle(this, message)
```
**Unknown variant → `null` → log → drop.** Not a throw. This is the mature-library answer and it maps
directly onto our "unknown payload variant from the server" problem.

### 2e. What openai-kotlin did *instead* — and where it is worse

`openai-kotlin` does not have a key registry at all. Its per-request options are a flat value class:
`openai-core/src/commonMain/kotlin/com.aallam.openai.api/core/RequestOptions.kt:12-16`
```kotlin
public data class RequestOptions(
    public val headers: Map<String, String> = emptyMap(),
    public val urlParameters: Map<String, String> = emptyMap(),
    public val timeout: Timeout? = null,
)
```
Three known options → a data class. No bag, no keys, no type token. **This is the rung-1 answer** and it is
the correct one at this size.

Where it is *worse* is honest-but-harsh unknown handling: for unknown **object** shapes it throws.
`openai-core/src/commonMain/kotlin/com.aallam.openai.api/assistant/AssistantResponseFormat.kt:95-110`:
```kotlin
"json_object" -> AssistantResponseFormat(type = "json_object")
"auto"        -> AssistantResponseFormat(type = "auto")
"text"        -> AssistantResponseFormat(type = "text")
else -> throw SerializationException("Unknown response format type: $type")
```
`ChatResponseFormat.kt:69-76` is the same. So openai-kotlin is *strict* on structured variants and
*lenient* on scalar variants. Combined with `Json { isLenient = true; ignoreUnknownKeys = true }`
(`internal/HttpClient.kt:99-102`), that is a coherent policy: **field drift is tolerated, discriminator
drift is loud.** Weigh that against 2d's drop-and-log.

There is also a **do-not-copy** in the same repo, and it proves the point about enums. Its single
server-owned enum,
`openai-core/src/commonMain/kotlin/com.aallam.openai.api/run/AssistantStreamEvent.kt:241-251`:
```kotlin
public class AssistantStreamEventTypeSerializer : KSerializer<AssistantStreamEventType> {
    override fun deserialize(decoder: Decoder): AssistantStreamEventType {
        val value = decoder.decodeString()
        return AssistantStreamEventType.entries.single { value == it.event }
    }
    ...
}
```
`entries.single { ... }` throws `NoSuchElementException` (not even a `SerializationException`) when OpenAI
adds a stream event type. A `@JvmInline value class` (`Status`, `FinishReason`) cannot have this bug; an
`enum` on a string you do not own always will.

### 2f. Typed *result* accessor over a raw payload — the best single idea in the survey

`Postgrest/src/commonMain/kotlin/io/github/jan/supabase/postgrest/result/PostgrestResult.kt:12-59`
```kotlin
class PostgrestResult(val data: String, val headers: Headers, @PublishedApi internal val postgrest: Postgrest) {
    fun countOrNull(): Long? = contentRange?.substringAfter("/")?.toLongOrNull()
    fun rangeOrNull(): LongRange? = ...

    inline fun <reified T> decodeAs(): T = postgrest.serializer.decode(data)
    inline fun <reified T> decodeAsOrNull(): T? = try { decodeAs() } catch (e: Exception) { null }
    inline fun <reified T> decodeList(): List<T> = decodeAs()
    inline fun <reified T> decodeSingle(): T = decodeList<T>().first()
    inline fun <reified T> decodeSingleOrNull(): T? = decodeList<T>().firstOrNull()
}
```
**The result keeps the raw payload, and every typed accessor has an `OrNull` twin.** The result object
never needs to know the full set of shapes the server can return. That is exactly the shape that solves
"statically typed result keyed by a name + unknown variant" without an `AttributeKey` and without a
registry. Note the `@PublishedApi internal` on the serializer — a public `inline` function cannot touch
`private`, so it is widened exactly one notch to `@PublishedApi internal`.

### Verdict for us (Q2)

- Typed value the server owns → `@JvmInline value class` + companion constants (openai-kotlin `Status`).
- Typed result *shape* the caller picks → raw payload + `as<T>()` / `asOrNull()` (supabase `PostgrestResult`).
- Unknown discriminator → `null` + log, never throw (supabase `RealtimeEvent.resolveEvent`).
- `AttributeKey<T>` only if we end up with an unbounded, cross-layer options bag. For a 15-file SDK that
  is almost certainly rung-1 "skip it".
- **Never `enum class` on a server-supplied string.** openai-kotlin has 3 `enum class`es: `LogLevel` and
  `Logger` are client-owned (correct); `AssistantStreamEventType` is **server**-owned and is the exact
  counterexample — see §2e.

---

## 3. Retry

### openai-kotlin — 12 lines, all delegated to ktor

`openai-client/src/commonMain/kotlin/com.aallam.openai.client/OpenAIConfig.kt:137-141`
```kotlin
public class RetryStrategy(
    public val maxRetries: Int = 3,
    public val base: Double = 2.0,
    public val maxDelay: Duration = 60.seconds,
)
```
`openai-client/.../internal/HttpClient.kt:68-73`
```kotlin
install(HttpRequestRetry) {
    maxRetries = config.retry.maxRetries
    // retry on rate limit error.
    retryIf { _, response -> response.status.value.let { it == 429 } }
    exponentialDelay(config.retry.base, config.retry.maxDelay.inWholeMilliseconds)
}
```
Retry is **not** hand-rolled: the policy is `retryIf` (one predicate) and the delay curve is
`exponentialDelay` from ktor. `RetryStrategy` exposes only what ktor needs.

### ktor — where the delay function lives, and how tests stay deterministic

`ktor-client/ktor-client-core/common/src/io/ktor/client/plugins/HttpRequestRetry.kt:41-42, 178-186, 220-246`
```kotlin
internal lateinit var delayMillis: HttpRetryDelayContext.(Int) -> Long
internal var delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }

public fun delayMillis(delayMillis: HttpRetryDelayContext.(Int) -> Long) { ... }

public fun exponentialDelay(respectRetryAfterHeader: Boolean = true, base: Double = 2.0, maxDelayMs: Long = 60000, randomizationMs: Long = 1000) {
    delayMillis(respectRetryAfterHeader) { retry ->
        val delay = minOf((base.pow(retry - 1) * baseDelayMs).toLong(), maxDelayMs)
        delay + randomMs(randomizationMs)
    }
}

public fun delay(block: suspend (Long) -> Unit) { delay = block }
```
The delay is a **field on the config**, with a `public fun delay(block)` setter so a test can install
`{ /* no-op */ }` and the retry loop becomes instant. Note that `delayMillis` is the *policy* (compute how
long) and `delay` is the *effect* (actually wait) — two separate seams, both overridable. Randomisation is
in the tick, bounded by `randomizationMs`.

### supabase-kt — basically no HTTP retry at all

`grep -rn "HttpRequestRetry" supabase-kt` → **zero hits**. The only retry anywhere is a hand-rolled
auth-session refresh loop with a configurable `retryDelay: Duration = 10.seconds`:
`Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/AuthConfig.kt:29-31` and
`Auth/src/commonMain/kotlin/io/github/jan/supabase/auth/AuthImpl.kt:616-636`
```kotlin
private suspend fun ...(retry: suspend () -> Unit) {
    ...
    logger.e(e) { "Couldn't refresh session due to an internal server error. Retrying in ${config.retryDelay} ..." }
    delay(config.retryDelay)     // hardcoded kotlinx delay, not injectable
    retry()
}
```
It is not injectable, and its tests are integration tests. A user-facing HTTP SDK shipping with *no*
transient-failure retry is a deliberate scope call, and it is defensible.

### aws-sdk-kotlin — a retry *framework*, with the delay provider as a strategy

`aws-runtime/aws-config/common/src/aws/sdk/kotlin/runtime/config/retries/ResolveRetryStrategy.kt:118-122`
```kotlin
delayProvider {
    initialDelay = defaultInitialDelay ?: STANDARD_INITIAL_DELAY   // 50.milliseconds
    scaleFactor  = STANDARD_SCALE_FACTOR                            // 2.0
}
tokenBucket { ... retryCost = 14; timeoutRetryCost = 5 }
maxAttempts = configuredMaxAttempts ?: defaultMaxAttempts
```
Retry mode is resolved from env vars + shared `~/.aws/config` profile (`AwsSdkSetting.AwsRetryMode`,
`resolveRetryConfig`, same file), the strategy is pluggable (`StandardRetryStrategy`,
`AdaptiveRetryStrategy`), and *what is retryable* is a separate object:
`aws-runtime/aws-http/common/src/aws/sdk/kotlin/runtime/http/retries/AwsRetryPolicy.kt:44-52`
```kotlin
public open class AwsRetryPolicy : StandardRetryPolicy() {
    override fun evaluateSpecificExceptions(ex: Throwable): RetryDirective? = when (ex) {
        is ServiceException -> evaluateServiceException(ex)
        else -> null
    }
}
```
with a hardcoded `knownErrorTypes` map of 17 throttling error codes and `knownStatusCodes = { 500,502,503,504 }`.
Retry-grade production SDKs need a *token bucket* to avoid retry storms. **We do not.**

### Verdict for us (Q3)

Openai-kotlin's 12 lines are the target: use the HTTP library's retry plugin, expose a
`RetryStrategy(maxRetries, base, maxDelay)` value class, retry only on the one status that means "try again
in a moment", and pick the delay curve from the library. Add `delay: suspend (Long) -> Unit` as a
config field if (and only if) we need deterministic unit tests of the retry loop — ktor already proves that
one lambda is the whole seam. See §7 for how the surveyed libraries test this.

---

## 4. Errors — deepest dive #2

### The choice, as actually made

| Library | `Result<T>`? | Sealed? | Exception? | Translation boundary |
|---|---|---|---|---|
| openai-kotlin | **no** (`grep "Result<" → 0`) | yes, twice | yes, rooted at `RuntimeException` | `HttpTransport.handleException` — one `when` |
| supabase-kt | **no** (`grep "Result<" → 0`) | no | yes, `open class RestException` | `SupabaseApi.rawRequest` → injected `parseErrorResponse` |
| firebase-kotlin-sdk | 7 hits: 5 × the Apple `awaitResult(...)` helper in `appleMain`, 1 × `CompletableDeferred<Result<...>>` in `jvmMain`, 1 × test. **None in `commonMain` public API** | no (`expect open class`) | yes, mirrors native SDK | inside each native wrapper |
| aws-sdk-kotlin | **no** (3 hits: 1 internal `AwsSpanInterceptor` returning `Result<Any>`, 2 in a test) | no | yes, `AwsServiceException`/`ClientException`/`ConfigurationException` | codegen-generated deserializer |

**Zero of four return `Result<T>` from public API.** In firebase-kotlin-sdk every one of the 7 `Result<`
hits is a platform-internal `awaitResult`-style helper in `appleMain`/`jvmMain` (`firebase-auth/src/appleMain/.../auth.kt:60`,
`firebase-auth/src/jvmMain/.../credentials.kt:92`), never a common API signature. In aws-sdk-kotlin the 3 hits
are 1 internal interceptor + 2 test lines. The strongest evidence is openai-kotlin:
`grep -rn "Result<" openai-client/src openai-core/src` returns nothing at all, yet the library has >150
suspending API functions. And the only place a `Result` shows up is *in a test* (`TestException.kt:17-19`):
```kotlin
val model = runCatching { openAI.model(ModelId("davinci")) }
assertTrue(model.isFailure)
val exception = model.exceptionOrNull() as OpenAIAPIException
```
i.e. even its own author treats `runCatching` as the *caller's* convenience, not the library's contract.

Reason: a suspending function already has an error channel, `kotlin.Result` is not serialisable, it does not
compose with `Flow`, and it forces every caller into `fold`/`getOrElse` ceremony. Exceptions + a sealed base
gives you `when (e) { is RateLimitException -> backoff(); is AuthenticationException -> refresh() }` with
compiler-checked exhaustiveness.

### openai-kotlin's hierarchy — the template to copy

`openai-core/src/commonMain/kotlin/com.aallam.openai.api/exception/OpenAIException.kt`
```kotlin
public sealed class OpenAIException(message: String? = null, throwable: Throwable? = null)
    : RuntimeException(message, throwable)

/** Runtime Http Client exception */
public class OpenAIHttpException(throwable: Throwable? = null) : OpenAIException(throwable?.message, throwable)

/** An exception thrown in case of a server error */
public class OpenAIServerException(throwable: Throwable? = null) : OpenAIException(message = throwable?.message, throwable = throwable)
```

`OpenAIAPIException.kt:4-9` — the parts worth stealing:
```kotlin
public sealed class OpenAIAPIException(
    public val statusCode: Int,
    public val error: OpenAIError,
    throwable: Throwable? = null,
) : OpenAIException(message = error.detail?.message, throwable = throwable)
```
**The parsed error body is a typed property on the exception**, and `message` is derived from it. Callers
never re-parse a body. Five concrete subclasses follow: `RateLimitException`, `InvalidRequestException`,
`AuthenticationException`, `PermissionException`, `UnknownAPIException` — each with a doc comment saying what
it means.

`OpenAIIOException.kt` — the *transport* subtree, kept separate from the *API* subtree:
```kotlin
public sealed class OpenAIIOException(throwable: Throwable? = null) : OpenAIException(message = throwable?.message, throwable = throwable)
public class OpenAITimeoutException(throwable: Throwable) : OpenAIIOException(throwable = throwable)
public class GenericIOException(throwable: Throwable? = null) : OpenAIIOException(throwable = throwable)
```
That two-branch split (`API errors` vs `IO errors`) is the single most useful structural decision here: it
lets a caller write `catch (e: OpenAIIOException) { retry }` and `catch (e: OpenAIAPIException) { report }`
without knowing any status codes, while still allowing status-code branching underneath.

### The single translation site

`openai-client/src/commonMain/kotlin/com.aallam.openai.client.internal.http/HttpTransport.kt:70-93`
```kotlin
private suspend fun handleException(e: Throwable) = when (e) {
    is CancellationException -> e // propagate coroutine cancellation
    is ClientRequestException -> openAIAPIException(e)
    is ServerResponseException -> OpenAIServerException(e)
    is HttpRequestTimeoutException, is SocketTimeoutException, is ConnectTimeoutException -> OpenAITimeoutException(e)
    is IOException -> GenericIOException(e)
    else -> OpenAIHttpException(e)
}

private suspend fun openAIAPIException(exception: ClientRequestException): OpenAIAPIException {
    val response = exception.response
    val status = response.status.value
    val error = response.body<OpenAIError>()
    return when(status) {
        429 -> RateLimitException(status, error, exception)
        400, 404, 409, 415 -> InvalidRequestException(status, error, exception)
        401 -> AuthenticationException(status, error, exception)
        403 -> PermissionException(status, error, exception)
        else -> UnknownAPIException(status, error, exception)
    }
}
```
Three things to note:

1. **`is CancellationException -> e` is first.** Structuring coroutine cancellation as a plain `IOException`
   or wrapping it in your own type is a real bug class. Every mature library here gets this right;
   supabase does it too (`KtorSupabaseHttpClient.kt:79-81`: `catch(e: CancellationException) { ...; throw e }`).
2. **`throw handleException(e)`** — the wrap sites are 3 lines each, in a 3-method class. There is exactly
   **one** boundary. No `try/catch` anywhere in the 19 `*Api` implementation files.
3. **Every transport exception type is enumerated**, including the `else ->` fallback. `Throwable` never
   escapes raw.

### supabase-kt's version — `open class` + subclass-per-status, single `parseErrorResponse` seam

`Supabase/src/commonMain/kotlin/io/github/jan/supabase/exceptions/RestException.kt:16-46`
```kotlin
open class RestException(val error: String, val description: String?, val response: HttpResponse):
    Exception(error + (description?.let { "\n$it" } ?: "") +
        "\nURL: ${maskUrl(response.request.url)}" + "\nHeaders: ${maskHeaders(response.request.headers)}" +
        "\nHttp Method: ${response.request.method.value}") {
    val statusCode = response.status.value
}
class UnauthorizedRestException(error: String, response: HttpResponse, message: String? = null): RestException(...)
class BadRequestRestException(...)
class NotFoundRestException(...)
class UnknownRestException(...)   // "Thrown for all other response codes"
```
and the transport-vs-protocol split, `Supabase/src/commonMain/kotlin/io/github/jan/supabase/exceptions/HttpRequestException.kt`:
```kotlin
class HttpRequestException(message: String, request: HttpRequestBuilder):
    IOException("HTTP request to ${request.url.buildString()} (${request.method.value}) failed with message: $message")
```
Same two-branch structure as openai-kotlin, done with `IOException` as the transport base.

The boundary is an **injected lambda**, `Supabase/src/commonMain/kotlin/io/github/jan/supabase/network/SupabaseApi.kt:16,28-32`:
```kotlin
open class SupabaseApi @SupabaseInternal constructor(
    val resolveUrl: (path: String) -> String,
    val parseErrorResponse: (suspend (response: HttpResponse) -> RestException)? = null,
    val httpClient: SupabaseHttpClient
) : SupabaseHttpClient() {
    open suspend fun rawRequest(url: String, builder: HttpRequestBuilder.() -> Unit): HttpResponse {
        return httpClient.request(url, builder).also {
            if(!it.status.isSuccess() && parseErrorResponse != null) throw parseErrorResponse.invoke(it)
        }
    }
}
```
Note the deliberate `? = null` on `parseErrorResponse`: a plugin that does not install a parser gets raw
responses. Note also the clever string masking (`maskUrl`, `maskHeaders`) in the *exception message* — an
exception is the single most-likely-to-be-logged object in an SDK, so put the API key out of reach there.
That detail is worth copying.

One downside: `open class RestException` (+ per-plugin `parseErrorResponse`) is genuinely extensible, which
is why supabase can afford one exception family across 6 independently-published plugins. It also means no
exhaustive `when` anywhere. For a single library, `sealed` is strictly better.

firebase-kotlin-sdk takes the third road — mirror the wrapped SDK's hierarchy verbatim
(`firebase-auth/.../auth.kt:89-101`, 11 `expect open class` subclasses of `FirebaseAuthException`) — plus a
platform error *code* string. That is wrapper-tax, not a pattern; we have no native SDK underneath.

### Verdict for us (Q4)

Sealed base exception, two branches (`…Exception` for API-level, `…IOException` for transport-level),
typed parsed error body as a property, one `when`-based translation function at the transport boundary,
`CancellationException` rethrown first. No `Result<T>` in public API.

---

## 5. Public surface discipline — this determines our CI gate

### Who gates what

| | `explicitApi()` | BCV plugin | api dump committed | `apiCheck` in CI |
|---|---|---|---|---|
| openai-kotlin | ✅ `openai-client/build.gradle.kts:17`, `openai-core/build.gradle.kts:12` | ✅ `openai-client/build.gradle.kts:10`, `openai-core/build.gradle.kts:5` | ✅ `openai-client/api/openai-client.api` (335 lines), `openai-core/api/openai-core.api` (7573 lines) | ✅ `.github/workflows/build.yml:65-66` |
| firebase-kotlin-sdk | ✅ `firebase-app/build.gradle.kts:54` | ✅ `build.gradle.kts:20`, `gradle/libs.versions.toml:15` | ✅ **per target**: `firebase-*/api/jvm/*.api` + `firebase-*/api/android/*.api` | not wired explicitly (BCV's `apiCheck` is part of `check`) |
| aws-sdk-kotlin | ✅ (generated code) | ✅ + `apiValidation {}` | ✅ per module | ✅ `.github/workflows/continuous-integration.yml:142` `./gradlew apiCheck` |
| ktor | ✅ | ✅ | ✅ **both** `ktor-client-core/api/ktor-client-core.api` (JVM, 1733 lines) and `ktor-client-core/api/ktor-client-core.klib.api` (KMP ABI, 2809 lines) | ✅ |
| **supabase-kt** | ❌ **none** | ❌ **none** | ❌ **none** | ❌ |

supabase-kt is the widest-adopted KMP client SDK in this survey and it ships **no binary-compatibility gate
at all**. It has `detekt` (`detekt.yml`, `.github/workflows/detekt.yml`), a per-module `commonTest`, a
separate `integration-test` module, and — notably — a machine-readable **feature-compliance manifest**,
`sdk-compliance.yaml`:
```yaml
sdk: kotlin
features:
  auth.sign_in.sign_up:
    status: implemented
    symbols:
      - Auth.signUpWith
  auth.sign_in.sign_in_with_web3: not_implemented
```
That is a *capability* checklist, not an ABI checklist, and it exists because the Supabase org owns a
cross-language spec registry. It is not the pattern for us.

### What the api dump actually looks like in practice — two real gotchas from the dumps

**(a) Value-class parameters produce mangled names.** From `openai-client/api/openai-client.api:3`:
```
public abstract fun assistant-7pl7fn0 (Ljava/lang/String;Lcom/aallam/openai/api/core/RequestOptions;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
public abstract fun assistants-7yDA0xE (Ljava/lang/Integer;Ljava/lang/String;...)
public abstract fun delete-l7QrTQ8 (...)
```
Every function whose signature contains a `@JvmInline value class` gets a hash suffix. That is correct and
harmless — the hash is derived from the value class's underlying type and does not change when you rename
things — but it makes dumps unreadable and it means **adding a value-class wrapper to an existing parameter
is a binary-incompatible change** even though it looks source-compatible. Worth knowing before we sprinkle
`@JvmInline` on the public API.

**(b) A dump is a snapshot; it can entrench accidental surface.**
`firebase-firestore/api/jvm/firebase-firestore.api:27`:
```
public synthetic fun getNative$firebase_firestore ()Lcom/google/firebase/firestore/Query;
```
That is Kotlin's internal-name mangling (`$moduleName` suffix) for an `internal` member on a public class,
committed into the checked-in dump. Even in a repo with `explicitApi()` + BCV the dump is not a guarantee of
*intent*, only of *drift*. Which means: run `apiDump`, then **read the diff** before committing it. The
gate catches accidental changes; it cannot catch deliberate bad decisions.

### Opt-in markers

| Library | Markers | Level |
|---|---|---|
| openai-kotlin | `@BetaOpenAI`, `@ExperimentalOpenAI`, `@InternalOpenAI`, `@LegacyOpenAI` | `InternalOpenAI` is `Level.ERROR` |
| supabase-kt | `@SupabaseInternal`, `@SupabaseExperimental`, `@SupabaseDsl` | first two `Level.ERROR`; `@SupabaseDsl` is a `@DslMarker` |
| aws-sdk-kotlin | `@InternalSdkApi`, `@GeneratedApi` (→ `nonPublicMarkers`) | `Level.ERROR` |
| firebase | none | — |

The canonical text, `aws-runtime/aws-core/common/src/aws/sdk/kotlin/runtime/Annotations.kt:13-29`:
```kotlin
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is internal to aws-runtime and generated SDKs and should not be used. It could be removed or changed without notice.",
)
public annotation class InternalSdkApi
```
and openai-kotlin's equivalent, `InternalOpenAI.kt`:
```kotlin
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This API is internal in OpenAI API client and should not be used. It could be removed or changed without notice."
)
public annotation class InternalOpenAI
```
Both use `Level.ERROR`, never `WARNING`. Both, in the build file, then globally opt in for their own
source sets so the annotations do not cause friction internally — openai-kotlin
`openai-client/build.gradle.kts:22-28`:
```kotlin
all {
    languageSettings {
        optIn("com.aallam.openai.api.ExperimentalOpenAI")
        optIn("com.aallam.openai.api.BetaOpenAI")
        optIn("com.aallam.openai.api.InternalOpenAI")
        optIn("com.aallam.openai.api.LegacyOpenAI")
    }
}
```
Note the two-marker split in openai-kotlin: `@InternalOpenAI` (never call this) vs `@BetaOpenAI`
(the *server* calls it beta, so it may change). They are different promises and both are worth having.

### Verdict for us (Q5)

`explicitApi()` + a single `@InternalJev` `@RequiresOptIn(Level.ERROR)` marker + BCV with one committed
`core/api/jev-kmp.api`, and `./gradlew apiCheck` in CI. That is the exact configuration of the closest
analog (openai-kotlin) and it is a ~10-line build-file change. Do **not** add per-target dump dirs or
`.klib.api` (ktor needs them because it publishes exotic targets); add them only when we actually publish
more than one target that matters. And read the dump diff on every PR.

---

## 6. Module layout

| Library | Published modules | Split rationale | Test layout |
|---|---|---|---|
| openai-kotlin | `openai-core` (pure models/serialization, no HTTP), `openai-client` (transport + `HttpClient`/ktor deps), `openai-client-bom` | **api vs client** — the models module has zero ktor dependency | bulk in `src/commonTest`; `jvmTest` adds `ktor-client-okhttp` + logback; `jsTest` adds `ktor-client-js`; `darwinTest`/`desktopTest` add native engines |
| supabase-kt | 6 feature modules + `bom` + `serializer-moshi`/`serializer-jackson` + `test-common` + `integration-test` | one module per *feature*, not per layer | `src/commonTest` per module; separate `integration-test` module for live calls |
| firebase-kotlin-sdk | 15 modules, one per Firebase product + `firebase-common`, `firebase-common-internal`, `test-utils` | one module per *native SDK wrapper* | `src/commonTest` + `jvmTest`/`jsTest`/`androidUnitTest`/`androidInstrumentedTest`/`appleTest` |
| aws-sdk-kotlin | `aws-runtime/{aws-core,aws-config,aws-http,aws-endpoint}` + one module **per AWS service** (hundreds) + `codegen/*` + `tests/*` | codegen fan-out | `common/test`, `jvm/test`; e2e tests as a separate `e2eTest` source set per service |

Observations:

- The **universal** test home is `src/commonTest`, and it holds the bulk. Platform test source sets exist
  only to add a *platform engine dependency*, not to duplicate assertions. openai-kotlin's `commonTest` has
  ~30 files; its platform test source sets have engine deps and almost nothing else.
- openai-kotlin's `core`/`client` split is the **only** one applicable to us: pure data + serializers in one
  module (no HTTP dependency leaks to consumers), transport in the other. It cost that project exactly two
  `build.gradle.kts` files and it is the reason `openai-core`'s api dump is 7573 lines of models with no
  ktor anywhere in it.
- aws's "one module per service" and firebase's "one module per product" are both **forced by**
  codegen/wrapper-per-native-SDK. They are not design choices to emulate.
- supabase/firebase both keep `test-utils`/`test-common` as a module so multiple modules can share fakes.
  **One module, no sharing → no `test-common` module.** YAGNI.
- Everything is published with `com.vanniktech.maven.publish` + `dokka`. Both are one-line plugin
  applications; not worth debating.

### Verdict for us (Q6)

For a ~15-file SDK: **one module**, or at most `core` (models) + `client` (transport) if the models are
numerous enough that not leaking the HTTP dependency matters. Tests in `commonTest`, with the platform
engine dependency in the per-platform test source set. No `test-common`, no `bom`, no samples module
(nested `sample/` projects, if ever, are fine).

---

## 7. What the tests actually do (and what they don't)

This is where the survey was most surprising.

- **openai-kotlin has no `MockEngine` anywhere.** `grep -rln "MockEngine" openai-client/src` → nothing.
  Its `commonTest` is almost entirely *live API* tests, and they are gated off by default:
  `openai-client/build.gradle.kts:124-127, 135-137`
  ```kotlin
  val liveTestsEnabled = providers.environmentVariable("OPENAI_LIVE_TESTS").map { it == "1" }.orElse(false)
  tasks.withType<KotlinJvmTest>().configureEach {
      onlyIf("Live API tests are disabled. Set OPENAI_LIVE_TESTS=1 to enable.") { liveTestsEnabled.get() }
  }
  ```
  (`openai-client/build.gradle.kts:98-111`; the same `onlyIf` is repeated for `KotlinJsTest` and
  `KotlinNativeTest`.)
  What runs by default: pure-logic tests only. `TestChatChunk`, `TestCosine`, `TestConfigure`. This is a
  pragmatic and slightly uncomfortable choice: it means CI verifies serialization and helpers, not transport.
- **supabase-kt does use `MockEngine`** — but only in `commonTest` for the exception/masking logic:
  `Supabase/src/commonTest/kotlin/RestExceptionTest.kt:5-8, 26-30`
  ```kotlin
  import io.ktor.client.engine.mock.MockEngine
  import io.ktor.client.engine.mock.respond
  ...
  private val clients = mutableListOf<HttpClient>()
  @Test fun testMessageContainsRequestInformation() = runTest { ... mockResponse(httpMethod = HttpMethod.Post) }
  ```
  Live tests live in the separate `integration-test` module.
- **ktor's own answer for determinism** is `MockEngine` (`ktor-client/ktor-client-mock/common/src/.../MockEngineConfig.kt`:
  `requestHandlers: MutableList<MockRequestHandler>`, `reuseHandlers`), used as a normal engine:
  `HttpClient(MockEngine) { engine { addHandler { respond(...) } } }`. Plus the retry delay seam from §3.

**For us this means**: `ktor-client-mock` in `commonTest` is cheap and gives us (a) the error-translation
`when` under test, (b) the "unknown discriminator → null/log" path under test, (c) deterministic retry if we
add it. That's 3-5 tests, no framework, no fixtures. Do it. Do **not** copy openai-kotlin's "everything is a
live test gated by an env var" — we are not large enough to afford an untested transport.

---

## Patterns we adopt (tied to file:line)

1. **`interface X : FeatureA, FeatureB, AutoCloseable` + `internal class XImpl` + `fun X(config)` factory.**
   → `openai-client/.../OpenAI.kt:13-62`, `internal/OpenAIApi.kt:13-33`.
   Concrete class never appears in any api dump in the survey.
2. **Feature interfaces composed by delegation, not inheritance.** `... : OpenAI, Completions by CompletionsApi(requester), ...`
   → `internal/OpenAIApi.kt`. Gives fake-ability with zero ceremony.
3. **One config class with defaulted params; a second factory overload taking it.** No builder for us.
   → `OpenAIConfig.kt:13-38`, dual factory `OpenAI.kt:30` + `OpenAI.kt:59`.
4. **Nullable engine *instance* for injection + a `HttpClientConfig<*>.() -> Unit` escape hatch.**
   → `OpenAIConfig.kt:35,37`; install path `internal/HttpClient.kt:89-91`.
5. **Do not close a dependency instance you were handed** — delegate `AutoCloseable` to the transport and
   rely on the HTTP library's own ownership tracking. → `OpenAIApi.kt:33` + `HttpTransport.kt:62-64`;
   Ktor's formal statement of the rule at `HttpClient.kt:646-653 / 962-965 / 1460-1471`.
6. **`suspend fun close()` is not needed** — `AutoCloseable` is enough for us. (supabase needs `suspend`
   only because it closes plugins; `SupabaseClient.kt:78`.)
7. **Sealed base exception in two branches: SDK/API errors vs IO/transport errors.** Enables
   `catch (e: XIOException) { retry }` without status-code knowledge.
   → `OpenAIException.kt`, `OpenAIIOException.kt`, `OpenAIAPIException.kt`.
8. **Parsed error body as a typed property on the exception, `statusCode: Int` too, `message` derived from it.**
   → `OpenAIAPIException.kt:4-9`. Callers never re-parse a body.
9. **One `when`-based translation function at the transport boundary; `CancellationException` rethrown first;
   every transport exception enumerated.** → `HttpTransport.kt:70-93`; supabase's `CancellationException`
   handling `KtorSupabaseHttpClient.kt:79-81`.
10. **Redact credentials inside the exception message.** → `RestException.kt:16-30` (`maskUrl`, `maskHeaders`).
11. **`@JvmInline value class` + companion constants for any string the server owns.** Unknown values pass
    through instead of breaking deserialization. → `openai-core/.../core/Status.kt`, `FinishReason.kt`.
    45 instances vs 3 `enum class`es, one of which is the counterexample in §2e
    (`AssistantStreamEvent.kt:246`).
12. **Typed accessor over a raw payload, with every accessor getting an `OrNull` twin.**
    → `PostgrestResult.kt:12-59`. This is our answer to "statically typed result keyed by a name".
13. **Unknown discriminator from the server → `null` → log → drop.** Never throw.
    → `RealtimeEvent.kt:44-48` + `RealtimeChannelImpl.kt:126-132`.
14. **Tolerate unknown *fields* globally** (`Json { isLenient = true; ignoreUnknownKeys = true }`).
    → `internal/HttpClient.kt:99-102`.
15. **Capability interfaces on a sealed result so `when` gives you exactly the fields that exist.**
    → `PostgresAction.kt:22-44` (`HasRecord`, `HasOldRecord`).
16. **Use the HTTP library's retry plugin; expose only `maxRetries`/`base`/`maxDelay`; retry on one status only.**
    → `OpenAIConfig.kt:137-141` + `internal/HttpClient.kt:68-73` (12 lines total).
17. **`explicitApi()` + BCV + committed `apiCheck` in CI.** → `openai-client/build.gradle.kts:10,17`,
    `openai-core/build.gradle.kts:5,12`, `.github/workflows/build.yml:65-66`.
18. **One or two `@RequiresOptIn(Level.ERROR)` markers; opt the library's own source sets in globally.**
    → `InternalOpenAI.kt`, `Annotations.kt:17-29`, `openai-client/build.gradle.kts:26-31`.
19. **`@PublishedApi internal` to widen a member exactly one notch for a public `inline`.** Two independent
    sightings: `PostgrestResult.kt:12` (serializer), `SupabaseClientBuilder.kt:29,107` (builder ctor).
20. **`ktor-client-mock` `MockEngine` in `commonTest`** for the error-translation `when` and the
    unknown-variant path. → `MockEngineConfig.kt`; used at `supabase-kt/Supabase/src/commonTest/kotlin/RestExceptionTest.kt`.
21. **Grep-check before widening the public surface: none of the four product SDKs exposes a concrete client class.**

---

## Patterns we reject (and why, per library + file)

1. **DSL builder for construction** — `supabase-kt`'s `@SupabaseDsl class SupabaseClientBuilder` with ~20
   `var`s (`SupabaseClientBuilder.kt:26-120`) + a `@DslMarker` annotation
   (`annotations/SupabaseDsl.kt`). It buys `install(Postgrest)` composition across 6 independently published
   plugins. We have one client and a fixed config. **Use a config class.**
2. **Plugin registry / `PluginManager` keyed by `String`** — `plugins/PluginManager.kt`,
   `plugins/SupabasePluginProvider.kt:14`. Reject *for us* (it is right for supabase). A 15-file SDK with a
   static feature set does not need a runtime registry.
3. **`AttributeKey<T>` + `Attributes` bag** — `ktor-utils/.../Attributes.kt:31`, used by
   `aws-runtime/aws-core/.../AwsClientOption.kt:21`. Ktor/aws need it because options must cross layers
   they don't own. We own all the layers; three named options is a `data class` — which is exactly what
   openai-kotlin chose (`RequestOptions.kt:12-16`). Revisit only if the options count becomes unbounded.
4. **`Result<T>` from public API.** Nobody does it (§4 table). Take the exception.
5. **`open class` exception hierarchy** — `RestException.kt`. It exists so independent modules can extend
   the family. One library → `sealed`, which also gives exhaustive `when`.
6. **Exception subclass per wrapped-native-SDK error** — `firebase-auth/.../auth.kt:89-101`, 11
   `expect open class`es. That is wrapper tax; we have no native SDK.
7. **Per-target api dumps (`api/jvm/`, `api/android/`) and `.klib.api`.** `firebase-*/api/*`,
   `ktor-client/ktor-client-core/api/*.klib.api` (2809 lines). Add when we publish targets that need it.
8. **`apiValidation { ignoredPackages += ... }` escape hatches** — `aws-runtime/build.gradle.kts:100-102`.
   If we need `ignoredPackages`, we have a layout problem.
9. **`retry` *framework*** — pluggable `RetryStrategy`, `StandardRetryStrategy`, `AdaptiveRetryStrategy`,
   17-entry `knownErrorTypes` throttle table, `tokenBucket { retryCost = 14 }`, env-var + profile resolution
   (`AwsRetryPolicy.kt:44-84`, `ResolveRetryStrategy.kt:100-140`). Correct for AWS. Absurd for us.
10. **"Everything is a live test behind an env var"** — `openai-client/build.gradle.kts:98-111`. Copy the
    *env-gating syntax* for any live tests we add; do not copy the coverage strategy.
11. **Codegen pipeline / one module per feature-or-service.** `aws-sdk-kotlin` (`codegen/*`,
    `services/*`) and `firebase-kotlin-sdk` (15 modules). Both are forced by their problem, not chosen.
12. **`suspend fun close()`** — `SupabaseClient.kt:78`. Only forced by suspend plugin teardown.
13. **A `sdk-compliance.yaml`-style capability manifest** — `supabase-kt/sdk-compliance.yaml`. Exists
    because the Supabase org owns a cross-language registry.
14. **Hand-rolled retry with a hardcoded `delay(...)`** — `AuthImpl.kt:616-636`. If we retry, the delay must
    be a field (ktor's `delay` at `HttpRequestRetry.kt:244` shows the 1-line version of this).

---

## Explicit over-engineering call-outs for a ~15-file SDK

Ranked by "most tempting, most damaging":

1. **A plugin registry.** Supabase's `PluginManager` is ~35 lines and it is load-bearing for them
   (`plugins/PluginManager.kt`). For us it is a `Map<String, Any>` with casts and a class of runtime
   errors that the compiler would otherwise catch. **Skip.**
2. **An `Attributes`/`AttributeKey` bag.** Two public generic types plus a type token plus a
   name-collision convention (`ktor-utils/.../Attributes.kt:31`) to hold three values. **Skip** — use
   `data class RequestOptions`.
3. **A retry framework.** `RetryStrategy` + `RetryPolicy` + `DelayProvider` + token bucket. The whole
   thing is 12 lines if you use the HTTP library's plugin (`internal/HttpClient.kt:68-73`). **Skip the
   framework, take the plugin.**
4. **`core`/`client` module split.** openai-kotlin gets real value (a 7573-line api dump with zero ktor
   dependency) but it costs a second `build.gradle.kts`, a second api dump, a BOM, and a second publish
   coordinate. At 15 files, one module. Revisit at ~40+ files or when a downstream consumer complains that
   the models drag in an HTTP stack.
5. **A `-bom` module.** `openai-client-bom/build.gradle.kts`, `supabase-kt/bom`. It exists to align versions
   across ≥5 published coordinates. One coordinate needs no BOM.
6. **A `test-common` module.** `supabase-kt/test-common`, `firebase-kotlin-sdk/test-utils`. Nothing to share
   when there is one module.
7. **Four opt-in markers.** openai-kotlin needs `Beta/Experimental/Internal/Legacy` because it tracks a
   moving external API. We need **one**: `@InternalJev`. (Add a `@BetaJev` only if our upstream server marks
   endpoints beta.) Note the build-file cost: each marker must be opted into in `languageSettings`
   (`openai-client/build.gradle.kts:26-31`) or internal use becomes noisy.
8. **`commonMain` with 4+ targets wired up front** — `openai-client/build.gradle.kts:18-21` declares
   `jvm()`, `jsNode()`, `jsWasm()`, `native()` and then carries `jsTest`/`wasmJsTest`/`nativeTest`/
   `desktopTest`/`darwinTest` source sets for engine deps. Ship the targets we can actually test.
9. **`apiValidation { ignoredPackages }`** — an escape hatch once you have one, you will keep using it
   (`aws-runtime/build.gradle.kts:100`).
10. **A `RequestOptions` with a `timeout: Timeout` per-request override** — `RequestOptions.kt:12-16`.
    Copy the class shape; add the per-request timeout override only when a caller needs it.

**The minimal shape I would actually build, straight from the survey:**

```
one module
  X.kt              : public interface X : FeatureA, FeatureB, AutoCloseable   (like OpenAI.kt:13)
  XConfig.kt        : class XConfig(..., engine: HttpClientEngine? = null, httpClientConfig: ... = {})
  internal/XImpl.kt : internal class XImpl(...) : X, FeatureA by FeatureAApi(requester), AutoCloseable by requester
  internal/HttpTransport.kt : the ONE error-translation when, CancellationException first
  api/XException.kt / XAPIException.kt / XIOException.kt  (sealed, two branches)
  api/Status.kt     : @JvmInline value class + companion constants (no enum for server strings)
  api/Result.kt     : raw payload + as<T>() / asOrNull()
+ explicitApi(), @InternalJev, BCV, apiCheck in CI
+ commonTest with ktor-client-mock MockEngine
```

---

## Residual risk / things not established by this survey

- **aws-sdk-kotlin's actual retry *implementation*** (`StandardRetryStrategy`, `ExponentialBackoffWithJitter`,
  the `delayProvider` interface, `AttributeKey` itself) lives in the external `smithy-kotlin` dependency, not
  in the cloned repo. What is cited here is its *usage* in `aws-runtime/*` (`AwsRetryPolicy.kt`,
  `ResolveRetryStrategy.kt`). The shape is unambiguous from the call sites; the internals were not read.
- **`RetryStrategy` deterministic tests in openai-kotlin/supabase**: neither library tests its retry path
  in `commonTest`, so no file in the clones demonstrates an injected-delay test. The mechanism cited
  (`HttpRequestRetryConfig.delay`, ktor `HttpRequestRetry.kt:244`) is read from source but is exercised by
  ktor's own tests, which were not in the sparse checkout.
- **`ktor` was cloned with a sparse checkout** of `ktor-client/ktor-client-core`, `ktor-client/ktor-client-cio`,
  `ktor-client/ktor-client-mock`, `ktor-utils`. Engine-specific files (okhttp/darwin) were not read.
- **`kubernetes-client/kotlin` and `stripe-android` were not surveyed.** The first failed to clone (repo
  no longer public at that path); the second was out of scope for this run. Five libraries (the four named
  priority repos + ktor) exceed the ticket's "at least four" bar, and none of the uncovered ones are likely
  to change the verdict — `stripe-android` is JVM/Android-only, so its patterns are less applicable to KMP
  than the four that were read.
- **`openai-kotlin` HEAD is 2026-02-07** (7 months older than the others in the clone set). Its patterns are
  stable enough to be worth copying regardless, but it will not show the newest Kotlin 2.x idioms.
