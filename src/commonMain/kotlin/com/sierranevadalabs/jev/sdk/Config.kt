package com.sierranevadalabs.jev.sdk

import com.sierranevadalabs.jev.sdk.errors.JevError
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The environment variables the config reads, matching both official SDKs. */
internal const val API_KEY_ENV = "TYPESAFE_API_KEY"
internal const val BASE_URL_ENV = "TYPESAFE_BASE_URL"
internal const val DEFAULT_MODEL_ENV = "TYPESAFE_DEFAULT_MODEL"

internal const val DEFAULT_BASE_URL = "https://api.typesafe.ai"
internal const val DEFAULT_MODEL = "jev-latest"
internal val DEFAULT_TIMEOUT: Duration = 10.seconds

/**
 * Everything a caller may configure. A plain class, deliberately **not** a `data class`: a generated
 * `toString()` would print [apiKey] verbatim, so the explicit [toString] below omits it — and
 * [defaultHeaders], which can carry credentials too.
 *
 * Every value is resolved when the client is built ([TypeSafeClient]), so leaving a field `null` means "read
 * the environment, then use the SDK's default". Precedence is always explicit → environment → SDK default:
 * [apiKey] reads `TYPESAFE_API_KEY` and has no default, [baseUrl] reads `TYPESAFE_BASE_URL` before
 * `https://api.typesafe.ai`, [defaultModel] reads `TYPESAFE_DEFAULT_MODEL` before `jev-latest`, and [timeout]
 * has no environment variable and defaults to 10 seconds. A blank or whitespace-only environment value counts
 * as unset. The environment is read once, at construction; changing a variable later does not affect a client
 * that already exists.
 *
 * @property apiKey the API key. `null` falls back to `TYPESAFE_API_KEY`; there is no default.
 * @property baseUrl the API root. `null` falls back to `TYPESAFE_BASE_URL`, then `https://api.typesafe.ai`.
 * @property defaultModel the model a call uses when it names none. `null` falls back to
 *   `TYPESAFE_DEFAULT_MODEL`, then `jev-latest`.
 * @property timeout the per-attempt request timeout. `null` means 10 seconds.
 * @property defaultHeaders headers added to every request, before the caller's per-call headers and before
 *   the headers the SDK owns.
 * @property retry the default retry policy. A call may override it.
 * @property engine the HTTP engine to use. `null` creates the platform default (OkHttp on JVM and Android,
 *   Darwin on Apple, CIO on Linux), which the client then owns and closes.
 * @property httpClientConfig extra Ktor client configuration. Runs before the SDK installs its own plugins,
 *   so `Logging` or custom auth can be added but the retry and timeout ordering cannot be broken.
 */
public class TypeSafeConfig(
    public val apiKey: String? = null,
    public val baseUrl: String? = null,
    public val defaultModel: String? = null,
    public val timeout: Duration? = null,
    public val defaultHeaders: Map<String, String> = emptyMap(),
    public val retry: RetryPolicy = RetryPolicy(),
    public val engine: HttpClientEngine? = null,
    public val httpClientConfig: HttpClientConfig<*>.() -> Unit = {},
) {
    /** The config as text. [apiKey] is deliberately absent, so a config cannot leak the key into a log. */
    override fun toString(): String = "TypeSafeConfig(baseUrl=$baseUrl, defaultModel=$defaultModel, timeout=$timeout, retry=$retry)"
}

/**
 * [TypeSafeConfig] with every value resolved. Internal, so the resolution rules are testable through an
 * injected environment without the public surface growing a seam for it.
 */
internal class ResolvedConfig(
    config: TypeSafeConfig,
    env: (String) -> String?,
) {
    val apiKey: String =
        resolveSetting(config.apiKey, API_KEY_ENV, env)?.takeIf { it.isNotBlank() }
            ?: throw JevError("No API key. Pass TypeSafeConfig(apiKey = ...) or set $API_KEY_ENV.")
    val baseUrl: String = resolveSetting(config.baseUrl, BASE_URL_ENV, DEFAULT_BASE_URL, env)
    val defaultModel: String = resolveSetting(config.defaultModel, DEFAULT_MODEL_ENV, DEFAULT_MODEL, env)
    val timeout: Duration = config.timeout ?: DEFAULT_TIMEOUT

    init {
        require(timeout > Duration.ZERO) { "timeout must be positive, was $timeout" }
    }
}

/** `explicit → env → default`, with a blank or whitespace-only env value treated as unset. */
internal fun resolveSetting(
    explicit: String?,
    envName: String,
    default: String,
    env: (String) -> String?,
): String = explicit ?: env(envName)?.trim()?.takeIf { it.isNotEmpty() } ?: default

/** The same rule with no default: `null` when neither the explicit value nor the environment provides one. */
internal fun resolveSetting(
    explicit: String?,
    envName: String,
    env: (String) -> String?,
): String? = explicit ?: env(envName)?.trim()?.takeIf { it.isNotEmpty() }
