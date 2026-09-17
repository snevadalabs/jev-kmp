package com.sierranevadalabs.jev.sdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The call-site probes for questions 1, 4, 5 and 6: everything here exists to be *compiled*, so the
 * verdict in the ticket is an observation instead of an opinion. No test asserts on these; the test for
 * the client path lives in `jvmTest` because it needs a coroutine runner.
 *
 * ## Accepted forms
 *
 * A heterogeneous vararg, and the same questions through a `List<Question<*>>`:
 *
 * ```kotlin
 * val r = client.systemOne("state", category, urgent)          // vararg
 * val all: List<Question<*>> = listOf(category, urgent)
 * val r2 = client.systemOne("state", *all.toTypedArray())      // spread — there is no List overload
 * ```
 *
 * The `String` convenience overload resolving against the member that takes a [JsonElement], and the
 * structured overload picking the other branch:
 *
 * ```kotlin
 * client.systemOne("string state", category)                   // extension, String
 * client.systemOne(buildJsonObject { }, category)              // member, JsonElement
 * ```
 *
 * ## Rejected form (question 5)
 *
 * The sibling-literal request shape, `mapOf("x" to choice(...))`, cannot type-check once the id lives
 * inside the question. Verbatim from `./gradlew :prototype:compileKotlinJvm`:
 *
 * ```
 * Type mismatch: inferred type is Map<String, ChoiceQuestion> but Question<*> was expected.
 * ```
 *
 * Nothing in that message points at "put the id first", so a Python or JS user's first instinct now costs
 * one confused compile. Accepted as the price of statically typed access.
 */
public object Probe {
    /**
     * Question 6: ten questions in one call.
     *
     * Six `noul`, two `choice`, two `score` — the shape a real caller with a long form writes.
     */
    public suspend fun tenQuestions(client: TypeSafeClient): SystemOneResponse {
        val about = choice("about", "What is this about?", mapOf("billing" to "money", "technical" to null))
        val billing = noul("billing", "Is this about billing?")
        val technical = noul("technical", "Is this technical?")
        val urgency = noul("urgency", "Does this convey urgency?")
        val anger = choice("anger", "How is the tone?", mapOf("calm" to null, "annoyed" to null))
        val churn = score("churn_risk", "Churn risk?", listOf("low", "medium", "high"))
        val refund = noul("wants_refund", "Are they asking for a refund?")
        val account = noul("account_specific", "Is this account-specific?")
        val quality = score("quality", "How good is the writing?", listOf("poor", "ok", "great"))
        val legal = noul("legal", "Is there a legal threat?")

        val r = client.systemOne(
            "Help! My payouts have been failing for 3 days.",
            about, billing, technical, urgency, anger, churn, refund, account, quality, legal,
        )

        // The typed reads the whole design exists for.
        val c: ChoiceAnswer = r[about]
        val s: ScoreAnswer = r[churn]
        check(c.choice.isNotEmpty() && s.score.isNotEmpty())
        return r
    }

    /** Question 4: the structured overloads compiling, and the one ambiguity that does need help. */
    public suspend fun structuredOverloads(client: TypeSafeClient) {
        val structured: JsonElement = buildJsonObject { put("k", "v") }

        client.systemOne(structured, noul("a", "plain"))          // member, JsonElement state
        client.systemOne("plain", noul("a", "plain"))             // extension, String state
        client.systemOne("plain", choice("c", structured, mapOf("x" to JsonPrimitive(1))))
        client.systemOne("plain", score("s", structured, listOf(JsonPrimitive(1), JsonPrimitive(2))))

        // `emptyMap()` and `emptyList()` are *not* ambiguous here, which is the surprising part: the
        // `prompt` argument alone selects the overload, so the container never has to be inferred against
        // two candidates at once. The one form that does not resolve is a plain prompt with structured
        // options — `choice("c", "Pick", mapOf("x" to JsonPrimitive(1)))` — which reports
        // `None of the following candidates is applicable:` and nothing more. Verbatim in the ticket.
    }

    /** Question 1: the same questions through a `List<Question<*>>`, which needs a spread. */
    public suspend fun listOfQuestions(client: TypeSafeClient, questions: List<Question<*>>): SystemOneResponse =
        client.systemOne("state", *questions.toTypedArray())

    /**
     * Question 3: the DSL alternative, in the only compiler-clean form it has.
     *
     * Inside the receiver block, `choice(...)` resolves to [SystemOneRequest.choice] — a `Unit`-returning
     * member — not to the top-level builder, so the block cannot produce a typed handle. The typed
     * questions have to be built outside and pushed in, which leaves the receiver functions unused and the
     * DSL reduced to a state setter plus a list append.
     */
    public suspend fun dslForm(client: TypeSafeClient): SystemOneResponse {
        val category: ChoiceQuestion = choice("category", "What is this about?", mapOf("billing" to null))
        val urgent: NoulQuestion = noul("urgent", "Does this convey urgency?")

        val request = systemOneRequest {
            state = buildJsonObject { put("text", "Help!") }
            questions += category
            questions += urgent
        }

        val r = client.systemOne(request)
        val c: ChoiceAnswer = r[category]
        val u: NoulAnswer = r[urgent]
        check(c.choice.isNotEmpty() && u.noul >= 0.0)
        return r
    }
}
