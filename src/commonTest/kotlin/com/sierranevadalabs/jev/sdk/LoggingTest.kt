package com.sierranevadalabs.jev.sdk

import com.sierranevadalabs.jev.sdk.errors.JevError
import com.sierranevadalabs.jev.sdk.errors.RateLimitError
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

/**
 * The logging surface: off unless asked for, one level gate in front of one sink, and two lines — the per-call
 * line and the per-retry line — that can carry nothing but the method, path, status, duration and request id.
 */
class LoggingTest {
    @Test
    fun offByDefaultIsSilentForARealCall() =
        runTest {
            val lines = mutableListOf<String>()
            val client = loggingClient(MockEngine { respond(OK_BODY) }, lines = lines)

            client.systemOne("hello", noul("urgent", "?"))

            assertTrue(lines.isEmpty(), "a default client must write nothing, not merely format nothing: $lines")
            client.close()
        }

    @Test
    fun theFiveLevelsGateEveryLine() =
        runTest {
            for (level in LogLevel.entries) {
                val lines = mutableListOf<String>()
                val client = loggingClient(MockEngine { respond(OK_BODY) }, logLevel = level, lines = lines)

                client.systemOne("hello", noul("urgent", "?"))

                val emits = level == LogLevel.Debug || level == LogLevel.Info
                assertEquals(emits, lines.isNotEmpty(), "level $level")
                if (emits) assertEquals(1, lines.size, "one line per call at $level: $lines")
                client.close()
            }
        }

    @Test
    fun thePerCallLineIsExactForAPinnedDurationAndRequestId() =
        runTest {
            val lines = mutableListOf<String>()
            val time = TestTimeSource()
            val engine =
                MockEngine {
                    time += 42.milliseconds
                    respond(OK_BODY, HttpStatusCode.OK, headers(REQUEST_ID_HEADER to "req_123"))
                }
            val client = loggingClient(engine, logLevel = LogLevel.Info, lines = lines, timeSource = time)

            client.systemOne("hello", noul("urgent", "?"))

            assertEquals(listOf("[typesafe-sdk] POST /v1/systemone <- 200 in 42ms (request req_123)"), lines)
            client.close()
        }

    @Test
    fun theLineOmitsTheRequestIdTheServerDidNotSend() =
        runTest {
            val lines = mutableListOf<String>()
            val time = TestTimeSource()
            val engine =
                MockEngine {
                    time += 7.milliseconds
                    respond("""{"models":[]}""")
                }
            val client = loggingClient(engine, logLevel = LogLevel.Info, lines = lines, timeSource = time)

            client.models.list()

            assertEquals(listOf("[typesafe-sdk] GET /v1/models <- 200 in 7ms"), lines)
            client.close()
        }

    @Test
    fun theRetryLineGoesThroughTheSameGateAndSink() =
        runTest {
            val lines = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    if (request.headers[RETRY_COUNT_HEADER] == null) {
                        respond("""{"error":{"message":"later"}}""", HttpStatusCode.ServiceUnavailable)
                    } else {
                        respond(OK_BODY)
                    }
                }
            val client = loggingClient(engine, logLevel = LogLevel.Info, lines = lines, retry = RetryPolicy(maxRetries = 1))

            client.systemOne("hello", noul("urgent", "?"))

            assertEquals(2, lines.size, lines.toString())
            assertEquals("[typesafe-sdk] retry 1: 503", lines[0])
            assertTrue(lines[1].startsWith("[typesafe-sdk] POST /v1/systemone <- 200 in "), lines[1])
            client.close()
        }

    @Test
    fun aFailureIsLoggedWithTheStatusItFailedOn() =
        runTest {
            val lines = mutableListOf<String>()
            val engine = MockEngine { respond("""{"error":{"message":"slow down"}}""", HttpStatusCode.TooManyRequests) }
            val client = loggingClient(engine, logLevel = LogLevel.Info, lines = lines)

            assertIs<RateLimitError>(runCatching { client.systemOne("hello", noul("urgent", "?")) }.exceptionOrNull())

            assertEquals(1, lines.size, lines.toString())
            assertTrue(lines.single().startsWith("[typesafe-sdk] POST /v1/systemone <- 429 in "), lines.single())
            client.close()
        }

    @Test
    fun aCallThatNeverGotAResponseLogsTheFailureClass() =
        runTest {
            val lines = mutableListOf<String>()
            val client = loggingClient(MockEngine { throw IOException("connect failed") }, logLevel = LogLevel.Info, lines = lines)

            runCatching { client.systemOne("hello", noul("urgent", "?")) }

            assertEquals(1, lines.size, lines.toString())
            assertTrue(lines.single().startsWith("[typesafe-sdk] POST /v1/systemone <- failed in "), lines.single())
            assertTrue(lines.single().contains("IOException"), lines.single())
            client.close()
        }

    @Test
    fun noLineCanCarryTheApiKeyAHeaderABodyOrAQuery() =
        runTest {
            val lines = mutableListOf<String>()
            val engine =
                MockEngine {
                    respond(
                        """{"model":"model-secret","answers":{"urgent":{"type":"noul","noul":0.5}}}""",
                        HttpStatusCode.OK,
                        headers(REQUEST_ID_HEADER to "req_123"),
                    )
                }
            val client =
                loggingClient(
                    engine,
                    logLevel = LogLevel.Debug,
                    lines = lines,
                    defaultHeaders = mapOf("x-api-secret" to "header-secret-value"),
                )

            client.systemOne("state-secret", noul("urgent", "prompt-secret"))

            val logged = lines.joinToString("\n")
            assertTrue(lines.isNotEmpty(), "the leak assertions would be vacuous on silence")
            val leaks =
                listOf(
                    "test-key",
                    "Bearer",
                    "Authorization",
                    "header-secret-value",
                    "state-secret",
                    "prompt-secret",
                    "model-secret",
                )
            for (leak in leaks) {
                assertFalse(leak in logged, "the log leaked '$leak': $logged")
            }
            client.close()
        }

    @Test
    fun aQueryStringNeverReachesTheLine() =
        runTest {
            val lines = mutableListOf<String>()
            val transport =
                createTransport(
                    apiKey = "test-key",
                    baseUrl = "https://api.typesafe.ai",
                    engine = MockEngine { respond("""{"ok":true}""") },
                    log = { lines += it },
                    sleeper = {},
                )

            transport.request(HttpMethod.Get, "/v1/models?api_key=test-key")

            assertEquals(1, lines.size, lines.toString())
            assertFalse("?" in lines.single(), lines.single())
            assertFalse("test-key" in lines.single(), lines.single())
            transport.close()
        }

    @Test
    fun theSinkPrefixesTheLineAndOnlyWhenTheLevelIsOn() {
        val lines = mutableListOf<String>()
        val sink: (String) -> Unit = { lines += it }

        logSink(LogLevel.Debug, sink)("hello")
        logSink(LogLevel.Info, sink)("hello")
        logSink(LogLevel.Warn, sink)("hello")
        logSink(LogLevel.Error, sink)("hello")
        logSink(LogLevel.Off, sink)("hello")

        assertEquals(listOf("[typesafe-sdk] hello", "[typesafe-sdk] hello"), lines)
    }

    @Test
    fun theLevelIsResolvedExplicitThenEnvThenOff() {
        assertEquals(LogLevel.Error, resolveLogLevel(LogLevel.Error) { "debug" }, "an explicit level wins")
        assertEquals(LogLevel.Debug, resolveLogLevel(null) { "DEBUG" }, "the sibling names are read case-insensitively")
        assertEquals(LogLevel.Off, resolveLogLevel(null) { "   " }, "a blank env value is unset, so the default applies")
        assertEquals(LogLevel.Off, resolveLogLevel(null) { null })
        for (name in listOf("debug", "info", "warn", "error", "off")) {
            assertEquals(name, parseLogLevel(name)?.name?.lowercase(), name)
        }
    }

    @Test
    fun anUnparseableEnvLevelFailsLoudlyAndNamesTheVariable() {
        val failure = assertFailsWith<JevError> { resolveLogLevel(null) { "verbose" } }

        assertTrue(failure.message!!.contains(LOG_LEVEL_ENV), failure.message)
        assertTrue(failure.message!!.contains("verbose"), failure.message)
        assertFailsWith<JevError> {
            ResolvedConfig(TypeSafeConfig(apiKey = "test-key")) { if (it == LOG_LEVEL_ENV) "verbose" else null }
        }
    }
}

private const val OK_BODY = """{"model":"jev-latest","answers":{"urgent":{"type":"noul","noul":0.5}}}"""

private fun loggingClient(
    engine: HttpClientEngine,
    logLevel: LogLevel? = null,
    lines: MutableList<String>,
    retry: RetryPolicy = RetryPolicy(maxRetries = 0),
    defaultHeaders: Map<String, String> = emptyMap(),
    timeSource: TimeSource = TimeSource.Monotonic,
): TypeSafeClient =
    createClient(
        TypeSafeConfig(
            apiKey = "test-key",
            engine = engine,
            retry = retry,
            defaultHeaders = defaultHeaders,
            logLevel = logLevel,
        ),
        // The real environment must never reach a test, or a developer's TYPESAFE_LOG_LEVEL would change it.
        env = { null },
        timeSource = timeSource,
        sink = { lines += it },
    )

private fun headers(vararg pairs: Pair<String, String>): Headers =
    HeadersBuilder().apply { pairs.forEach { (name, value) -> append(name, value) } }.build()
