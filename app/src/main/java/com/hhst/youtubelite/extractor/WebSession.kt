package com.hhst.youtubelite.extractor

import com.google.gson.JsonObject

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
    private val configuration: JsonObject = configuration.deepCopy()
    /** The page's WEB client version, when the captured configuration is a WEB one. */
    val webClientVersion: String?

    /** The web player requests content-bound streaming poTokens for this session. */
    val videoBoundGvs: Boolean

    init {
        this.webClientVersion = this.configuration.obj("INNERTUBE_CONTEXT")
            .obj("client")
            .takeIf { it.text("clientName") == "WEB" }?.text("clientVersion")
        this.videoBoundGvs = this.configuration.obj("WEB_PLAYER_CONTEXT_CONFIGS").entrySet()
            .mapNotNull { it.value as? JsonObject }.any { value ->
                queryFlags(value.text("serializedExperimentFlags", ""))
                    .get("html5_generate_content_po_token") == "true"
            }
    }

    fun configuration(): JsonObject = configuration.deepCopy()

    fun cookies(origin: String): String = credentials.cookies(origin)

    fun scope(): String = "$key:$networkGeneration"

    private companion object {
        /** Minimal `key=value&key=value` reader for serialized experiment flags. */
        fun queryFlags(serialized: String): Map<String, String> = serialized.split('&')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}
