package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.loadWatch

/** Reads fresh account progress without reusing cached page metadata. */
class MediaResumePosition(private val client: MediaClient,
    private val shared: SharedWatchReads? = null) {
    suspend fun load(videoId: String): Long? {
        if (ContentSource.fixtures || MediaSession.privateBrowsing()) return null
        val position = client.read { context, http ->
            if (!context.signedIn || MediaSession.privateBrowsing()) return@read null
            // Fresh: a completed response is never reused, but an identical
            // in-flight read (the page read starting at the same moment) joins.
            val page = if (shared != null)
                shared.read(videoId, context, http, fresh = true) { id, ctx, httpClient ->
                    loadWatch(id, ctx, http = httpClient)
                }
            else loadWatch(videoId, context, http = http)
            page.requireValue().resumeSeconds
                ?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1_000 }
                ?.times(1_000)
        }
        return position.takeUnless { MediaSession.privateBrowsing() }
    }
}
