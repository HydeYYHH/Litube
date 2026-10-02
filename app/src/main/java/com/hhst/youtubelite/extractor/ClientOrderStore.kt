package com.hhst.youtubelite.extractor

import com.tencent.mmkv.MMKV
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor

/**
 * MMKV persistence for the YouTube player-client trial order.
 *
 * Mirrors [YoutubeStreamExtractor.getClientOrder]; successful clients move to the front.
 */
class ClientOrderStore(
    private val kv: MMKV,
) {
    /** Loads the saved order into [YoutubeStreamExtractor]. */
    fun load() {
        val raw = kv.decodeString(KEY, null)?.trim().orEmpty()
        if (raw.isEmpty()) return
        val order = raw.split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (order.isNotEmpty()) {
            YoutubeStreamExtractor.setClientOrder(order)
        }
    }

    /** Persists the current order after a successful player response. */
    fun save() {
        val order = YoutubeStreamExtractor.getClientOrder()
        if (order.isNotEmpty()) {
            kv.encode(KEY, order.joinToString(SEPARATOR))
        }
    }

    /** Pushes a client that 403'd during playback to the end of the trial order. */
    fun demote(clientName: String) {
        YoutubeStreamExtractor.demoteClient(clientName)
        save()
    }

    private companion object {
        const val KEY = "extractor:client_order"
        const val SEPARATOR = ","
    }
}
