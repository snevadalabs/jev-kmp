package com.sierranevadalabs.jev.sdk

import com.sierranevadalabs.jev.sdk.errors.JevError

/** The environment variable that names the log level, matching both official SDKs. */
internal const val LOG_LEVEL_ENV = "TYPESAFE_LOG_LEVEL"

/** Logging is off unless a caller turns it on. */
internal val DEFAULT_LOG_LEVEL: LogLevel = LogLevel.Off

/** The prefix on every line this SDK writes, matching the JavaScript SDK's `consoleLogger`. */
internal const val LOG_PREFIX = "[typesafe-sdk]"

/**
 * How much the SDK logs. The names are the Python and JavaScript SDKs' `TYPESAFE_LOG_LEVEL` values — `debug`,
 * `info`, `warn`, `error`, `off` — read case-insensitively, so a value that works against a sibling does not
 * throw here. The default is [Off], a deliberate divergence from the JavaScript SDK, whose default is `warn`.
 *
 * What each level does today:
 *
 * - [Debug] and [Info] emit the same two lines. One is per call, after the response is read: the method, the
 *   path, the status, the duration in whole milliseconds, and the request id. The other is per retry: the
 *   attempt number and the status or cause. [Debug] is reserved for the byte-level output of the deferred
 *   pluggable-logger abstraction; it emits nothing extra yet.
 * - [Warn] and [Error] emit nothing and are accepted for sibling parity. This SDK's failures are *thrown*, and
 *   they already carry their own status, body and request id, so logging them as well would print each one
 *   twice.
 * - [Off] emits nothing.
 *
 * No level writes a header, a body, a query string, or the API key. Every line is built from the method, path,
 * status, duration and request id alone, so there is no redaction table that can be incomplete.
 */
public enum class LogLevel {
    /** Per-call and per-retry lines today; reserved for byte-level logging later. */
    Debug,

    /** The per-call line and the per-retry line. */
    Info,

    /** Accepted for sibling parity; emits nothing, because a failure is thrown rather than logged. */
    Warn,

    /** Accepted for sibling parity; emits nothing, because a failure is thrown rather than logged. */
    Error,

    /** Emits nothing. The default. */
    Off,
}

/** Parses one of the five level names, ignoring surrounding space and case. `null` for anything else. */
internal fun parseLogLevel(value: String): LogLevel? = LogLevel.entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }

/**
 * Resolves the level as `explicit → TYPESAFE_LOG_LEVEL → [Off]`. A blank or whitespace-only environment value
 * counts as unset; an unrecognised one is rejected rather than silently disabling logging.
 */
internal fun resolveLogLevel(
    explicit: LogLevel?,
    env: (String) -> String?,
): LogLevel {
    if (explicit != null) return explicit
    val raw = env(LOG_LEVEL_ENV)?.trim()?.takeIf { it.isNotEmpty() } ?: return DEFAULT_LOG_LEVEL
    return parseLogLevel(raw)
        ?: throw JevError(
            "Invalid $LOG_LEVEL_ENV value \"$raw\". " +
                "Expected one of: ${LogLevel.entries.joinToString(", ") { it.name.lowercase() }}.",
        )
}

/**
 * The level gate in front of [sink], and the only place the `[typesafe-sdk]` prefix is added. [sink] is
 * `internal` so a test can collect lines where a caller gets `println`; on Android stdout goes to logcat, so one
 * sink covers every platform without an `expect`/`actual` pair.
 */
internal fun logSink(
    level: LogLevel,
    sink: (String) -> Unit = ::println,
): (String) -> Unit =
    if (level == LogLevel.Debug || level == LogLevel.Info) {
        { message -> sink("$LOG_PREFIX $message") }
    } else {
        {}
    }
