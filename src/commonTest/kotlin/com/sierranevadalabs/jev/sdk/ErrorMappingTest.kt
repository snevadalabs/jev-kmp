package com.sierranevadalabs.jev.sdk

import com.sierranevadalabs.jev.sdk.conformance.loadConformanceCases
import com.sierranevadalabs.jev.sdk.errors.APIError
import com.sierranevadalabs.jev.sdk.errors.AuthenticationError
import com.sierranevadalabs.jev.sdk.errors.BadRequestError
import com.sierranevadalabs.jev.sdk.errors.InternalServerError
import com.sierranevadalabs.jev.sdk.errors.NotFoundError
import com.sierranevadalabs.jev.sdk.errors.PermissionDeniedError
import com.sierranevadalabs.jev.sdk.errors.RateLimitError
import com.sierranevadalabs.jev.sdk.errors.UnprocessableEntityError
import com.sierranevadalabs.jev.sdk.errors.errorFor
import com.sierranevadalabs.jev.sdk.errors.extractErrorMessage
import com.sierranevadalabs.jev.sdk.errors.parseBody
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The status→class mapping and the body-shape message extraction, in the one place each lives. */
class ErrorMappingTest {
    @Test
    fun extractsEveryMessageTheErrorFixturesDeclare() {
        val cases = loadConformanceCases().filter { it.expect["message"] != null }

        assertEquals(5, cases.size, "the error fixtures the message rules cover")
        for (case in cases) {
            val body =
                case.responses
                    .last()
                    .body
                    .decodeToString()
            assertEquals(case.expect["message"]!!.jsonPrimitive.content, extractErrorMessage(body), case.id)
        }
    }

    @Test
    fun extractsTheOtherBodyShapesAdr0005Lists() {
        assertEquals("a message", extractErrorMessage("""{"message":"a message"}"""))
        assertEquals("nested", extractErrorMessage("""{"detail":{"message":"nested"}}"""))
        assertEquals("bare", extractErrorMessage(""""bare""""))
        assertEquals("questions: msg", extractErrorMessage("""{"detail":[{"loc":["body","questions"],"msg":"msg"}]}"""))
    }

    @Test
    fun aWrongJsonTypeOnAMessageFieldFallsBackToTheRawText() {
        // ticket 22's malformed-input survivors in ErrorMapping.kt: the right key with the wrong JSON type, an
        // array where an object is expected, and a valid entry with no `msg` all extract nothing, so the body's
        // own text is the message rather than a silently empty one.
        val bodies =
            listOf(
                """{"error":123}""",
                """{"error":{"message":123}}""",
                """{"error":null}""",
                """{"message":[1]}""",
                """{"detail":[]}""",
                """{"detail":{"message":false}}""",
                """{"detail":[{"loc":["body","questions"]}]}""",
                "42",
                "[1,2]",
            )

        for (body in bodies) {
            assertEquals(body, extractErrorMessage(body), body)
        }
    }

    @Test
    fun extractsTheFastApiValidationEntriesAndTheirFieldPaths() {
        // Ported from the FastAPI `{detail: [{loc, msg}]}` cluster ticket 22 measured as unpinned. An integer
        // `loc` segment (a list index) is deliberately not asserted here: Python stringifies it into the path
        // and this SDK drops it, a divergence recorded in the Answer rather than pinned as correct.
        assertEquals("m", extractErrorMessage("""{"detail":[{"msg":"m"}]}"""))
        assertEquals("m", extractErrorMessage("""{"detail":[{"loc":["body"],"msg":"m"}]}"""))
        assertEquals("m", extractErrorMessage("""{"detail":[{"loc":"body","msg":"m"}]}"""))
        assertEquals("x.y: m", extractErrorMessage("""{"detail":[{"loc":["x","y"],"msg":"m"}]}"""))
        assertEquals(
            "questions.impact: m; questions: n",
            extractErrorMessage(
                """{"detail":[{"loc":["body","questions","impact"],"msg":"m"},{"loc":["body","questions"],"msg":"n"}]}""",
            ),
        )
    }

    @Test
    fun aBlankOrNonJsonBodyParsesToNothingWithoutThrowing() {
        assertNull(parseBody(""))
        assertNull(parseBody("   \n "))
        assertEquals(JsonPrimitive("x"), parseBody("\"x\""))
    }

    @Test
    fun capsTheExtractedMessageAtTwoHundredCharacters() {
        val long = "x".repeat(500)

        val message = extractErrorMessage(long)

        assertEquals(201, message.length, "200 characters plus the ellipsis")
        assertTrue(message.endsWith("…"), message)
    }

    @Test
    fun mapsStatusesToClassesInOnePlace() {
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
            assertEquals(type, errorFor(status, """{"error":{"message":"boom"}}""", "req_1", null)::class, "status $status")
        }
    }

    @Test
    fun rateLimitCarriesTheParsedRetryAfterAndTheRequestId() {
        val error = errorFor(429, """{"error":{"message":"slow down"}}""", "req_9", 1_500L)

        val rateLimit = error as RateLimitError
        assertEquals(1_500L, rateLimit.retryAfterMs)
        assertEquals("req_9", rateLimit.requestId)
        assertEquals("slow down", rateLimit.message)
    }

    @Test
    fun anEmptyBodyFallsBackToTheStatusAndABareStatusErrorCarriesNoRequestId() {
        val error = errorFor(503, "", null, null)

        assertEquals("HTTP 503", error.message)
        assertEquals(503, error.status)
        assertEquals(null, error.requestId)
        assertEquals(null, error.body)
    }
}
