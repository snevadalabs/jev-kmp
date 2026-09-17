package com.sierranevadalabs.jev.sdk

import kotlinx.serialization.json.JsonElement

/**
 * One answer from the System One API, discriminated by the question's primitive.
 *
 * This is the raw, untyped view: every value in [SystemOneResponse.answers] is an [Answer], and an
 * exhaustive `when` over it keeps working when the server adds a primitive because a new variant has to
 * be added here first. Use the typed accessors on [SystemOneResponse] when the primitive is known.
 */
public sealed interface Answer

/** The answer to a [NoulQuestion]: the model's probability that the statement is true, in `0..1`. */
public data class NoulAnswer(public val noul: Double) : Answer

/**
 * The answer to a [ChoiceQuestion].
 *
 * @property choice the option the model picked, one of the question's option keys.
 * @property probabilities per-option probabilities, keyed by the question's option keys.
 * @property confidence the model's confidence in [choice], in `0..1`.
 */
public data class ChoiceAnswer(
    public val choice: String,
    public val probabilities: Map<String, Double>,
    public val confidence: Double,
) : Answer

/**
 * The answer to a [ScoreQuestion].
 *
 * @property score the level the model picked, one of the question's levels.
 * @property legend the question's ordered levels, echoed back.
 * @property probabilities per-level probabilities, keyed by level.
 * @property confidence the model's confidence in [score], in `0..1`.
 */
public data class ScoreAnswer(
    public val score: String,
    public val legend: List<String>,
    public val probabilities: Map<String, Double>,
    public val confidence: Double,
) : Answer

/**
 * A primitive this SDK version does not know, kept so that a newer server degrades instead of failing.
 *
 * This variant is never returned by a typed accessor — a [Question] cannot ask for it. It surfaces only
 * through [SystemOneResponse.answers].
 *
 * @property type the server's `type` discriminator, verbatim.
 * @property raw the answer body, unparsed.
 */
public data class UnknownAnswer(public val type: String, public val raw: JsonElement) : Answer
