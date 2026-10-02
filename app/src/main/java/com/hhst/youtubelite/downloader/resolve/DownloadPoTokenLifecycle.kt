package com.hhst.youtubelite.downloader.resolve

import com.hhst.youtubelite.downloader.net.DownloadResourceIdentity
import com.hhst.youtubelite.extractor.Extractor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 403 URL refresh for downloads: serial per video, at most two rounds, and
 * the related PoToken is cleared before the independent re-extract.
 *
 * [Extractor.awaitMedia] / [Extractor.awaitFreshMedia] waits are independently
 * cancellable. Playback's in-flight `/player` slot is not cancelled here.
 *
 * PoToken mint uses a dedicated WebView. Process-global timer occupancy is
 * owned by [com.hhst.youtubelite.downloader.webview.WebViewTimerOccupancy].
 */
class DownloadPoTokenLifecycle(
    private val catalogs: DownloadCatalogSource,
    private val poToken: PoTokenEvictor? = null,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val rounds = ConcurrentHashMap<String, Int>()

    suspend fun refreshAfter403(
        videoId: String,
        previousIdentities: List<String>,
    ): IdentityRefreshResult {
        val mutex = locks.getOrPut(videoId) { Mutex() }
        return mutex.withLock {
            val next = (rounds[videoId] ?: 0) + 1
            if (next > MAX_ROUNDS) {
                return@withLock IdentityRefreshResult(
                    videoId = videoId,
                    round = next,
                    exhausted = true,
                    evictedPoToken = false,
                    catalog = null,
                    decisions = previousIdentities.map {
                        ComponentRefreshDecision(it, refreshedIdentity = null, needsRedownload = true)
                    },
                )
            }
            rounds[videoId] = next
            poToken?.evict(videoId)
            val catalog = catalogs.refresh(videoId)
            val byItag = catalog.formats.associateBy { it.itag }
            val decisions = previousIdentities.map { previous ->
                val parts = previous.removePrefix(DownloadResourceIdentity.PREFIX).split(':')
                val itag = parts.getOrNull(1)?.toIntOrNull()
                val refreshedFormat = itag?.let { byItag[it] }
                val refreshed = refreshedFormat?.let { DownloadResourceIdentity.of(videoId, it) }
                val proven = refreshed != null && DownloadResourceIdentity.proven(previous, refreshed)
                ComponentRefreshDecision(
                    previousIdentity = previous,
                    refreshedIdentity = refreshed,
                    needsRedownload = !proven,
                )
            }
            IdentityRefreshResult(
                videoId = videoId,
                round = next,
                exhausted = false,
                evictedPoToken = poToken != null,
                catalog = catalog,
                decisions = decisions,
            )
        }
    }

    companion object {
        const val MAX_ROUNDS = 2
    }
}

data class IdentityRefreshResult(
    val videoId: String,
    val round: Int,
    val exhausted: Boolean,
    val evictedPoToken: Boolean,
    val catalog: DownloadCatalog?,
    val decisions: List<ComponentRefreshDecision>,
)

data class ComponentRefreshDecision(
    val previousIdentity: String,
    val refreshedIdentity: String?,
    val needsRedownload: Boolean,
)
