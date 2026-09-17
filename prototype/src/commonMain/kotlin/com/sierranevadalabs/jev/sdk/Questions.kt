package com.sierranevadalabs.jev.sdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A question to send to the System One API, carrying both its wire name and the concrete [Answer] type it
 * predicts.
 *
 * The typed key *is* the wire question: [id] is the key the answer comes back under, and `T` is what
 * [SystemOneResponse.get] hands back. This is what lets one call mix primitives and still read each answer
 * statically typed:
 *
 * ```kotlin
 * val category = choice("category", "What is this about?", mapOf("billing" to null))
 * val urgent = noul("urgent", "Does this convey urgency?")
 *
 * val r = client.systemOne("Help!", category, urgent)
 * val c: ChoiceAnswer = r[category]
 * ```
 *
 * @property id the wire key; the answer to this question arrives under this key.
 * @property prompt the question text, as a [JsonElement] so structured prompts work too.
 */
public sealed interface Question<T : Answer> {
    /** The wire key this question's answer arrives under. */
    public val id: String

    /** The question text. */
    public val prompt: JsonElement
}

/** The question asked by [noul]. */
public data class NoulQuestion(
    override val id: String,
    override val prompt: JsonElement,
) : Question<NoulAnswer>

/** The question asked by [choice]. */
public data class ChoiceQuestion(
    override val id: String,
    override val prompt: JsonElement,
    /** The option keys, mapped to their optional descriptions. */
    public val options: Map<String, JsonElement?>,
) : Question<ChoiceAnswer>

/** The question asked by [score]. */
public data class ScoreQuestion(
    override val id: String,
    override val prompt: JsonElement,
    /** The ordered levels, lowest first. At least two. */
    public val levels: List<JsonElement>,
) : Question<ScoreAnswer>

/** Asks a boolean question: is [prompt] true of the state? Reads back as a [NoulAnswer]. */
public fun noul(id: String, prompt: String): NoulQuestion = NoulQuestion(id, JsonPrimitive(prompt))

/** Asks a boolean question with a structured [prompt]. Reads back as a [NoulAnswer]. */
public fun noul(id: String, prompt: JsonElement): NoulQuestion = NoulQuestion(id, prompt)

/**
 * Asks the model to pick one of [options]. Reads back as a [ChoiceAnswer].
 *
 * @param options the option keys, each mapped to an optional description.
 */
public fun choice(id: String, prompt: String, options: Map<String, String?>): ChoiceQuestion =
    ChoiceQuestion(id, JsonPrimitive(prompt), options.mapValues { it.value?.let(::JsonPrimitive) })

/**
 * Asks the model to pick one of [options], with a structured [prompt]. Reads back as a [ChoiceAnswer].
 *
 * @param options the option keys, each mapped to an optional structured description.
 */
public fun choice(id: String, prompt: JsonElement, options: Map<String, JsonElement>): ChoiceQuestion =
    ChoiceQuestion(id, prompt, options)

/**
 * Asks the model to rate the state on ordered [levels]. Reads back as a [ScoreAnswer].
 *
 * @param levels at least two levels, lowest first.
 */
public fun score(id: String, prompt: String, levels: List<String>): ScoreQuestion =
    ScoreQuestion(id, JsonPrimitive(prompt), levels.map(::JsonPrimitive))

/**
 * Asks the model to rate the state on ordered [levels], with a structured [prompt]. Reads back as a
 * [ScoreAnswer].
 *
 * @param levels at least two levels, lowest first.
 */
public fun score(id: String, prompt: JsonElement, levels: List<JsonElement>): ScoreQuestion =
    ScoreQuestion(id, prompt, levels)

/**
 * Whether [answer] is the answer type this question predicts.
 *
 * This is the type check behind [SystemOneResponse.get] and [SystemOneResponse.answerOrNull]. It cannot be
 * a reified cast: `T` is erased at runtime, so `as? T` would compile to an unchecked cast to [Answer] and
 * hand back a wrong-typed value that only blows up as a `ClassCastException` at the caller's assignment.
 *
 * `internal` rather than an interface member: the sealed hierarchy means the mapping is total, and the
 * exhaustive `when` here is what forces a new primitive to be wired up.
 */
internal fun questionAccepts(question: Question<*>, answer: Answer): Boolean = when (question) {
    is NoulQuestion -> answer is NoulAnswer
    is ChoiceQuestion -> answer is ChoiceAnswer
    is ScoreQuestion -> answer is ScoreAnswer
}
