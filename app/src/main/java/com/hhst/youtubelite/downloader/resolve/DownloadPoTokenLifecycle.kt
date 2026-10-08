package com.hhst.youtubelite.downloader.resolve

import com.hhst.youtubelite.downloader.net.DownloadResourceIdentity
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import kotlinx.coroutines.sync.withLock

/** Concurrent components share the host's one refresh; each transfer bounds its own retries. */
class DownloadPoTokenLifecycle(
    private val catalogs: DownloadCatalogSource,
    private val poToken: PoTokenEvictor? = null,
) {
    private class Recovery {
        val mutex = Mutex()
        var users = 0
        var at = 0L
        var catalog: DownloadCatalog? = null
    }
    private val recoveries = LinkedHashMap<String, Recovery>(16, .75f, true)

    suspend fun refreshAfter403(videoId: String, previousIdentities: List<String>): IdentityRefreshResult {
        val scope = videoId + ":" + catalogs.scope()
        val recovery = synchronized(recoveries) {
            recoveries.entries.removeAll { it.value.users == 0 && System.currentTimeMillis() - it.value.at > 120_000 }
            if (scope !in recoveries && recoveries.size >= 256) throw IOException("RECOVERY_CAPACITY")
            recoveries.getOrPut(scope) { Recovery() }.also { it.users++ }
        }
        try {
            return recovery.mutex.withLock {
                if (recovery.catalog == null || System.currentTimeMillis() - recovery.at >= 10_000) {
                    poToken?.evict(videoId)
                    recovery.catalog = catalogs.refresh(videoId)
                    recovery.at = System.currentTimeMillis()
                }
                val catalog = requireNotNull(recovery.catalog)
                val decisions = previousIdentities.map { previous ->
                    val format = DownloadResourceIdentity.matching(videoId, previous, catalog.formats)
                    val identity = format?.let { DownloadResourceIdentity.of(videoId, it) }
                    ComponentRefreshDecision(
                        previous,
                        identity == null || !DownloadResourceIdentity.proven(previous, identity),
                    )
                }
                IdentityRefreshResult(1, false, poToken != null, catalog, decisions)
            }
        } finally { synchronized(recoveries) { recovery.users-- } }
    }
}

data class IdentityRefreshResult(
    val round: Int,
    val exhausted: Boolean,
    val evictedPoToken: Boolean,
    val catalog: DownloadCatalog?,
    val decisions: List<ComponentRefreshDecision>,
)

data class ComponentRefreshDecision(
    val previousIdentity: String,
    val needsRedownload: Boolean,
)
