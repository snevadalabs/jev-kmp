package com.sierranevadalabs.jev.sdk

import io.ktor.client.engine.HttpClientEngine

/** The engine a caller gets when they configure none: OkHttp, Darwin or CIO depending on the target. */
internal expect fun createDefaultEngine(): HttpClientEngine

/** Reads one environment variable, or `null` when it is unset. */
internal expect fun platformEnv(name: String): String?

/** `<platform>/<version>`, for `X-TypeSafe-Runtime`. Computed once per process. */
internal expect val runtimeIdentity: String
