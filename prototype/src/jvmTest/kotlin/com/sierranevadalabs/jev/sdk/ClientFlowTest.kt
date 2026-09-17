package com.sierranevadalabs.jev.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ClientFlowTest {
    /** Answers every question with the primitive it asked for — so the typed reads are the real thing. */
    private fun faithfulFake(): TypeSafeClient = FakeClient { questions ->
        questions.associate { question ->
            question.id to when (question) {
                is ChoiceQuestion -> ChoiceAnswer("billing", mapOf("billing" to 1.0), 1.0)
                is ScoreQuestion -> ScoreAnswer("low", listOf("low", "medium", "high"), mapOf("low" to 1.0), 1.0)
                is NoulQuestion -> NoulAnswer(0.5)
            }
        }
    }

    @Test
    fun tenQuestionCallRoundTripsThroughTheClientAndReadsBackTyped() {
        val response = runBlocking { Probe.tenQuestions(faithfulFake()) }

        assertEquals(10, response.answers.size)
        assertEquals(NoulAnswer(0.5), response.answers["billing"])
    }

    @Test
    fun structuredOverloadsCompileAndRun() {
        val fake = faithfulFake()
        val questions = listOf<Question<*>>(
            noul("a", "Is this true?"),
            choice("b", "Pick one", mapOf("x" to null, "y" to null)),
        )

        runBlocking {
            Probe.structuredOverloads(fake)
            assertEquals(2, Probe.listOfQuestions(fake, questions).answers.size)
        }
    }

    @Test
    fun dslFormCompilesAndRuns() {
        val response = runBlocking { Probe.dslForm(faithfulFake()) }

        assertEquals(2, response.answers.size)
    }
}
