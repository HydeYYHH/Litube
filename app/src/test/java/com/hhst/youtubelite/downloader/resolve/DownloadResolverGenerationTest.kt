package com.hhst.youtubelite.downloader.resolve

import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadHarness
import com.hhst.youtubelite.downloader.core.DownloadResolveOutcome
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.InputComponent
import com.hhst.youtubelite.downloader.core.request
import com.hhst.youtubelite.downloader.data.DownloadRepository
import com.hhst.youtubelite.downloader.data.DownloadSession
import com.hhst.youtubelite.extractor.MediaProtocol
import com.hhst.youtubelite.extractor.MediaRange
import com.hhst.youtubelite.extractor.MediaRequestPlan
import com.hhst.youtubelite.extractor.StreamClient
import com.hhst.youtubelite.extractor.WebAccount
import com.hhst.youtubelite.extractor.YoutubeWebSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadResolverGenerationTest {
    @Test fun staleAdmissionCannotResolveTheResumedGeneration() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("generation"), "generation").created.single().taskId
        val admitted = h.generation(id)
        h.coordinator.pause(DownloadTarget.Task(id))
        h.coordinator.resume(DownloadTarget.Task(id))
        val resumed = h.repo.transact { snapshot(id) }!!
        val catalogs = Catalogs { media("current") }
        val resolver = DownloadResolverImpl(h.coordinator, h.repo, catalogs)

        assertEquals(DownloadResolveOutcome.Stale, resolver.resolve(id, admitted))
        assertEquals(0, catalogs.reads)
        assertEquals(resumed, h.repo.transact { snapshot(id) })
        assertTrue(resolver.sourcesOf(id, resumed.task.executionGeneration).isEmpty())
    }

    @Test fun lateCatalogCannotOverwriteTheResumedSources() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("generation"), "generation").created.single().taskId
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first = true
        val catalogs = Catalogs {
            if (first) {
                first = false
                started.complete(Unit)
                release.await()
                media("old", 4)
            } else media("new", 8)
        }
        val resolver = DownloadResolverImpl(h.coordinator, h.repo, catalogs)
        val admitted = h.generation(id)
        val old = async { resolver.resolve(id, admitted) }
        started.await()
        h.coordinator.pause(DownloadTarget.Task(id))
        h.coordinator.resume(DownloadTarget.Task(id))
        val current = h.generation(id)
        assertTrue(resolver.sourcesOf(id, current).isEmpty())
        assertTrue(resolver.resolve(id, current) is DownloadResolveOutcome.Ready)
        val snapshot = h.repo.transact { snapshot(id) }
        val sources = resolver.sourcesOf(id, current)
        release.complete(Unit)

        assertEquals(DownloadResolveOutcome.Stale, old.await())
        assertEquals(snapshot, h.repo.transact { snapshot(id) })
        assertEquals(sources, resolver.sourcesOf(id, current))
        assertTrue(sources.all { "id=new" in it.url })
        assertTrue(resolver.sourcesOf(id, admitted).isEmpty())
        assertEquals("new", resolver.lastPlan!!.muxed!!.format.url.substringAfter("id=").substringBefore('&'))
    }

    @Test fun aResolutionAcceptedBeforeResumeCannotPublishItsCacheAfterResume() = runTest {
        val h = DownloadHarness()
        val gated = CommittedResolutionGate(h.repo)
        val coordinator = DownloadCoordinator(gated)
        val id = coordinator.enqueue(request("generation"), "generation").created.single().taskId
        var catalog = media("old", 4)
        val resolver = DownloadResolverImpl(coordinator, gated, Catalogs { catalog })
        val admitted = h.generation(id)
        gated.arm()
        val old = async { resolver.resolve(id, admitted) }
        gated.committed.await()
        // reportResolved has committed, but the old resolver has not published its cache.
        coordinator.pause(DownloadTarget.Task(id))
        coordinator.resume(DownloadTarget.Task(id))
        val current = h.generation(id)
        catalog = media("new", 8)
        assertTrue(resolver.resolve(id, current) is DownloadResolveOutcome.Ready)
        val snapshot = h.repo.transact { snapshot(id) }
        val sources = resolver.sourcesOf(id, current)
        gated.release.complete(Unit)

        assertEquals(DownloadResolveOutcome.Stale, old.await())
        assertEquals(snapshot, h.repo.transact { snapshot(id) })
        assertEquals(sources, resolver.sourcesOf(id, current))
        assertTrue(resolver.sourcesOf(id, admitted).isEmpty())
    }

    @Test fun lateCatalogCannotPublishAfterPauseWithoutResume() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("generation"), "generation").created.single().taskId
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolver = DownloadResolverImpl(h.coordinator, h.repo, Catalogs {
            started.complete(Unit)
            release.await()
            media("old")
        })
        val admitted = h.generation(id)
        val old = async { resolver.resolve(id, admitted) }
        started.await()
        h.coordinator.pause(DownloadTarget.Task(id))
        val paused = h.repo.transact { snapshot(id) }
        release.complete(Unit)

        assertEquals(DownloadResolveOutcome.Stale, old.await())
        assertEquals(paused, h.repo.transact { snapshot(id) })
        assertTrue(resolver.sourcesOf(id, admitted).isEmpty())
        assertNull(resolver.lastPlan)
    }

    @Test fun lateForbiddenRecoveryCannotAdoptTheResumedGeneration() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("generation"), "generation").created.single().taskId
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var catalog = media("original", 4)
        val catalogs = Catalogs { catalog }
        catalogs.refreshRead = {
            started.complete(Unit)
            release.await()
            media("old-refresh", 12)
        }
        val resolver = DownloadResolverImpl(h.coordinator, h.repo, catalogs)
        val admitted = h.generation(id)
        assertTrue(resolver.resolve(id, admitted) is DownloadResolveOutcome.Ready)
        val identity = resolver.sourcesOf(id, admitted).single().resourceIdentity
        val old = async { resolver.recoverSource(id, admitted, identity) }
        started.await()
        h.coordinator.pause(DownloadTarget.Task(id))
        h.coordinator.resume(DownloadTarget.Task(id))
        val current = h.generation(id)
        catalog = media("new", 8)
        assertTrue(resolver.resolve(id, current) is DownloadResolveOutcome.Ready)
        val snapshot = h.repo.transact { snapshot(id) }
        val sources = resolver.sourcesOf(id, current)
        release.complete(Unit)

        assertNull(old.await())
        assertEquals(snapshot, h.repo.transact { snapshot(id) })
        assertEquals(sources, resolver.sourcesOf(id, current))
        assertFalse(resolver.recoverForbidden(id, admitted))
        assertEquals(1, catalogs.refreshes)
    }

    @Test fun staleBackupIsRejectedWhileTheCurrentGenerationCanStillUseItsBackup() = runTest {
        val h = DownloadHarness()
        val id = h.coordinator.enqueue(request("generation"), "generation").created.single().taskId
        val session = YoutubeWebSession("generation", WebAccount.ANONYMOUS, 0, null, null, null,
            "visitor", "browser-UA", 0, null, "test", JsonObject(emptyMap()), { "" })
        fun plan(client: StreamClient) = MediaRequestPlan(session, client, MediaProtocol.HTTPS, MediaRange.HEADER)
        val catalog = catalog(listOf(
            muxedFormat(360, client = "VISIONOS").copy(requestPlan = plan(StreamClient.VISIONOS)),
            muxedFormat(240, client = "ANDROID").copy(requestPlan = plan(StreamClient.ANDROID_VR)),
        ))
        val resolver = DownloadResolverImpl(h.coordinator, h.repo, Catalogs { catalog })
        val admitted = h.generation(id)
        assertTrue(resolver.resolve(id, admitted) is DownloadResolveOutcome.Ready)
        val oldIdentity = resolver.sourcesOf(id, admitted).single().resourceIdentity
        h.coordinator.pause(DownloadTarget.Task(id))
        h.coordinator.resume(DownloadTarget.Task(id))
        val current = h.generation(id)
        assertTrue(resolver.resolve(id, current) is DownloadResolveOutcome.Ready)
        val snapshot = h.repo.transact { snapshot(id) }
        val sources = resolver.sourcesOf(id, current)

        assertNull(resolver.backupSource(id, admitted, oldIdentity))
        assertEquals(snapshot, h.repo.transact { snapshot(id) })
        assertEquals(sources, resolver.sourcesOf(id, current))
        val backup = resolver.backupSource(id, current, sources.single().resourceIdentity)
        assertNotNull(backup)
        assertFalse(backup!!.exhausted)
        assertNotEquals(sources.single().requestPlan!!.client, backup.source.requestPlan!!.client)
    }

    private suspend fun DownloadHarness.generation(id: String) = repo.transact { getTask(id) }!!.executionGeneration

    private fun media(identity: String, length: Long = 4) = catalog(listOf(muxedFormat().copy(
        url = playbackUrl(18, length, client = "ANDROID", id = identity),
    )))

    private class Catalogs(private val read: suspend () -> DownloadCatalog) : DownloadCatalogSource {
        var reads = 0
        var refreshes = 0
        var refreshRead: suspend () -> DownloadCatalog = read
        override suspend fun catalog(videoId: String): DownloadCatalog { reads++; return read() }
        override suspend fun refresh(videoId: String): DownloadCatalog { refreshes++; return refreshRead() }
    }

    /** Gate after the database commit, outside its lock, to exercise the cache-publication boundary. */
    private class CommittedResolutionGate(private val delegate: DownloadRepository) : DownloadRepository by delegate {
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private var armed = false
        fun arm() { armed = true }

        override suspend fun <T> transact(block: suspend DownloadSession.() -> T): T {
            var resolved = false
            val result = delegate.transact {
                val session = this
                block(object : DownloadSession by session {
                    override suspend fun updateComponent(component: InputComponent) {
                        session.updateComponent(component)
                        if (component.resourceIdentity != null) resolved = true
                    }
                })
            }
            if (resolved && armed) {
                armed = false
                committed.complete(Unit)
                release.await()
            }
            return result
        }
    }
}
