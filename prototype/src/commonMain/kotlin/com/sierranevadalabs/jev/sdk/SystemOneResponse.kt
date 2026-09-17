package com.sierranevadalabs.jev.sdk

/**
 * The response to a `systemOne` call.
 *
 * [answers] is the raw, always-available view; [get] and [answerOrNull] are the typed ones, keyed by the
 * [Question] that produced the answer.
 */
public class SystemOneResponse(
    /** Every answer the server returned, keyed by question id, including unknown primitives. */
    public val answers: Map<String, Answer>,
    /** The model that answered, as reported by the server. */
    public val model: String? = null,
    /** The server's `x-typesafe-request-id`, for support tickets. */
    public val requestId: String? = null,
) {
    /**
     * The answer to [question], statically typed.
     *
     * @throws AnswerTypeMismatchException if the answer under this key is not a [T] — a caller-side
     *   contract error, since the question's own type says what it asked for.
     * @throws NoSuchElementException if the response has no answer under this key.
     */
    public operator fun <T : Answer> get(question: Question<T>): T {
        val answer = answers[question.id]
            ?: throw NoSuchElementException("no answer for question '${question.id}'")
        if (!questionAccepts(question, answer)) {
            throw AnswerTypeMismatchException(
                "question '${question.id}' is a ${question::class.simpleName} " +
                    "but the server answered with ${answer::class.simpleName}",
            )
        }
        // Sound: questionAccepts above established the runtime type.
        @Suppress("UNCHECKED_CAST")
        return answer as T
    }

    /**
     * The answer to [question], or `null` if it is missing, an unknown primitive, or not a [T].
     *
     * The non-throwing twin of [get].
     */
    public fun <T : Answer> answerOrNull(question: Question<T>): T? {
        val answer = answers[question.id] ?: return null
        if (!questionAccepts(question, answer)) return null
        @Suppress("UNCHECKED_CAST")
        return answer as T
    }
}

/**
 * Thrown when a question is read back as the wrong answer type.
 *
 * This is a programming error and nothing on the wire can produce it, so it deliberately does not live in
 * `com.sierranevadalabs.jev.sdk.errors` next to the transport failures.
 *
 * @param message names the question id and both types.
 */
public class AnswerTypeMismatchException(message: String) : IllegalStateException(message)
