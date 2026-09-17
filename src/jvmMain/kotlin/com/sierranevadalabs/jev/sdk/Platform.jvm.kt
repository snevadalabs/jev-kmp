package com.sierranevadalabs.jev.sdk

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun createDefaultEngine(): HttpClientEngine = OkHttp.create()

internal actual fun platformEnv(name: String): String? = System.getenv(name)

internal actual val runtimeIdentity: String = "jvm/${System.getProperty("java.version") ?: "unknown"}"
