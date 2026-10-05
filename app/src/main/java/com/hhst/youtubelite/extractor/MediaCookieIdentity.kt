package com.hhst.youtubelite.extractor

/** Two bounded snapshots; re-normalize only when cookies change, without delaying account fences. */
internal class MediaCookieIdentity(private val normalize: (String) -> String = YoutubeSessionProvider::authCookies) {
    private var frozen: String? = null
    private var frozenIdentity = ""
    private var live: String? = null
    private var liveIdentity = ""

    @Synchronized fun matches(snapshot: String, current: String): Boolean {
        if (snapshot == current) return true
        if (frozen != snapshot) {
            frozenIdentity = normalize(snapshot)
            frozen = snapshot
        }
        if (live != current) {
            liveIdentity = normalize(current)
            live = current
        }
        return frozenIdentity == liveIdentity
    }
}
