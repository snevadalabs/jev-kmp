package com.sierranevadalabs.jev.sdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The rejected builder alternative for question 3: a `systemOne { }` receiver DSL.
 *
 * Kept only so the verdict has something compiled to point at; it is not proposed API.
 */
public class SystemOneRequest {
    /** The state to reason about; required. */
    public var state: JsonElement = JsonNull

    /** The questions collected by the receiver functions. */
    public val questions: MutableList<Question<*>> = mutableListOf()

    /** Adds a [noul] question. */
    public fun noul(id: String, prompt: String): Unit { questions.add(com.sierranevadalabs.jev.sdk.noul(id, prompt)) }

    /** Adds a [choice] question. */
    public fun choice(id: String, prompt: String, options: Map<String, String?>): Unit {
        questions.add(com.sierranevadalabs.jev.sdk.choice(id, prompt, options))
    }

    /** Adds a [score] question. */
    public fun score(id: String, prompt: String, levels: List<String>): Unit {
        questions.add(com.sierranevadalabs.jev.sdk.score(id, prompt, levels))
    }
}

/** Builds a [SystemOneRequest] with the receiver DSL. */
public fun systemOneRequest(block: SystemOneRequest.() -> Unit): SystemOneRequest =
    SystemOneRequest().apply(block)

/** Sends a [SystemOneRequest] over the client. A DSL-only convenience the interface does not have. */
public suspend fun TypeSafeClient.systemOne(request: SystemOneRequest): SystemOneResponse =
    systemOne(request.state, *request.questions.toTypedArray())
