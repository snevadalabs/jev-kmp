package com.sierranevadalabs.jev.sdk

/**
 * The wire fingerprint every request carries. Caller-supplied values for the protected names are replaced, and a
 * caller-supplied retry count is dropped rather than honoured.
 */
internal const val SDK_NAME = "typesafe-sdk-kotlin"

/** Kept in step with `gradle.properties` by the `checkVersion` task; it is the public release version. */
internal const val SDK_VERSION = "0.1.0"

internal const val AUTHORIZATION_HEADER = "Authorization"
internal const val ACCEPT_HEADER = "Accept"
internal const val CONTENT_TYPE_HEADER = "Content-Type"
internal const val SDK_HEADER = "X-TypeSafe-SDK"
internal const val RUNTIME_HEADER = "X-TypeSafe-Runtime"
internal const val RETRY_COUNT_HEADER = "X-TypeSafe-Retry-Count"
internal const val REQUEST_ID_HEADER = "x-typesafe-request-id"
internal const val RETRY_AFTER_MS_HEADER = "retry-after-ms"

/**
 * Builds the outgoing header set: client defaults, then the caller's headers, last-wins and case-insensitive, then
 * the protected names this SDK owns. `null`-style deletion is not expressible through a `Map`, so a caller cannot
 * remove a protected header — only replace their own.
 */
internal fun assembleHeaders(
    defaultHeaders: Map<String, String>,
    callerHeaders: Map<String, String>,
    apiKey: String,
    runtime: String = runtimeIdentity,
    hasBody: Boolean = false,
): Map<String, String> {
    val byLowercaseName = LinkedHashMap<String, Pair<String, String>>()

    fun merge(
        name: String,
        value: String,
    ) {
        byLowercaseName[name.lowercase()] = name to value
    }

    defaultHeaders.forEach { (name, value) -> merge(name, value) }
    callerHeaders.forEach { (name, value) -> merge(name, value) }
    byLowercaseName.remove(RETRY_COUNT_HEADER.lowercase())

    merge(AUTHORIZATION_HEADER, "Bearer $apiKey")
    merge(ACCEPT_HEADER, "application/json")
    if (hasBody) merge(CONTENT_TYPE_HEADER, "application/json")
    merge(SDK_HEADER, "$SDK_NAME/$SDK_VERSION")
    merge(RUNTIME_HEADER, runtime)

    return byLowercaseName.values.associate { it.first to it.second }
}
