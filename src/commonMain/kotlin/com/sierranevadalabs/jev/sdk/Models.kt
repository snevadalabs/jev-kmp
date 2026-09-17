package com.sierranevadalabs.jev.sdk

import com.sierranevadalabs.jev.sdk.errors.APIResponseValidationError
import com.sierranevadalabs.jev.sdk.errors.parseBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The model catalogue: what the API can be asked with. Reached as `client.models`. */
public interface Models {
    /**
     * Returns every model the API offers, from `GET /v1/models`.
     *
     * @throws com.sierranevadalabs.jev.sdk.errors.APIResponseValidationError if a 200 body is not
     *   `{ models: [...] }` with a string `name` on every entry — a server-side contract break, not a caller
     *   error.
     * @throws com.sierranevadalabs.jev.sdk.errors.JevError for a failed call. Retry and the status-to-error
     *   mapping have already been applied by the client.
     */
    public suspend fun list(): List<ModelCard>
}

/**
 * One entry in the model catalogue. Fields the server adds later are ignored.
 *
 * @property name the model id to pass as `model`.
 * @property description a human-readable summary, when the server sends one.
 * @property releaseDate the release date as the server writes it, verbatim.
 */
public data class ModelCard(
    public val name: String,
    public val description: String? = null,
    public val releaseDate: String? = null,
)

/**
 * The token counters the server reports for one call. Every field is optional: the API omits `usage`
 * entirely on some responses and has never sent the OpenAPI schema's `billing_units`.
 *
 * @property inputTokens tokens the model read.
 * @property outputTokens tokens the model produced.
 */
public data class Usage(
    public val inputTokens: Int? = null,
    public val outputTokens: Int? = null,
)

/**
 * The `models` resource.
 *
 * It holds a closure over the client's request path rather than the transport itself, so the resource never
 * holds — and cannot leak — the API key. Errors and retries are the client's, already applied when the
 * closure returns.
 */
internal class ModelsApi(
    private val fetch: suspend () -> TransportResponse,
) : Models {
    override suspend fun list(): List<ModelCard> {
        val response = fetch()
        val payload = parseBody(response.body) as? JsonObject
        val models = payload?.get("models") as? JsonArray
        if (models == null) {
            throw APIResponseValidationError(
                field = "models",
                status = response.status,
                body = parseBody(response.body),
                requestId = response.requestId,
                message = "GET /v1/models: expected { models: [...] }",
            )
        }
        return models.mapIndexed { index, element -> decodeModelCard(element, index, response) }
    }
}

private fun decodeModelCard(
    element: JsonElement,
    index: Int,
    response: TransportResponse,
): ModelCard {
    val card = element as? JsonObject
    val name = (card?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (card == null || name == null) {
        throw APIResponseValidationError(
            field = "models.$index.name",
            status = response.status,
            body = parseBody(response.body),
            requestId = response.requestId,
            message = "GET /v1/models: models.$index.name: expected a string field 'name'",
        )
    }
    return ModelCard(
        name = name,
        description = card.stringFieldOrNull("description"),
        releaseDate = card.stringFieldOrNull("release_date"),
    )
}

private fun JsonObject.stringFieldOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
