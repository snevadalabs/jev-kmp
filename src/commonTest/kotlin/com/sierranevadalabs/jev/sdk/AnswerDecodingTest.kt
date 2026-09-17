package com.sierranevadalabs.jev.sdk

import com.sierranevadalabs.jev.sdk.conformance.FixtureCase
import com.sierranevadalabs.jev.sdk.conformance.loadConformanceCases
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The answer decoder, against the same bytes the conformance fixtures carry. The three decode cases are read
 * from `conformance/cases/` rather than restated here, so a fixture edit cannot silently disagree with us.
 */
class AnswerDecodingTest {
    @Test
    fun decodesTheThreePrimitivesFromTheMixedFixture() {
        val answers = fixtureAnswers("response-mixed-primitives")

        assertEquals(NoulAnswer(0.91), answers.getValue("urgent"))
        assertEquals(
            ChoiceAnswer("billing", mapOf("billing" to 0.87, "technical" to 0.13), 0.87),
            answers.getValue("category"),
        )
        assertEquals(
            ScoreAnswer(
                score = 1.7,
                legend = mapOf(0 to JsonPrimitive("can wait"), 1 to JsonPrimitive("this week"), 2 to JsonPrimitive("today")),
                probabilities = mapOf(0 to 0.1, 1 to 0.1, 2 to 0.8),
                confidence = 0.8,
            ),
            answers.getValue("impact"),
        )
    }

    @Test
    fun scoreKeysArriveStringifiedAndSurfaceAsIntegers() {
        val score = assertIs<ScoreAnswer>(fixtureAnswers("response-score-stringified-keys").getValue("quality"))

        assertEquals(mapOf(0 to JsonPrimitive("bad"), 1 to JsonPrimitive("great")), score.legend)
        assertEquals(mapOf(0 to 0.2, 1 to 0.8), score.probabilities)
    }

    @Test
    fun anUnknownAnswerTypeBecomesUnknownAnswerAndKeepsTheRawPayload() {
        val answers = fixtureAnswers("response-unknown-answer-type")

        val unknown = assertIs<UnknownAnswer>(answers.getValue("mystery"))
        assertEquals("sentiment", unknown.type)
        assertEquals("frustrated", (unknown.raw as JsonObject).getValue("label").let { (it as JsonPrimitive).content })
        assertEquals(NoulAnswer(0.91), answers.getValue("urgent"))
    }

    @Test
    fun unknownExtraFieldsOnAKnownAnswerAreTolerated() {
        val answers =
            decode(
                """
                {
                  "urgent": {"type": "noul", "noul": 0.5, "explanation": "because", "nested": {"a": [1]} },
                  "category": {"type": "choice", "choice": "a", "confidence": 0.9,
                               "probabilities": {"a": 1.0}, "future_field": true }
                }
                """,
            )

        assertEquals(NoulAnswer(0.5), answers.getValue("urgent"))
        assertEquals(ChoiceAnswer("a", mapOf("a" to 1.0), 0.9), answers.getValue("category"))
    }

    @Test
    fun aMissingNoulFieldFailsNamingTheExactFieldPath() {
        val failure = assertFailsWith<ResponseValidationException> { decode("""{"urgent": {"type": "noul"}}""") }

        assertEquals("answers.urgent.noul", failure.fieldPath)
    }

    @Test
    fun aChoiceWithoutProbabilitiesFailsNamingTheExactFieldPath() {
        val failure =
            assertFailsWith<ResponseValidationException> {
                decode("""{"category": {"type": "choice", "choice": "a", "confidence": 0.9}}""")
            }

        assertEquals("answers.category.probabilities", failure.fieldPath)
    }

    @Test
    fun aMissingAnswerTypeFailsNamingTheTypeField() {
        val failure = assertFailsWith<ResponseValidationException> { decode("""{"urgent": {"noul": 0.5}}""") }

        assertEquals("answers.urgent.type", failure.fieldPath)
    }

    @Test
    fun aMalformedScoreKeyFailsNamingTheOffendingEntry() {
        val failure =
            assertFailsWith<ResponseValidationException> {
                decode(
                    """
                    {"quality": {"type": "score", "score": 1.0, "confidence": 0.5,
                                 "legend": {"0": "bad", "1": "great"}, "probabilities": {"0": 0.5, "x": 0.5}}}
                    """,
                )
            }

        assertEquals("answers.quality.probabilities.x", failure.fieldPath)
    }

    @Test
    fun aStringifiedScoreValueFailsRatherThanBeingCoerced() {
        val failure =
            assertFailsWith<ResponseValidationException> {
                decode("""{"quality": {"type": "score", "score": "1.0"}}""")
            }

        assertEquals("answers.quality.score", failure.fieldPath)
    }

    @Test
    fun everyFixtureResponseDecodesOrFailsAtTheFieldTheFixtureNames() {
        val cases = loadConformanceCases().filter { it.id.startsWith("response-") && it.responses.last().status == 200 }
        assertTrue(cases.isNotEmpty())
        cases.forEach { case ->
            val answers =
                Json
                    .parseToJsonElement(
                        case.responses
                            .last()
                            .body
                            .decodeToString(),
                    ).jsonObject
                    .getValue("answers")
                    .jsonObject
            val expectedField = case.expect["field"]?.jsonPrimitive?.content
            if (expectedField == null) {
                decodeAnswers(answers)
            } else {
                val failure = assertFailsWith<ResponseValidationException> { decodeAnswers(answers) }
                assertEquals(expectedField, failure.fieldPath, case.id)
            }
        }
    }

    private fun fixtureAnswers(id: String): Map<String, Answer> {
        val case = fixture(id)
        val body =
            Json
                .parseToJsonElement(
                    case.responses
                        .last()
                        .body
                        .decodeToString(),
                ).jsonObject
        return decodeAnswers(body.getValue("answers").jsonObject)
    }

    private fun fixture(id: String): FixtureCase = loadConformanceCases().first { it.id == id }

    private fun decode(answers: String): Map<String, Answer> = decodeAnswers(Json.parseToJsonElement(answers).jsonObject)
}
