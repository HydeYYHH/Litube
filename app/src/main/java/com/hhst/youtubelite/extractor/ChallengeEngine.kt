package com.hhst.youtubelite.extractor

import java.io.IOException

/** Player script challenge solving (signature decipher + n-parameter). */
interface SignatureSolver {
    class Solutions(val signatures: Map<String, String>, val throttles: Map<String, String>)

    @Throws(IOException::class)
    fun signatureTimestamp(playerUrl: String, context: ExtractionContext): Int

    @Throws(IOException::class)
    fun solve(playerUrl: String, signatures: Set<String>, throttles: Set<String>, context: ExtractionContext): Solutions

    /** The cached player script text, fetched through the task transport when missing. */
    @Throws(IOException::class)
    fun playerScript(playerUrl: String, context: ExtractionContext): String =
        throw IOException("player_script_unavailable")

    /** Compiles ahead of the first solve; unsupported runtimes return false. */
    fun initialize(playerUrl: String, context: ExtractionContext): Boolean = false
}

/** BgUtils poToken minting bound to the extraction task's session and budget. */
interface PoTokenMinter {
    enum class Purpose { PLAYER, GVS, SUBS }
    enum class Binding { VIDEO, VISITOR, DATA_SYNC }

    class TokenRequest(
        val purpose: Purpose,
        val client: StreamClient,
        val protocol: MediaProtocol,
        val binding: Binding,
        val identifier: String,
        val scope: String,
    ) {
        fun cacheKey(): String = "$scope:$purpose:$client:$protocol:$binding:$identifier"
    }

    class Token(val value: String, val expiresAtMillis: Long)

    @Throws(IOException::class)
    fun mint(request: TokenRequest, context: ExtractionContext): Token

    fun invalidate(scope: String)

    /** Drops every cached token and the minter; used by full session recovery. */
    fun invalidateAll() {}


    /** Hosts can release a low-RAM minter after one extraction's purposes are done. */
    fun finished(context: ExtractionContext) {}
}
