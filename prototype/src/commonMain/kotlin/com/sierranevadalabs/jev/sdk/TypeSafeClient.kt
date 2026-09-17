package com.sierranevadalabs.jev.sdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration

/**
 * The client, as it would be shaped by tickets 11 and 12: an interface, one abstract method per endpoint,
 * convenience overloads as extension functions.
 */
public interface TypeSafeClient : AutoCloseable {
    /**
     * Sends the state and [questions] and returns the server's answers.
     *
     * @param state the state to reason about.
     * @param questions at least one; Kotlin permits zero `vararg` arguments, so this is a runtime check.
     * @param model overrides the client's default model for this call.
     * @param timeout overrides the client's default timeout for this call.
     */
    public suspend fun systemOne(
        state: JsonElement,
        vararg questions: Question<*>,
        model: String? = null,
        timeout: Duration? = null,
    ): SystemOneResponse
}

/**
 * [systemOne] with the state as plain text.
 *
 * The same call as [TypeSafeClient.systemOne] with a [JsonPrimitive] state.
 */
public suspend fun TypeSafeClient.systemOne(
    state: String,
    vararg questions: Question<*>,
    model: String? = null,
    timeout: Duration? = null,
): SystemOneResponse = systemOne(JsonPrimitive(state), *questions, model = model, timeout = timeout)

/**
 * A [TypeSafeClient] that answers from a canned map instead of the network.
 *
 * The prototype's own test seam; the real client is built in tickets 11 and 12.
 */
internal class FakeClient(
    private val answer: (List<Question<*>>) -> Map<String, Answer>,
) : TypeSafeClient {
    override suspend fun systemOne(
        state: JsonElement,
        vararg questions: Question<*>,
        model: String?,
        timeout: Duration?,
    ): SystemOneResponse = SystemOneResponse(answers = answer(questions.toList()))

    override fun close() {}
}
