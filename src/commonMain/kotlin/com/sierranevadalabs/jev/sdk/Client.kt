package com.sierranevadalabs.jev.sdk

import com.sierranevadalabs.jev.sdk.errors.APIResponseValidationError
import com.sierranevadalabs.jev.sdk.errors.asPublicError
import com.sierranevadalabs.jev.sdk.errors.errorFor
import com.sierranevadalabs.jev.sdk.errors.parseBody
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The TypeSafe / Jev System One client.
 *
 * Build one with the [TypeSafeClient] factory function, which resolves the [TypeSafeConfig] against the
 * environment. Every call is `suspend`; there is no blocking entry point. The client is [AutoCloseable] and
 * closes the HTTP engine it created — never one it was handed — so closing twice is safe.
 *
 * An implementation holds the API key and never prints it: not in a generated `toString()`, not in any
 * `toString()` we write, not in a log line, and not in an exception.
 */
public interface TypeSafeClient : AutoCloseable {
    /** The model catalogue, reached as `client.models.list()`. */
    public val models: Models

    /**
     * Asks one or more typed questions of [state] in a single call.
     *
     * @param state the content to evaluate: a string, object, or array.
     * @param questions the questions. At least one is required, and a `score` question needs two or more
     *   levels; both are checked locally before the request, with an [IllegalArgumentException].
     * @param model the model to use, or `null` for the client's configured default.
     * @param timeout the per-attempt request timeout, or `null` for the client's configured default.
     * @param retry the retry policy for this call, or `null` for the client's configured default.
     * @throws com.sierranevadalabs.jev.sdk.errors.JevError for a failed call; the concrete class matches the
     *   siblings' names.
     */
    public suspend fun systemOne(
        state: JsonElement,
        vararg questions: Question<*>,
        model: String? = null,
        timeout: Duration? = null,
        retry: RetryPolicy? = null,
    ): SystemOneResponse
}

/**
 * [TypeSafeClient.systemOne] with plain-text [state], the convenience form of the same call.
 *
 * @param state the content to evaluate.
 * @param questions the questions; at least one.
 */
public suspend fun TypeSafeClient.systemOne(
    state: String,
    vararg questions: Question<*>,
    model: String? = null,
    timeout: Duration? = null,
    retry: RetryPolicy? = null,
): SystemOneResponse = systemOne(JsonPrimitive(state), *questions, model = model, timeout = timeout, retry = retry)

/**
 * Builds a client from [config], resolving anything left `null` against the environment (`TYPESAFE_API_KEY`,
 * `TYPESAFE_BASE_URL`, `TYPESAFE_DEFAULT_MODEL`) and then the SDK defaults.
 *
 * @throws com.sierranevadalabs.jev.sdk.errors.JevError when no API key is configured.
 */
public fun TypeSafeClient(config: TypeSafeConfig): TypeSafeClient = createClient(config)

internal fun createClient(
    config: TypeSafeConfig,
    env: (String) -> String? = ::platformEnv,
    engineFactory: () -> HttpClientEngine = ::createDefaultEngine,
    random: () -> Double = { Random.nextDouble() },
    sleeper: suspend (Long) -> Unit = { delay(it) },
    timeSource: TimeSource = TimeSource.Monotonic,
    sink: (String) -> Unit = ::println,
): TypeSafeClient {
    val resolved = ResolvedConfig(config, env)
    val transport =
        createTransport(
            apiKey = resolved.apiKey,
            baseUrl = resolved.baseUrl,
            engine = config.engine,
            defaultHeaders = config.defaultHeaders,
            timeout = resolved.timeout,
            retryPolicy = config.retry,
            log = logSink(resolved.logLevel, sink),
            httpClientConfig = config.httpClientConfig,
            engineFactory = engineFactory,
            random = random,
            sleeper = sleeper,
            timeSource = timeSource,
        )
    return TypeSafeClientImpl(transport, resolved.defaultModel)
}

internal class TypeSafeClientImpl(
    private val transport: Transport,
    private val defaultModel: String,
) : TypeSafeClient {
    // The resource gets a closure, not the transport, so it never has the API key in reach.
    override val models: Models =
        ModelsApi { timeout, retry ->
            request(HttpMethod.Get, "/v1/models", timeout = timeout, retry = retry)
        }

    override suspend fun systemOne(
        state: JsonElement,
        vararg questions: Question<*>,
        model: String?,
        timeout: Duration?,
        retry: RetryPolicy?,
    ): SystemOneResponse {
        validateQuestions(questions.toList())
        val body =
            buildJsonObject {
                put("state", state)
                put("model", model ?: defaultModel)
                put("questions", JsonObject(questions.associate { it.id to it.toWireJson() }))
            }.toString()
        return decodeSystemOneResponse(request(HttpMethod.Post, SYSTEM_ONE_PATH, body, timeout, retry))
    }

    override fun close() {
        transport.close()
    }

    private suspend fun request(
        method: HttpMethod,
        path: String,
        body: String? = null,
        timeout: Duration? = null,
        retry: RetryPolicy? = null,
    ): TransportResponse {
        val response =
            try {
                transport.request(method, path, body = body, timeout = timeout, policy = retry)
            } catch (failure: TransportException) {
                throw failure.asPublicError()
            }
        if (response.status !in 200..299) throw errorFor(response)
        return response
    }
}

private const val SYSTEM_ONE_PATH = "/v1/systemone"

/** Reads a Wire System One payload into the response surface, mapping a malformed body to a validation error. */
internal fun decodeSystemOneResponse(response: TransportResponse): SystemOneResponse {
    val payload =
        parseBody(response.body) as? JsonObject
            ?: throw invalidResponse(response, "expected a JSON object response body", null)
    val answersBody =
        payload["answers"] as? JsonObject
            ?: throw invalidResponse(response, "answers: expected an object field 'answers'", "answers")
    val answers =
        try {
            decodeAnswers(answersBody)
        } catch (failure: ResponseValidationException) {
            throw invalidResponse(response, failure.message ?: failure.fieldPath, failure.fieldPath)
        }
    return SystemOneResponse(
        answers = answers,
        model = (payload["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        usage = (payload["usage"] as? JsonObject)?.let(::decodeUsage),
        requestId = response.requestId,
        status = response.status,
        headers = response.headers,
    )
}

private fun invalidResponse(
    response: TransportResponse,
    message: String,
    field: String?,
): APIResponseValidationError =
    APIResponseValidationError(
        field = field,
        status = response.status,
        body = parseBody(response.body),
        requestId = response.requestId,
        message = message,
    )

private fun decodeUsage(payload: JsonObject): Usage =
    Usage(
        inputTokens = payload.intOrNull("input_tokens"),
        outputTokens = payload.intOrNull("output_tokens"),
    )

private fun JsonObject.intOrNull(name: String): Int? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
