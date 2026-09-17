package com.sierranevadalabs.jev.sdk

import com.sierranevadalabs.jev.sdk.conformance.FixtureCase
import com.sierranevadalabs.jev.sdk.conformance.loadConformanceCases
import com.sierranevadalabs.jev.sdk.errors.APIConnectionError
import com.sierranevadalabs.jev.sdk.errors.APIError
import com.sierranevadalabs.jev.sdk.errors.APIResponseValidationError
import com.sierranevadalabs.jev.sdk.errors.APITimeoutError
import com.sierranevadalabs.jev.sdk.errors.AuthenticationError
import com.sierranevadalabs.jev.sdk.errors.BadRequestError
import com.sierranevadalabs.jev.sdk.errors.InternalServerError
import com.sierranevadalabs.jev.sdk.errors.JevError
import com.sierranevadalabs.jev.sdk.errors.NotFoundError
import com.sierranevadalabs.jev.sdk.errors.PermissionDeniedError
import com.sierranevadalabs.jev.sdk.errors.RateLimitError
import com.sierranevadalabs.jev.sdk.errors.UnprocessableEntityError
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The public entry point, end to end through `MockEngine`: the request it puts on the wire, the response
 * surface it hands back, the error it throws for a non-2xx status, and its lifecycle.
 */
class ClientTest {
    @Test
    fun roundTripsTheFixturesRequestAndResponseSurface() =
        runTest {
            val fixture = loadConformanceCases().first { it.id == "response-mixed-primitives" }
            val engine = MockEngine { respond(fixture.responses.single().body, HttpStatusCode.OK, fixtureHeaders(fixture)) }
            val client = client(engine)
            val urgent = noul("urgent", "Does this convey urgency?")
            val category = choice("category", "What is this about?", mapOf("billing" to null, "technical" to null))
            val impact = score("impact", "How bad is it?", listOf("can wait", "this week", "today"))

            val response = client.systemOne("Help! My payouts have been failing for 3 days.", urgent, category, impact)

            val request = engine.requestHistory.single()
            assertEquals(
                Json.parseToJsonElement(fixture.requestBody),
                Json.parseToJsonElement(request.body.toByteArray().decodeToString()),
                "the request body must match the fixture exactly",
            )
            assertEquals("Bearer test-key", request.headers[AUTHORIZATION_HEADER])
            assertEquals("application/json", request.headers[ACCEPT_HEADER])
            // Ktor moves the content type onto the outgoing body; the engine writes it as the header on the wire.
            assertEquals(ContentType.Application.Json, request.body.contentType)
            assertEquals("typesafe-sdk-kotlin/0.1.0", request.headers[SDK_HEADER])
            assertTrue(request.headers[RUNTIME_HEADER]?.contains('/') == true, "the runtime header names a platform and version")
            assertNull(request.headers[RETRY_COUNT_HEADER], "attempt 0 never carries a retry count")

            assertEquals("jev-latest", response.model)
            assertEquals(Usage(12, 3), response.usage)
            assertEquals("req_123", response.requestId)
            assertEquals(200, response.status)
            assertEquals("application/json", response.headers["content-type"])
            assertEquals(NoulAnswer(0.91), response[urgent])
            assertEquals(ChoiceAnswer("billing", mapOf("billing" to 0.87, "technical" to 0.13), 0.87), response[category])
            assertEquals(1.7, response[impact].score)
            client.close()
        }

    @Test
    fun structuredStateAndPerCallModelAndTimeoutReachTheWire() =
        runTest {
            val fixture = loadConformanceCases().first { it.id == "request-primitives-structured-state" }
            val engine = MockEngine { respond(fixture.responses.single().body, HttpStatusCode.OK, fixtureHeaders(fixture)) }
            val client = client(engine)
            val urgent = noul("urgent", "Does this convey urgency?")
            val category = choice("category", "What is this about?", mapOf("billing" to null, "technical" to null))
            val impact = score("impact", "How bad is it?", listOf("can wait", "this week", "today"))
            val state =
                buildJsonObject {
                    put("document", "Hello 🌍")
                    put("locale", "en-US")
                }

            client.systemOne(state, urgent, category, impact, model = "jev-fast", timeout = 250.milliseconds)

            val request = engine.requestHistory.single()
            val sent = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            val expected = Json.parseToJsonElement(fixture.requestBody).jsonObject
            assertEquals(expected.getValue("state"), sent.getValue("state"))
            assertEquals(expected.getValue("questions"), sent.getValue("questions"))
            assertEquals(JsonPrimitive("jev-fast"), sent.getValue("model"))
            assertEquals(250L, request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
            client.close()
        }

    @Test
    fun perCallRetryPolicyReplacesTheClientsOwn() =
        runTest {
            val engine = MockEngine { respond("""{"error":{"message":"later"}}""", HttpStatusCode.ServiceUnavailable) }
            val client = client(engine, retry = RetryPolicy(maxRetries = 0))

            runCatching { client.systemOne("hello", noul("urgent", "?"), retry = RetryPolicy(maxRetries = 2)) }

            assertEquals(3, engine.requestHistory.size, "the per-call policy wins over the client default")
            client.close()
        }

    @Test
    fun mapsEveryStatusToItsErrorClassInOnePlace() =
        runTest {
            val expected =
                listOf(
                    400 to BadRequestError::class,
                    401 to AuthenticationError::class,
                    403 to PermissionDeniedError::class,
                    404 to NotFoundError::class,
                    408 to APIError::class,
                    418 to APIError::class,
                    422 to UnprocessableEntityError::class,
                    429 to RateLimitError::class,
                    500 to InternalServerError::class,
                    529 to InternalServerError::class,
                )
            for ((status, type) in expected) {
                val engine = MockEngine { respond("""{"error":{"message":"boom"}}""", HttpStatusCode.fromValue(status)) }
                val failure = runCatching { client(engine).systemOne("hello", noul("urgent", "?")) }.exceptionOrNull()
                assertEquals(type, failure?.let { it::class }, "status $status")
            }
        }

    @Test
    fun rateLimitCarriesTheServersRetryAfter() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        """{"error":{"message":"slow down"}}""",
                        HttpStatusCode.TooManyRequests,
                        fixtureHeaders("Retry-After" to "7", "x-typesafe-request-id" to "req_123"),
                    )
                }

            val failure = runCatching { client(engine).systemOne("hello", noul("urgent", "?")) }.exceptionOrNull()

            val rateLimit = assertIs<RateLimitError>(failure)
            assertEquals(7_000L, rateLimit.retryAfterMs)
            assertEquals("slow down", rateLimit.message)
            assertEquals("req_123", rateLimit.requestId)
            assertEquals(429, rateLimit.status)
        }

    @Test
    fun mapsTransportFailuresToTheConnectionAndTimeoutBranch() =
        runTest {
            val connection =
                runCatching {
                    client(MockEngine { throw IOException("connect failed") }).systemOne("hello", noul("urgent", "?"))
                }.exceptionOrNull()
            assertIs<APIConnectionError>(connection)
            assertIs<IOException>(connection.cause)
            assertIs<JevError>(connection)

            val timeout =
                runCatching {
                    client(MockEngine { throw HttpRequestTimeoutException("https://api.typesafe.ai", 1_000) })
                        .systemOne("hello", noul("urgent", "?"))
                }.exceptionOrNull()
            assertIs<APITimeoutError>(timeout)
            assertIs<APIConnectionError>(timeout)
        }

    @Test
    fun aMalformedAnswerFailsWithTheFieldPath() =
        runTest {
            val engine = MockEngine { respond("""{"model":"jev-latest","answers":{"urgent":{"type":"noul"}}}""") }

            val failure = runCatching { client(engine).systemOne("hello", noul("urgent", "?")) }.exceptionOrNull()

            val validation = assertIs<APIResponseValidationError>(failure)
            assertEquals("answers.urgent.noul", validation.field)
            assertEquals(200, validation.status)
        }

    @Test
    fun usageIsNullWhenTheServerOmitsIt() =
        runTest {
            val fixture = loadConformanceCases().first { it.id == "response-usage-absent" }
            val engine = MockEngine { respond(fixture.responses.single().body, HttpStatusCode.OK, fixtureHeaders(fixture)) }

            val response = client(engine).systemOne("hello", noul("urgent", "?"))

            assertNull(response.usage)
        }

    @Test
    fun validationRejectsTheTwoLocalCasesBeforeTheNetwork() =
        runTest {
            val engine = MockEngine { respond("""{"model":"jev-latest","answers":{}}""") }
            val client = client(engine)

            assertIs<IllegalArgumentException>(
                runCatching { client.systemOne("hello") }.exceptionOrNull(),
                "a call with no questions is rejected locally",
            )
            assertIs<IllegalArgumentException>(
                runCatching { client.systemOne("hello", score("impact", "How bad?", listOf("only"))) }.exceptionOrNull(),
                "a one-level score is rejected locally",
            )
            assertEquals(0, engine.requestHistory.size)
            client.close()
        }

    @Test
    fun modelsListReturnsTheCardsAndIgnoresExtraFields() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        """{"models":[{"name":"jev-latest","description":"flagship","release_date":"2026-01-01","tags":["a"]}]}""",
                    )
                }
            val client = client(engine)

            val cards = client.models.list()

            assertEquals(listOf(ModelCard("jev-latest", "flagship", "2026-01-01")), cards)
            val request = engine.requestHistory.single()
            assertEquals("https://api.typesafe.ai/v1/models", request.url.toString())
            assertNull(request.headers[CONTENT_TYPE_HEADER], "a GET carries no body and no content type")
            client.close()
        }

    @Test
    fun modelsListNamesTheEndpointWhenTheShapeIsWrong() =
        runTest {
            for (body in listOf("""{"data":[]}""", "[]", "not json")) {
                val engine = MockEngine { respond(body) }

                val failure = runCatching { client(engine).models.list() }.exceptionOrNull()

                val validation = assertIs<APIResponseValidationError>(failure, "body: $body")
                assertTrue(validation.message!!.contains("GET /v1/models"), validation.message)
                assertTrue(validation.message!!.contains("expected { models: [...] }"), validation.message)
            }
        }

    @Test
    fun theApiKeyNeverAppearsInAStringRepresentation() =
        runTest {
            val engine = MockEngine { respond("""{"error":{"message":"invalid api key"}}""", HttpStatusCode.Unauthorized) }
            val client = client(engine)

            assertFalse("test-key" in TypeSafeConfig(apiKey = "test-key").toString(), "the config's toString")
            assertFalse("test-key" in client.toString(), "the client's toString")

            val failure = runCatching { client.systemOne("hello", noul("urgent", "?")) }.exceptionOrNull()
            assertIs<AuthenticationError>(failure)
            assertFalse("test-key" in failure.toString(), failure.toString())
            assertFalse("Bearer" in failure.toString(), failure.toString())
            client.close()
        }

    @Test
    fun closesIdempotentlyAndLeavesAnInjectedEngineAlive() =
        runTest {
            val engine = MockEngine { respond("""{"model":"jev-latest","answers":{"urgent":{"type":"noul","noul":0.5}}}""") }
            val first = client(engine)

            first.close()
            first.close()

            val second = client(engine)
            val response = second.systemOne("hello", noul("urgent", "?"))
            assertEquals(NoulAnswer(0.5), response.answers["urgent"])
            second.close()
        }

    @Test
    fun closesTheEngineItCreated() =
        runTest {
            val owned = RecordingEngine(MockEngine { respond("""{"model":"jev-latest","answers":{}}""") })

            val client = createClient(TypeSafeConfig(apiKey = "test-key"), engineFactory = { owned })
            client.close()

            assertTrue(owned.closed, "an engine the client created is the client's to close")
        }

    @Test
    fun aMissingApiKeyFailsWithTheRootError() =
        runTest {
            val failure =
                runCatching {
                    TypeSafeClient(TypeSafeConfig(engine = MockEngine { respond("""{"model":"x","answers":{}}""") }))
                }.exceptionOrNull()

            val root = assertIs<JevError>(failure)
            assertTrue(root.message!!.contains("TYPESAFE_API_KEY"), root.message)
        }
}

/** An [HttpClientEngine] that only records whether it was closed, so ownership is observable. */
private class RecordingEngine(
    private val delegate: HttpClientEngine,
) : HttpClientEngine by delegate {
    var closed = false
        private set

    override fun close() {
        closed = true
    }
}

private fun client(
    engine: HttpClientEngine,
    retry: RetryPolicy = RetryPolicy(maxRetries = 0),
): TypeSafeClient = TypeSafeClient(TypeSafeConfig(apiKey = "test-key", engine = engine, retry = retry))

private fun fixtureHeaders(vararg extra: Pair<String, String>): Headers =
    HeadersBuilder()
        .apply { extra.forEach { (name, value) -> append(name, value) } }
        .build()

private fun fixtureHeaders(fixture: FixtureCase): Headers =
    HeadersBuilder()
        .apply {
            fixture.responses
                .single()
                .headers
                .forEach { (name, value) -> append(name, value) }
        }.build()
