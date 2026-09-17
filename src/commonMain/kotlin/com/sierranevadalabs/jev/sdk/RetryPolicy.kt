package com.sierranevadalabs.jev.sdk

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How a call is retried when the server or the transport itself fails.
 *
 * The defaults mirror the official TypeSafe SDKs: two retries, a 500 ms initial backoff growing exponentially to
 * a 5 s ceiling, 25% subtractive jitter, and retries on 408, 429 and any 5xx.
 *
 * @property backoffInitial delay before the first retry. Each further retry doubles it.
 * @property backoffMax ceiling of the exponential backoff, applied before jitter is subtracted.
 * @property maxRetryAfter largest server-supplied `retry-after-ms`/`Retry-After` this policy honours. A larger
 *   value falls back to the backoff rather than waiting for it.
 * @property backoffJitter fraction of the capped backoff that jitter may subtract, in `0.0..1.0`. `0.0` makes
 *   every delay exactly deterministic.
 * @property maxRetries retries after the initial attempt. `0` disables retrying.
 * @property httpStatuses response codes that are retried. Copied when a call is prepared.
 * @property respectRetryAfter whether the server's `Retry-After` is honoured on the delay, and on the rate-limit
 *   error surfaced for a 429.
 * @property apiConnectionError whether a transport failure that is not a timeout is retried.
 * @property apiTimeoutError whether a request timeout is retried.
 */
public data class RetryPolicy(
    public val backoffInitial: Duration = 500.milliseconds,
    public val backoffMax: Duration = 5.seconds,
    public val maxRetryAfter: Duration = 60.seconds,
    public val backoffJitter: Double = 0.25,
    public val maxRetries: Int = 2,
    public val httpStatuses: Set<Int> = setOf(408, 429) + (500..599),
    public val respectRetryAfter: Boolean = true,
    public val apiConnectionError: Boolean = true,
    public val apiTimeoutError: Boolean = true,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must be >= 0, was $maxRetries" }
        require(backoffInitial > Duration.ZERO) { "backoffInitial must be positive, was $backoffInitial" }
        require(backoffMax >= backoffInitial) {
            "backoffMax ($backoffMax) must be >= backoffInitial ($backoffInitial)"
        }
        require(maxRetryAfter >= Duration.ZERO) { "maxRetryAfter must be >= 0, was $maxRetryAfter" }
        require(backoffJitter in 0.0..1.0) { "backoffJitter must be in 0.0..1.0, was $backoffJitter" }
        require(httpStatuses.all { it in 100..599 }) {
            "httpStatuses must only contain HTTP status codes, was $httpStatuses"
        }
    }
}
