package com.sierranevadalabs.jev.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.json.JsonPrimitive

class SystemOneResponseTest {
    private val category = choice("category", "What is this about?", mapOf("billing" to null))
    private val urgent = noul("urgent", "Does this convey urgency?")
    private val choiceAnswer = ChoiceAnswer("billing", mapOf("billing" to 0.9), 0.9)
    private val noulAnswer = NoulAnswer(0.8)

    private val response = SystemOneResponse(
        answers = mapOf("category" to choiceAnswer, "urgent" to noulAnswer),
    )

    @Test
    fun typedAccessReturnsTheConcreteAnswer() {
        val c: ChoiceAnswer = response[category]
        val u: NoulAnswer = response[urgent]

        assertEquals(choiceAnswer, c)
        assertEquals(noulAnswer, u)
    }

    @Test
    fun typedAccessOnTheWrongQuestionTypeThrows() {
        val mismatch = noul("category", "Does this convey urgency?")

        assertFailsWith<AnswerTypeMismatchException> { response[mismatch] }
    }

    @Test
    fun answerOrNullReturnsNullInsteadOfThrowingOnATypeMismatch() {
        val mismatch = noul("category", "Does this convey urgency?")

        assertNull(response.answerOrNull(mismatch))
    }

    @Test
    fun answerOrNullReturnsNullForAnUnknownPrimitive() {
        val unknown = SystemOneResponse(
            answers = mapOf("urgent" to UnknownAnswer("noul_v2", JsonPrimitive(0.5))),
        )

        assertNull(unknown.answerOrNull(urgent))
    }

    @Test
    fun unknownAnswerSurvivesInTheRawAnswersMap() {
        val unknownAnswer = UnknownAnswer("noul_v2", JsonPrimitive(0.5))
        val unknown = SystemOneResponse(answers = mapOf("urgent" to unknownAnswer))

        assertSame(unknownAnswer, unknown.answers["urgent"])
    }

    @Test
    fun missingKeyThrowsAndItsOrNullTwinReturnsNull() {
        val absent = noul("absent", "Is this true?")
        assertFailsWith<NoSuchElementException> { response[absent] }
        assertNull(response.answerOrNull(absent))
    }
}
