package com.sierranevadalabs.jev.sdk.conformance

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** One response in a case's script: the status, the headers, and the exact bytes of the body. */
internal class FixtureResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/** One conformance case, as the format in `docs/adr/0005-conformance-fixture-format.md` defines it. */
internal data class FixtureCase(
    val id: String,
    val method: String,
    val path: String,
    val requestBody: String,
    val responses: List<FixtureResponse>,
    val expect: JsonObject,
)

private val KNOWN_ANSWER_TYPES = setOf("noul", "choice", "score")

/** Reads every `*.json` under the manifest's case directory and hands back one [FixtureCase] each. */
internal fun loadConformanceCases(files: Map<String, String> = FIXTURE_FILES): List<FixtureCase> {
    val manifest = Json.parseToJsonElement(files.getValue("manifest.json")).jsonObject
    val directory = manifest.getValue("cases").jsonPrimitive.content
    return files.keys
        .filter { it.startsWith("$directory/") && it.endsWith(".json") }
        .sorted()
        .map { parseCase(it, files.getValue(it)) }
}

private fun parseCase(
    path: String,
    text: String,
): FixtureCase {
    val case = Json.parseToJsonElement(text).jsonObject
    val id = case.getValue("id").jsonPrimitive.content
    require(id == path.substringAfterLast('/').removeSuffix(".json")) { "$path: id '$id' must match the file name" }
    val request = case.getValue("request").jsonObject
    return FixtureCase(
        id = id,
        method = request.getValue("method").jsonPrimitive.content,
        path = request.getValue("path").jsonPrimitive.content,
        requestBody = request.getValue("body").toString(),
        responses = case.getValue("responses").jsonArray.map(::parseResponse),
        expect = case.getValue("expect").jsonObject,
    )
}

private fun parseResponse(element: JsonElement): FixtureResponse {
    val response = element.jsonObject
    val body = response["bodyRaw"]?.jsonPrimitive?.content ?: response.getValue("body").toString()
    return FixtureResponse(
        status = response.getValue("status").jsonPrimitive.int,
        headers = response["headers"]?.jsonObject.orEmpty().mapValues { (_, value) -> value.jsonPrimitive.content },
        body = body.encodeToByteArray(),
    )
}

/**
 * Replays one case through Ktor's `MockEngine` and checks the format's own invariants: the transport sees the
 * fixture's request and response bytes, and the declared expectation is derivable from the declared payload.
 *
 * The stub stands in for the SDK. *Build the conformance, wire, and live test tiers* replaces it with the real
 * client, which turns the last check into "the SDK's decoded answers match `expect.answers`" without touching
 * the fixtures. ponytail: there is no retry loop here either — the response list *is* the attempt count until
 * the real transport exists.
 */
internal suspend fun assertConformanceCase(case: FixtureCase) {
    var attempt = 0
    val requests = mutableListOf<String>()
    val engine =
        MockEngine { request ->
            requests += request.body.toByteArray().decodeToString()
            val response = case.responses[attempt++]
            respond(
                response.body,
                HttpStatusCode.fromValue(response.status),
                Headers.build { response.headers.forEach { (name, value) -> append(name, value) } },
            )
        }
    val client = HttpClient(engine)
    try {
        var response: HttpResponse? = null
        repeat(case.responses.size) {
            response = client.post(case.path) { setBody(case.requestBody) }
        }
        assertEquals(case.responses.size, requests.size, "${case.id}: attempts the transport saw")
        assertEquals(case.requestBody, requests.first(), "${case.id}: request body")
        assertEquals(case.requestBody, requests.last(), "${case.id}: retried request body")
        assertFixtureShape(case, assertNotNull(response).status.value)
    } finally {
        client.close()
    }
}

private fun assertFixtureShape(
    case: FixtureCase,
    status: Int,
) {
    assertEquals(case.responses.last().status, status, "${case.id}: final status")

    case.expect["error"]?.jsonPrimitive?.content?.let { name ->
        assertTrue(
            case.expect["message"] != null || case.expect["field"] != null,
            "${case.id}: '$name' must name the extracted message or the offending field",
        )
        return
    }

    val last = case.responses.last()
    val lastBody = last.body.decodeToString()
    val payload = Json.parseToJsonElement(lastBody).jsonObject
    case.expect["usage"]?.jsonPrimitive?.content?.let { expected ->
        val usage = payload["usage"]
        val present = usage != null && usage !is JsonNull
        assertEquals(expected == "present", present, "${case.id}: usage must be $expected")
    }
    case.expect["answers"]?.jsonObject?.forEach { (key, type) ->
        val answer = payload.getValue("answers").jsonObject[key] ?: fail("${case.id}: no answer '$key'")
        val answerObject = answer.jsonObject
        val declaredType = answerObject.getValue("type")
        val declared = declaredType.jsonPrimitive.content
        if (type.jsonPrimitive.content == "unknown") {
            val unknownTypes = case.expect["unknownTypes"]?.jsonObject
            val expectedType = unknownTypes?.get(key)?.jsonPrimitive?.content
            assertTrue(declared !in KNOWN_ANSWER_TYPES, "${case.id}: '$key' is a known type, not an unknown one")
            assertEquals(expectedType, declared, "${case.id}: '$key' carries the server's own type string")
        } else {
            assertEquals(type.jsonPrimitive.content, declared, "${case.id}: '$key' answer type")
        }
    }
    case.expect["delayMs"]?.let {
        assertTrue(case.responses.size > 1, "${case.id}: delayMs means a retry, so there must be two responses")
    }
}
