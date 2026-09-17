package com.sierranevadalabs.jev.sdk

import com.sierranevadalabs.jev.sdk.errors.JevError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Config resolution is `explicit → env → default`, with the siblings' env names and defaults, and a
 * blank or whitespace-only env value is ignored rather than treated as set.
 */
class ConfigTest {
    @Test
    fun explicitBeatsEnvBeatsDefault() {
        val env = mapOf(BASE_URL_ENV to "https://from-env.test")

        assertEquals("https://explicit.test", resolveSetting("https://explicit.test", BASE_URL_ENV, DEFAULT_BASE_URL, env::get))
        assertEquals("https://from-env.test", resolveSetting(null, BASE_URL_ENV, DEFAULT_BASE_URL, env::get))
        assertEquals(DEFAULT_BASE_URL, resolveSetting(null, BASE_URL_ENV, DEFAULT_BASE_URL, { null }))
    }

    @Test
    fun blankAndWhitespaceOnlyEnvValuesAreIgnoredNotTreatedAsSet() {
        assertEquals(DEFAULT_BASE_URL, resolveSetting(null, BASE_URL_ENV, DEFAULT_BASE_URL, { "" }))
        assertEquals(DEFAULT_BASE_URL, resolveSetting(null, BASE_URL_ENV, DEFAULT_BASE_URL, { "   " }))
        assertNull(resolveSetting(null, API_KEY_ENV, { "\t\n " }))
    }

    @Test
    fun envValuesAreTrimmedAndTheSiblingsNamesAndDefaultsAreUsed() {
        val env =
            mapOf(
                API_KEY_ENV to " env-key ",
                DEFAULT_MODEL_ENV to "jev-test",
            )

        val resolved = ResolvedConfig(TypeSafeConfig(), env::get)

        assertEquals("env-key", resolved.apiKey)
        assertEquals(DEFAULT_BASE_URL, resolved.baseUrl)
        assertEquals("jev-test", resolved.defaultModel)
        assertEquals(10.seconds, resolved.timeout)
        assertEquals("TYPESAFE_API_KEY", API_KEY_ENV)
        assertEquals("TYPESAFE_BASE_URL", BASE_URL_ENV)
        assertEquals("TYPESAFE_DEFAULT_MODEL", DEFAULT_MODEL_ENV)
    }

    @Test
    fun aMissingApiKeyFailsWithTheRootError() {
        val failure = assertFailsWith<JevError> { ResolvedConfig(TypeSafeConfig(), { null }) }

        assertIs<JevError>(failure)
        assertEquals(true, failure.message!!.contains(API_KEY_ENV), failure.message)
    }

    @Test
    fun aNonPositiveTimeoutIsRejected() {
        assertFailsWith<IllegalArgumentException> { ResolvedConfig(TypeSafeConfig(timeout = Duration.ZERO), apiKeyOnlyEnv) }
        assertFailsWith<IllegalArgumentException> {
            ResolvedConfig(TypeSafeConfig(timeout = (-1).seconds), apiKeyOnlyEnv)
        }
    }
}

/**
 * An environment that answers for the API key and nothing else, the way a real one does for an unset variable:
 * a stub returning the same value for every name would hand `TYPESAFE_LOG_LEVEL` a key and fail resolution.
 */
private val apiKeyOnlyEnv: (String) -> String? = { if (it == API_KEY_ENV) "key" else null }
