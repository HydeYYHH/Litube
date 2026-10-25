package com.hhst.youtubelite.extractor

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import com.grack.nanojson.JsonWriter

/** Account state captured with the page configuration. */
enum class WebAccount { ANONYMOUS, AUTHENTICATED, PREMIUM }

/** Credentials stay in the host; stream and plan objects only carry the snapshot above. */
fun interface SessionCredentials {
    fun cookies(origin: String): String
}

/**
 * Immutable identity snapshot of one YouTube web configuration. Copy semantics
 * keep a handed-out session unaffected by later captures.
 */
class YoutubeWebSession(
    val key: String,
    val account: WebAccount,
    val accountIndex: Int,
    val delegatedId: String?,
    val userSessionId: String?,
    val dataSyncId: String?,
    val visitorData: String?,
    val userAgent: String,
    val networkGeneration: Long,
    val playerUrl: String?,
    val configurationSource: String,
    configuration: JsonObject,
    private val credentials: SessionCredentials,
) {
    private val configuration: JsonObject = copy(configuration)
    /** The page's WEB client version, when the captured configuration is a WEB one. */
    val webClientVersion: String?

    /** The web player requests content-bound streaming poTokens for this session. */
    val videoBoundGvs: Boolean

    init {
        this.webClientVersion = this.configuration.getObject("INNERTUBE_CONTEXT")
            .getObject("client")
            .takeIf { it.getString("clientName") == "WEB" }?.getString("clientVersion")
        this.videoBoundGvs = this.configuration.getObject("WEB_PLAYER_CONTEXT_CONFIGS").values
            .filterIsInstance<JsonObject>().any { value ->
                queryFlags(value.getString("serializedExperimentFlags", ""))
                    .get("html5_generate_content_po_token") == "true"
            }
    }

    fun configuration(): JsonObject = copy(configuration)

    fun cookies(origin: String): String = credentials.cookies(origin)

    fun scope(): String = "$key:$networkGeneration"

    private companion object {
        fun copy(value: JsonObject): JsonObject = try {
            JsonParser.`object`().from(JsonWriter.string(value))
        } catch (failure: JsonParserException) {
            throw IllegalArgumentException("Invalid session configuration", failure)
        }

        /** Minimal `key=value&key=value` reader for serialized experiment flags. */
        fun queryFlags(serialized: String): Map<String, String> = serialized.split('&')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}
