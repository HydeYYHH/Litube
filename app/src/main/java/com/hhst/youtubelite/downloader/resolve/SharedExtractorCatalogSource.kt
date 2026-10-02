package com.hhst.youtubelite.downloader.resolve

import com.hhst.youtubelite.extractor.Extractor

/**
 * Shared [Extractor] access for downloads. [catalog] joins the in-flight parse
 * (same `/player` as playback). [refresh] uses [Extractor.awaitFreshMedia] so
 * a 403 retry does not cancel or replace playback's in-flight slot and does
 * not write stream URLs into the playback cache.
 *
 * Cancelling the coroutine that calls these methods cancels only the wait.
 */
class SharedExtractorCatalogSource(
    private val extractor: Extractor,
) : DownloadCatalogSource {
    override suspend fun catalog(videoId: String): DownloadCatalog {
        val (metadata, stream) = extractor.awaitMedia(videoId)
        return DownloadCatalog.from(metadata, stream)
    }

    override suspend fun refresh(videoId: String): DownloadCatalog {
        val (metadata, stream) = extractor.awaitFreshMedia(videoId)
        return DownloadCatalog.from(metadata, stream)
    }
}
