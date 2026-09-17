package com.sierranevadalabs.jev.sdk

import android.os.Build
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun createDefaultEngine(): HttpClientEngine = OkHttp.create()

// Android is a JVM for `System.getenv`; the same actual as the JVM target.
internal actual fun platformEnv(name: String): String? = System.getenv(name)

// The integer API level, not the spoofable `Build.VERSION.RELEASE` string.
internal actual val runtimeIdentity: String = "android/${Build.VERSION.SDK_INT}"
