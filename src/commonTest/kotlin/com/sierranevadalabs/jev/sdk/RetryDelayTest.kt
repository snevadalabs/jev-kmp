package com.sierranevadalabs.jev.sdk

import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.headersOf
import io.ktor.http.toHttpDate
import io.ktor.util.date.GMTDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * The delay policy is a pure function, so it is pinned here directly; [TransportTest] pins the same numbers
 * arriving through Ktor's `HttpRequestRetry`.
 */
class RetryDelayTest {
    private val now = 1_700_000_000_000L // exactly on a second boundary, so HTTP dates round-trip

    @Test
    fun growsExponentiallyFromTheInitialBackoffAndStopsAtTheCeiling() {
        val policy = RetryPolicy(backoffJitter = 0.0)

        assertEquals(500L, retryDelayMs(0, null, policy) { 0.0 })
        assertEquals(1_000L, retryDelayMs(1, null, policy) { 0.0 })
        assertEquals(2_000L, retryDelayMs(2, null, policy) { 0.0 })
        assertEquals(4_000L, retryDelayMs(3, null, policy) { 0.0 })
        assertEquals(5_000L, retryDelayMs(4, null, policy) { 0.0 })
        assertEquals(5_000L, retryDelayMs(12, null, policy) { 0.0 })
    }

    @Test
    fun subtractsJitterAndRoundsToWholeMilliseconds() {
        val policy = RetryPolicy(backoffJitter = 0.25)

        assertEquals(500L, retryDelayMs(0, null, policy) { 0.0 })
        assertEquals(438L, retryDelayMs(0, null, policy) { 0.5 })
        assertEquals(375L, retryDelayMs(0, null, policy) { 1.0 })
        assertEquals(750L, retryDelayMs(1, null, policy) { 1.0 })
    }

    @Test
    fun prefersRetryAfterMsOverRetryAfter() {
        val headers = multiHeaders("retry-after-ms" to "1500", "Retry-After" to "5")

        assertEquals(1_500L, parseRetryAfterMs(headers, now))
    }

    @Test
    fun parsesRetryAfterSeconds() {
        assertEquals(2_000L, parseRetryAfterMs(headersOf("Retry-After", "2"), now))
        assertEquals(1_500L, parseRetryAfterMs(headersOf("Retry-After", "1.5"), now))
        assertEquals(0L, parseRetryAfterMs(headersOf("Retry-After", "0"), now))
    }

    @Test
    fun parsesRetryAfterHttpDates() {
        val future = headersOf("Retry-After", GMTDate(now + 4_000).toHttpDate())
        assertEquals(4_000L, parseRetryAfterMs(future, now))

        val past = headersOf("Retry-After", GMTDate(now - 30_000).toHttpDate())
        assertEquals(0L, parseRetryAfterMs(past, now))
    }

    @Test
    fun rejectsValuesThatAreNotAPositiveFiniteDelay() {
        assertNull(parseRetryAfterMs(headersOf("Retry-After", "-3"), now))
        assertNull(parseRetryAfterMs(headersOf("Retry-After", "soon"), now))
        assertNull(parseRetryAfterMs(headersOf("Retry-After", ""), now))
        assertNull(parseRetryAfterMs(headersOf("Retry-After", "Infinity"), now))
        assertNull(parseRetryAfterMs(multiHeaders("retry-after-ms" to "nope", "Retry-After" to "later"), now))
        assertNull(parseRetryAfterMs(headersOf(), now))
    }

    @Test
    fun aRetryAfterAboveTheCapFallsBackToTheBackoff() {
        val policy = RetryPolicy(backoffJitter = 0.0)

        assertEquals(500L, retryDelayMs(0, headersOf("Retry-After", "120"), policy) { 0.0 })
        assertEquals(500L, retryDelayMs(0, multiHeaders("retry-after-ms" to "60001"), policy) { 0.0 })
        assertEquals(60_000L, retryDelayMs(0, multiHeaders("retry-after-ms" to "60000"), policy) { 0.0 })
    }

    @Test
    fun respectRetryAfterFalseIgnoresBothHeaderForms() {
        val policy = RetryPolicy(backoffJitter = 0.0, respectRetryAfter = false)

        assertEquals(500L, retryDelayMs(0, headersOf("Retry-After", "2"), policy) { 0.0 })
        assertEquals(500L, retryDelayMs(0, multiHeaders("retry-after-ms" to "1500"), policy) { 0.0 })
    }

    @Test
    fun backoffIsCappedIndependentlyOfTheInitialValue() {
        val policy =
            RetryPolicy(
                backoffInitial = 100.milliseconds,
                backoffMax = 250.milliseconds,
                backoffJitter = 0.0,
            )

        assertEquals(100L, retryDelayMs(0, null, policy) { 0.0 })
        assertEquals(200L, retryDelayMs(1, null, policy) { 0.0 })
        assertEquals(250L, retryDelayMs(2, null, policy) { 0.0 })
        assertEquals(250L, retryDelayMs(3, null, policy) { 0.0 })
    }
}

private fun multiHeaders(vararg pairs: Pair<String, String>): Headers =
    HeadersBuilder().apply { pairs.forEach { (name, value) -> append(name, value) } }.build()
