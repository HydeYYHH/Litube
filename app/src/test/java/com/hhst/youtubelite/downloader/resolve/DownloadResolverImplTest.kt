package com.hhst.youtubelite.downloader.core

import com.hhst.youtubelite.downloader.net.DownloadResourceIdentity
import com.hhst.youtubelite.downloader.resolve.DownloadCatalog
import com.hhst.youtubelite.downloader.resolve.DownloadCatalogSource
import com.hhst.youtubelite.downloader.resolve.DownloadPoTokenLifecycle
import com.hhst.youtubelite.downloader.resolve.DownloadResolverImpl
import com.hhst.youtubelite.downloader.resolve.DownloadUnavailableReason
import com.hhst.youtubelite.downloader.resolve.PoTokenEvictor
import com.hhst.youtubelite.downloader.ui.DownloadPresentation
import com.hhst.youtubelite.downloader.ui.DownloadUiMapper
import com.hhst.youtubelite.downloader.resolve.TEST_VIDEO_ID
import com.hhst.youtubelite.downloader.resolve.audioFormat
import com.hhst.youtubelite.downloader.resolve.catalog
import com.hhst.youtubelite.downloader.resolve.subtitle
import com.hhst.youtubelite.downloader.resolve.videoFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadResolverImplTest {

    private val baseFormats = listOf(
        videoFormat(720),
        videoFormat(1080),
        videoFormat(1440),
        audioFormat(),
    )

    @Test
    fun resolve_writesMimeAndExpectedBytes() = runTest {
        val h = DownloadHarness()
        val taskId = h.coordinator.enqueue(
            request("a", config = DownloadConfig(includeSubtitle = true, subtitleLanguage = "en", includeCover = true)),
            "s1",
        ).created.single().taskId
        val resolver = DownloadResolverImpl(
            coordinator = h.coordinator,
            repository = h.repo,
            catalogs = ImmediateCatalog(
                catalog(baseFormats, subtitles = listOf(subtitle("en", mime = "text/vtt"))),
            ),
        )
        val outcome = resolver.resolve(taskId)
        assertTrue(outcome is DownloadResolveOutcome.Ready)
        val snap = h.repo.transact { snapshot(taskId) }!!
        assertEquals(DownloadPhase.TRANSFER, snap.task.phase)
        assertEquals(DownloadStatus.QUEUED, snap.task.status)
        val video = snap.assets.first { it.asset.kind == AssetKind.VIDEO }
        val videoComp = video.components.first { it.component.kind == InputComponentKind.VIDEO }.component
        val audioComp = video.components.first { it.component.kind == InputComponentKind.AUDIO }.component
        assertEquals("video/mp4", videoComp.mimeType)
        assertEquals("audio/mp4", audioComp.mimeType)
        assertTrue(videoComp.expectedBytes != null && videoComp.expectedBytes!! > 0)
        assertTrue(audioComp.resourceIdentity!!.startsWith(DownloadResourceIdentity.PREFIX))
        assertFalse(audioComp.resourceIdentity!!.contains("expire"))
        val sub = snap.assets.first { it.asset.kind == AssetKind.SUBTITLE }
        assertTrue(sub.asset.outputName!!.endsWith(".en.vtt"))
        assertEquals("text/vtt", sub.asset.mimeType)
        val cover = snap.assets.first { it.asset.kind == AssetKind.COVER }
        assertTrue(cover.asset.outputName!!.endsWith(".jpg"))
        assertEquals(1080, resolver.lastPlan!!.video!!.format.height)
    }

    @Test
    fun resolve_retryKeepsExplicitQuality() = runTest {
        val h = DownloadHarness()
        val config = DownloadConfig(videoQuality = "1440p", audioTrack = "id:en")
        val taskId = h.coordinator.enqueue(request("a", config = config), "s1").created.single().taskId
        val catalogs = ImmediateCatalog(catalog(baseFormats))
        val resolver = DownloadResolverImpl(h.coordinator, h.repo, catalogs)
        resolver.resolve(taskId)
        val firstItag = resolver.lastPlan!!.video!!.format.itag
        resolver.resolve(taskId)
        assertEquals(1440, resolver.lastPlan!!.video!!.format.height)
        assertEquals(firstItag, resolver.lastPlan!!.video!!.format.itag)
        assertEquals("id:en", resolver.lastPlan!!.audio!!.audioTrackKey)
    }

    @Test
    fun cancelDownloadWait_doesNotCancelSharedParse() = runTest {
        val catalog = catalog(baseFormats)
        val spy = SharedParseSpy(this, catalog)
        val playback = async { spy.catalog(TEST_VIDEO_ID) }
        spy.parseStarted.await()
        val download = launch { spy.catalog(TEST_VIDEO_ID) }
        yield()
        download.cancelAndJoin()
        assertEquals(1, spy.waitCancelled)
        assertFalse(spy.parseCancelled)
        assertEquals(TEST_VIDEO_ID, playback.await().videoId)
        assertFalse(spy.parseCancelled)
        assertTrue(spy.parseCompleted)
    }

    @Test
    fun resolveCancel_doesNotCancelSharedParse() = runTest {
        val h = DownloadHarness()
        val taskId = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        val spy = SharedParseSpy(this, catalog(baseFormats))
        val resolver = DownloadResolverImpl(h.coordinator, h.repo, spy)
        val playback = async { spy.catalog(TEST_VIDEO_ID) }
        spy.parseStarted.await()
        val job = launch { resolver.resolve(taskId) }
        yield()
        job.cancelAndJoin()
        assertTrue(spy.waitCancelled >= 1)
        assertFalse(spy.parseCancelled)
        playback.await()
        assertTrue(spy.parseCompleted)
    }

    @Test
    fun identityMismatch_afterUrlRefresh_marksRedownload() = runTest {
        val h = DownloadHarness()
        val taskId = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        val original = catalog(baseFormats)
        val mismatched = catalog(
            listOf(
                videoFormat(1080, clen = 9),
                audioFormat(clen = 9),
            ),
        )
        val catalogs = SwitchingCatalog(original, mismatched)
        val evictions = mutableListOf<String>()
        val resolver = DownloadResolverImpl(
            coordinator = h.coordinator,
            repository = h.repo,
            catalogs = catalogs,
            poTokens = DownloadPoTokenLifecycle(catalogs, PoTokenEvictor { evictions += it }),
        )
        resolver.resolve(taskId)
        val result = resolver.refreshAfter403(taskId)!!
        assertEquals(1, result.round)
        assertTrue(result.evictedPoToken)
        assertEquals(listOf(vid("a")), evictions)
        assertTrue(result.decisions.any { it.needsRedownload })
        val snap = h.repo.transact { snapshot(taskId) }!!
        assertTrue(snap.assets.flatMap { it.components }.any { it.component.needsRedownload })
    }

    @Test
    fun poTokenRefresh_concurrentComponentsShareOneRefresh() = runTest {
        val catalogs = ImmediateCatalog(catalog(baseFormats))
        val lifecycle = DownloadPoTokenLifecycle(catalogs, PoTokenEvictor { })
        val identity = DownloadResourceIdentity.of(TEST_VIDEO_ID, videoFormat(1080))
        val first = lifecycle.refreshAfter403(TEST_VIDEO_ID, listOf(identity))
        val second = lifecycle.refreshAfter403(TEST_VIDEO_ID, listOf(identity))
        val third = lifecycle.refreshAfter403(TEST_VIDEO_ID, listOf(identity))
        assertEquals(1, first.round)
        assertEquals(1, second.round)
        assertFalse(third.exhausted)
        assertEquals(1, third.round)
        assertTrue(first.catalog === second.catalog && second.catalog === third.catalog)
    }

    @Test
    fun liveResolve_failsWithDistinctReason() = runTest {
        val h = DownloadHarness()
        val taskId = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        val resolver = DownloadResolverImpl(
            h.coordinator,
            h.repo,
            ImmediateCatalog(catalog(baseFormats, isLive = true, durationSec = 0L)),
        )
        val outcome = resolver.resolve(taskId) as DownloadResolveOutcome.Failed
        assertEquals(DownloadUnavailableReason.LIVE.name, outcome.reason)
        val snap = h.repo.transact { snapshot(taskId) }!!
        assertEquals(DownloadStatus.FAILED, snap.task.status)
        assertEquals(DownloadUnavailableReason.LIVE.name, snap.task.errorMessage)
    }

    @Test
    fun extractionFailure_exposesActualCauseInDownloadRow() = runTest {
        val h = DownloadHarness()
        val taskId = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        val catalogs = object : DownloadCatalogSource {
            override suspend fun catalog(videoId: String): DownloadCatalog =
                throw java.io.IOException("Connection timed out\n  at extractor")
            override suspend fun refresh(videoId: String) = catalog(videoId)
        }
        val resolver = DownloadResolverImpl(h.coordinator, h.repo, catalogs)
        val outcome = resolver.resolve(taskId) as DownloadResolveOutcome.Failed
        assertEquals(DownloadUnavailableReason.EXTRACTION_FAILED.name, outcome.reason)
        val snapshot = h.repo.transact { snapshot(taskId) }!!
        val row = DownloadUiMapper.item(snapshot)
        assertEquals("Connection timed out", DownloadPresentation.failureReason(row))
    }

    @Test
    fun unknownMediaLength_doesNotUseBitrateEstimateOrPreviousLength() = runTest {
        val h = DownloadHarness()
        val taskId = h.coordinator.enqueue(request("a"), "s1").created.single().taskId
        h.coordinator.reportResolved(taskId, 0, listOf(ResolvedComponentUpdate(
            AssetKind.VIDEO, InputComponentKind.VIDEO, expectedBytes = 999)))
        val unknown = videoFormat(1080, clen = 0).copy(bitrate = 4_000_000, approxDurationMs = 60_000)
        val resolver = DownloadResolverImpl(h.coordinator, h.repo, ImmediateCatalog(catalog(listOf(unknown, audioFormat()))))
        assertTrue(resolver.resolve(taskId) is DownloadResolveOutcome.Ready)
        val snapshot = h.repo.transact { snapshot(taskId) }!!
        val video = snapshot.assets.first { it.asset.kind == AssetKind.VIDEO }
            .components.first { it.component.kind == InputComponentKind.VIDEO }
        assertEquals(null, video.component.expectedBytes)
        assertEquals(null, DownloadUiMapper.item(snapshot).expectedBytes)
    }

    @Test
    fun changingSessionDoesNotReuseThePreviousRecoveryCatalog() = runTest {
        var account = "first"
        var refreshes = 0
        val catalogs = object : DownloadCatalogSource {
            override fun scope(): String = account
            override suspend fun catalog(videoId: String): DownloadCatalog = catalog(baseFormats)
            override suspend fun refresh(videoId: String): DownloadCatalog { refreshes++; return catalog(videoId) }
        }
        val lifecycle = DownloadPoTokenLifecycle(catalogs)
        val first = lifecycle.refreshAfter403(TEST_VIDEO_ID, emptyList())
        val shared = lifecycle.refreshAfter403(TEST_VIDEO_ID, emptyList())
        assertTrue(first.catalog === shared.catalog)
        account = "second"
        lifecycle.refreshAfter403(TEST_VIDEO_ID, emptyList())
        assertEquals(2, refreshes)
    }
}

private class ImmediateCatalog(
    private val catalog: DownloadCatalog,
    private var refreshCatalog: DownloadCatalog = catalog,
) : DownloadCatalogSource {
    override suspend fun catalog(videoId: String) = catalog
    override suspend fun refresh(videoId: String) = refreshCatalog
}

private class SwitchingCatalog(
    private val first: DownloadCatalog,
    private val refreshed: DownloadCatalog,
) : DownloadCatalogSource {
    override suspend fun catalog(videoId: String) = first
    override suspend fun refresh(videoId: String) = refreshed
}

/**
 * Shared parse job plus independent waiters: cancelling one await must not
 * cancel the parse other waiters (playback) still need.
 */
private class SharedParseSpy(
    scope: CoroutineScope,
    private val value: DownloadCatalog,
) : DownloadCatalogSource {
    val parseStarted = CompletableDeferred<Unit>()
    var parseCancelled = false
    var parseCompleted = false
    var waitCancelled = 0
    private val parsed = CompletableDeferred<DownloadCatalog>()

    init {
        scope.launch {
            parseStarted.complete(Unit)
            try {
                delay(40)
                parsed.complete(value)
                parseCompleted = true
            } catch (cancelled: CancellationException) {
                parseCancelled = true
                throw cancelled
            }
        }
    }

    override suspend fun catalog(videoId: String): DownloadCatalog {
        try {
            return parsed.await()
        } catch (cancelled: CancellationException) {
            waitCancelled++
            throw cancelled
        }
    }

    override suspend fun refresh(videoId: String): DownloadCatalog = value
}
