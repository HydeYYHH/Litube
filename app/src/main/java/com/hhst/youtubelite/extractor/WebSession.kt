package com.hhst.youtubelite.extractor

import kotlinx.serialization.json.JsonObject

enum class WebAccount { ANONYMOUS, AUTHENTICATED, PREMIUM }

/** Credentials stay in the host; handed-out sessions carry only the identity snapshot. */
fun interface SessionCredentials {
    fun cookies(origin: String): String
}

/**
 * Immutable identity snapshot of one YouTube web configuration; copies keep a
 * handed-out session unaffected by later captures.
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
    /** Internal snapshot marker; native sessions have no browser document yet. */
    internal val nativeBootstrap: Boolean = configurationSource == "native-session"

    // JsonElement values are effectively immutable; no defensive copy needed.
    private val configuration: JsonObject = configuration
    val webClientVersion: String?

    /** Web player requests content-bound streaming poTokens for this session. */
    val videoBoundGvs: Boolean

    init {
        this.webClientVersion = this.configuration.obj("INNERTUBE_CONTEXT")
            .obj("client")
            .takeIf { it.text("clientName") == "WEB" }?.text("clientVersion")
        this.videoBoundGvs = this.configuration.obj("WEB_PLAYER_CONTEXT_CONFIGS").entries
            .mapNotNull { it.value as? JsonObject }.any { value ->
                queryFlags(value.text("serializedExperimentFlags", ""))
                    .get("html5_generate_content_po_token") == "true"
            }
    }

    fun configuration(): JsonObject = configuration

    fun cookies(origin: String): String = credentials.cookies(origin)

    fun scope(): String = "$key:$networkGeneration"

    private companion object {
        fun queryFlags(serialized: String): Map<String, String> = serialized.split('&')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}
