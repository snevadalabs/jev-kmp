package com.sierranevadalabs.jev.sdk

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestRetryEvent
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.timeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.utils.unwrapCancellationException
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The one place an HTTP call leaves this SDK. Everything above it sees [TransportResponse] or
 * [TransportException] and never a Ktor type, a status-to-error mapping, or an `IOException`.
 */
internal class Transport(
    private val http: HttpClient,
    private val engine: HttpClientEngine,
    private val ownsEngine: Boolean,
    private val apiKey: String,
    private val baseUrl: String,
    private val defaultHeaders: Map<String, String>,
    private val retryPolicy: RetryPolicy,
    private val random: () -> Double,
) : AutoCloseable {
    /**
     * Sends one request. [body] must be a `String`, because Ktor copies the body object by reference on each
     * retry: a streamed body would be consumed by the first attempt and fail the second.
     *
     * [policy] replaces the client's retry policy for this call only, [timeout] its request timeout.
     */
    suspend fun request(
        method: HttpMethod,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeout: Duration? = null,
        policy: RetryPolicy? = null,
    ): TransportResponse {
        val effectivePolicy = policy ?: retryPolicy
        val response =
            try {
                http.request(joinUrl(baseUrl, path)) {
                    this.method = method
                    assembleHeaders(defaultHeaders, headers, apiKey, hasBody = body != null).forEach { (name, value) ->
                        this.headers[name] = value
                    }
                    if (body != null) setBody(TextContent(body, ContentType.Application.Json))
                    // Only the request timeout is per-call: one timeout capability per client keeps OkHttp from
                    // rebuilding its dispatcher, and socket/connect timeouts stay pinned at the client.
                    if (timeout != null) this.timeout { requestTimeoutMillis = timeout.inWholeMilliseconds }
                    retry { applyPolicy(effectivePolicy, random) }
                }
            } catch (cause: Throwable) {
                throw cause.asTransportFailure()
            }

        return try {
            response.toTransportResponse(effectivePolicy)
        } catch (cause: Throwable) {
            throw cause.asTransportFailure()
        }
    }

    override fun close() {
        http.close()
        if (ownsEngine) engine.close()
    }
}

/** What the layers above see: a status, the raw body, and the two headers the SDK contract surfaces. */
internal class TransportResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
    val requestId: String?,
    val retryAfterMs: Long?,
)

/**
 * A Ktor or engine failure, translated at the single transport boundary. `expectSuccess` is off, so a non-2xx
 * response is data ([TransportResponse]) rather than an exception; these two are the case where no response exists.
 */
internal sealed class TransportException(
    message: String,
    cause: Throwable,
) : Exception(message, cause) {
    class Timeout(
        cause: Throwable,
    ) : TransportException("The request timed out", cause)

    class Connection(
        cause: Throwable,
    ) : TransportException("The request failed before a response arrived", cause)
}

/**
 * Builds the transport, and with it the only [HttpClient] in the module.
 *
 * The caller's [httpClientConfig] runs first, so a caller can add `Logging` or a custom auth plugin but cannot
 * break the order [HttpRequestRetry] and [HttpTimeout] require. When [engine] is `null` a platform default is
 * created by [engineFactory] and owned here; an engine that was handed to us is never closed.
 */
internal fun createTransport(
    apiKey: String,
    baseUrl: String,
    engine: HttpClientEngine? = null,
    defaultHeaders: Map<String, String> = emptyMap(),
    timeout: Duration = 10.seconds,
    retryPolicy: RetryPolicy = RetryPolicy(),
    log: (String) -> Unit = {},
    random: () -> Double = { Random.nextDouble() },
    sleeper: suspend (Long) -> Unit = { delay(it) },
    httpClientConfig: HttpClientConfig<*>.() -> Unit = {},
    engineFactory: () -> HttpClientEngine = ::createDefaultEngine,
): Transport {
    val ownsEngine = engine == null
    val resolvedEngine = engine ?: engineFactory()
    val http =
        HttpClient(resolvedEngine) {
            // A 429 is data we map ourselves, not an exception Ktor raises for us.
            expectSuccess = false
            httpClientConfig()
            install(HttpRequestRetry) {
                // Per-request `retry { }` never copies `delay`, so this seam reaches every request.
                delay(sleeper)
                applyPolicy(retryPolicy, random)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = timeout.inWholeMilliseconds
                socketTimeoutMillis = timeout.inWholeMilliseconds
            }
        }

    http.monitor.subscribe(HttpRequestRetryEvent) { event ->
        // One line per retry: the attempt number and the status or cause. Never headers, never bodies.
        log("retry ${event.retryCount}: ${event.response?.status?.value ?: event.cause?.let { it::class.simpleName }}")
    }

    return Transport(
        http = http,
        engine = resolvedEngine,
        ownsEngine = ownsEngine,
        apiKey = apiKey,
        baseUrl = baseUrl,
        defaultHeaders = defaultHeaders,
        retryPolicy = retryPolicy,
        random = random,
    )
}

private fun joinUrl(
    baseUrl: String,
    path: String,
): String = baseUrl.trimEnd('/') + "/" + path.trimStart('/')

private suspend fun HttpResponse.toTransportResponse(policy: RetryPolicy): TransportResponse =
    TransportResponse(
        status = status.value,
        headers = headers.flattened(),
        body = bodyAsText(),
        requestId = headers[REQUEST_ID_HEADER],
        retryAfterMs = if (policy.respectRetryAfter) parseRetryAfterMs(headers) else null,
    )

private fun Headers.flattened(): Map<String, String> = entries().associate { entry -> entry.key to entry.value.joinToString(", ") }

private fun Throwable.asTransportFailure(): Throwable {
    val cause = unwrapCancellationException()
    if (cause is CancellationException) return cause
    return when (cause.transportFailureKind()) {
        TransportFailureKind.Timeout -> TransportException.Timeout(cause)
        else -> TransportException.Connection(cause)
    }
}
