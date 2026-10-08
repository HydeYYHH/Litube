package com.hhst.youtubelite.downloader.engine

import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.CompletionKind
import com.hhst.youtubelite.downloader.core.DownloadConfig
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadSettings
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.SeqIdFactory
import com.hhst.youtubelite.downloader.core.request
import com.hhst.youtubelite.downloader.data.InMemoryDownloadRepository
import com.hhst.youtubelite.downloader.io.DownloadDirectories
import com.hhst.youtubelite.downloader.io.DownloadFinalizerImpl
import com.hhst.youtubelite.downloader.net.DownloadHttpClients
import com.hhst.youtubelite.downloader.net.DownloadTransportImpl
import com.hhst.youtubelite.downloader.io.DownloadPublisherImpl
import com.hhst.youtubelite.downloader.io.LocalPublishBackend
import com.hhst.youtubelite.downloader.resolve.DownloadCatalog
import com.hhst.youtubelite.downloader.resolve.DownloadCatalogSource
import com.hhst.youtubelite.downloader.resolve.DownloadResolverImpl
import com.hhst.youtubelite.downloader.resolve.audioFormat
import com.hhst.youtubelite.downloader.resolve.muxedFormat
import com.hhst.youtubelite.downloader.resolve.subtitle
import com.grack.nanojson.JsonObject
import org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile
import org.schabi.newpipe.extractor.services.youtube.streams.RequestPlan
import org.schabi.newpipe.extractor.services.youtube.streams.YoutubeSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DownloadEngineE2ETest {
    private lateinit var server: MockWebServer
    private lateinit var root: File
    private val clients = mutableListOf<OkHttpClient>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        root = File(System.getProperty("java.io.tmpdir"), "dl-e2e-${System.nanoTime()}").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        clients.forEach { client ->
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
        runCatching { server.shutdown() }
        root.deleteRecursively()
    }

    @Test
    fun singleVideo_downloadResumeFinish_publishedUriOpenable() = runBlocking {
        val media = load("downloader/media/avc_aac.mp4")
        val firstDone = java.util.concurrent.CountDownLatch(1)
        val allowRest = AtomicBoolean(false)
        server.dispatcher = mediaDispatcher(media) { request ->
            val range = request.getHeader("Range")
            if (range != null && range.startsWith("bytes=0-")) firstDone.countDown()
            null
        }
        val env = env(
            catalog = muxedCatalog(mediaUrl("/v.mp4", media.size.toLong(), 18)),
            chunkBytes = 64,
            interceptor = Interceptor { chain ->
                val range = chain.request().header("Range").orEmpty()
                if (range.startsWith("bytes=") && !range.startsWith("bytes=0-")) {
                    var waited = 0
                    while (!allowRest.get() && !chain.call().isCanceled() && waited < 8_000) {
                        Thread.sleep(20)
                        waited += 20
                    }
                    if (chain.call().isCanceled()) throw IOException("Canceled")
                }
                chain.proceed(chain.request())
            },
        )
        val taskId = env.coordinator.enqueue(request("a"), "s1").created.single().taskId
        val first = async(Dispatchers.IO) { env.engine.run(taskId) }
        assertTrue(firstDone.await(5, TimeUnit.SECONDS))
        // The request arriving does not mean the chunk is checkpointed yet:
        // poll until the first verified chunk lands, then pause mid-transfer.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val count = env.repo.transact { snapshot(taskId) }!!.assets.single().components
                .flatMap { it.chunks }.count { it.verified }
            if (count > 0) break
            delay(20)
        }
        env.coordinator.pause(DownloadTarget.Task(taskId))
        first.cancel()
        runCatching { first.await() }
        val paused = env.repo.transact { snapshot(taskId) }!!
        assertEquals(DownloadStatus.PAUSED, paused.task.status)
        val kept = paused.assets.single().components.flatMap { it.chunks }.filter { it.verified }
        assertTrue(
            "status=${paused.task.status} phase=${paused.task.phase} gen=${paused.task.executionGeneration} " +
                "err=${paused.task.errorMessage}",
            kept.isNotEmpty(),
        )
        allowRest.set(true)
        env.coordinator.resume(DownloadTarget.Task(taskId))
        env.engine.run(taskId)
        val done = env.repo.transact { snapshot(taskId) }!!
        assertEquals(
            "phase=${done.task.phase} status=${done.task.status} err=${done.task.errorMessage} " +
                done.assets.map { "${it.asset.kind}:${it.asset.errorMessage}" },
            DownloadPhase.COMPLETE,
            done.task.phase,
        )
        assertEquals(
            "phase=${done.task.phase} status=${done.task.status} err=${done.task.errorMessage} " +
                done.assets.map { "${it.asset.kind}:${it.asset.errorMessage}" },
            CompletionKind.FULL,
            done.task.completion,
        )
        val uri = done.assets.single().asset.publishedUri
        assertNotNull(uri)
        assertTrue(uri!!.startsWith("content://"))
        val file = env.backend.fileFor(uri)
        assertNotNull(file)
        assertTrue(file!!.isFile && file.length() > 0L)
    }

    @Test
    fun audioOnly_muxesM4a() = runBlocking {
        val media = load("downloader/media/aac.m4a")
        server.dispatcher = mediaDispatcher(media)
        val env = env(
            catalog = DownloadCatalog(
                videoId = "abcdefghijk",
                title = "A",
                durationSec = 1,
                formats = listOf(
                    audioFormat(clen = media.size.toLong()).copy(url = mediaUrl("/a.m4a", media.size.toLong(), 140)),
                ),
            ),
        )
        val taskId = env.coordinator.enqueue(
            request("a", config = DownloadConfig(audioOnly = true)),
            "s1",
        ).created.single().taskId
        env.engine.run(taskId)
        val done = env.repo.transact { snapshot(taskId) }!!
        assertEquals(
            "status=${done.task.status} err=${done.task.errorMessage} " +
                done.assets.map { "${it.asset.kind}:${it.asset.errorMessage}" },
            CompletionKind.FULL,
            done.task.completion,
        )
        assertEquals(AssetKind.AUDIO, done.assets.single().asset.kind)
        val uri = done.assets.single().asset.publishedUri!!
        assertTrue(env.backend.fileFor(uri)!!.name.endsWith(".m4a"))
    }

    @Test
    fun attachmentFailure_isPartialAndRetryFailedOnly() = runBlocking {
        val media = load("downloader/media/avc_aac.mp4")
        val session = YoutubeSession("subtitle-test", YoutubeSession.Account.ANONYMOUS,
            0, null, null, null, "visitor", "browser-UA", 1, null, "test", JsonObject(), { "" })
        val subtitlePlan = RequestPlan(session, ClientProfile.WEB, RequestPlan.Protocol.HTTPS, RequestPlan.Range.NONE, false)
        server.dispatcher = mediaDispatcher(media) { request ->
            if ("timedtext" in request.path.orEmpty()) MockResponse().setResponseCode(404) else null
        }
        val env = env(
            catalog = muxedCatalog(mediaUrl("/v.mp4", media.size.toLong(), 18)).copy(
                subtitles = listOf(subtitle("en").copy(url = server.url("/timedtext").toString(), requestPlan = subtitlePlan)),
            ),
        )
        val taskId = env.coordinator.enqueue(
            request("a", config = DownloadConfig(includeSubtitle = true, subtitleLanguage = "en")),
            "s1",
        ).created.single().taskId
        env.engine.run(taskId)
        val snap = env.repo.transact { snapshot(taskId) }!!
        assertEquals(
            "status=${snap.task.status} err=${snap.task.errorMessage} " +
                snap.assets.map { "${it.asset.kind}:${it.asset.published}:${it.asset.errorMessage}" },
            CompletionKind.PARTIAL,
            snap.task.completion,
        )
        val video = snap.assets.first { it.asset.kind == AssetKind.VIDEO }.asset
        val sub = snap.assets.first { it.asset.kind == AssetKind.SUBTITLE }.asset
        assertTrue(video.published)
        assertTrue(sub.failed)
        // A system job restored after failure must leave the terminal state intact.
        val requestsBeforeRestore = server.requestCount
        env.engine.run(taskId)
        val restored = env.repo.transact { snapshot(taskId) }!!
        assertEquals(DownloadStatus.FAILED, restored.task.status)
        assertEquals(DownloadPhase.COMPLETE, restored.task.phase)
        assertEquals(video.publishedUri, restored.assets.first { it.asset.kind == AssetKind.VIDEO }.asset.publishedUri)
        assertEquals(requestsBeforeRestore, server.requestCount)
        server.dispatcher = mediaDispatcher(media) { request ->
            if ("timedtext" in request.path.orEmpty()) {
                MockResponse().setResponseCode(200).setBody("WEBVTT\n")
            } else {
                null
            }
        }
        env.coordinator.retryFailed(DownloadTarget.Task(taskId))
        env.engine.run(taskId)
        val after = env.repo.transact { snapshot(taskId) }!!
        assertTrue(after.assets.first { it.asset.kind == AssetKind.VIDEO }.asset.published)
        assertEquals(video.publishedUri, after.assets.first { it.asset.kind == AssetKind.VIDEO }.asset.publishedUri)
        assertTrue(after.assets.first { it.asset.kind == AssetKind.SUBTITLE }.asset.published)
        assertEquals(CompletionKind.FULL, after.task.completion)
    }

    private fun env(
        catalog: DownloadCatalog,
        chunkBytes: Long = DownloadSettings.CHUNK_BYTES,
        interceptor: Interceptor? = null,
    ): Env {
        val repo = InMemoryDownloadRepository()
        val dirs = DownloadDirectories(File(root, "work"))
        val backend = LocalPublishBackend(File(root, "out"))
        val publisher = DownloadPublisherImpl(backend)
        val client = DownloadHttpClients.create().newBuilder()
            .readTimeout(8, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .apply { interceptor?.let { addNetworkInterceptor(it) } }
            .build()
        clients += client
        val transport = DownloadTransportImpl(
            client = client,
            chunkBytes = chunkBytes,
            sleeper = { },
        )
        val coordinator = DownloadCoordinator(
            repository = repo,
            transport = transport,
            publisher = publisher,
            ids = SeqIdFactory(),
        )
        val resolver = DownloadResolverImpl(
            coordinator = coordinator,
            repository = repo,
            catalogs = object : DownloadCatalogSource {
                override suspend fun catalog(videoId: String) = catalog
                override suspend fun refresh(videoId: String) = catalog
            },
        )
        val engine = DownloadEngine(
            coordinator = coordinator,
            repository = repo,
            resolver = resolver,
            transport = transport,
            finalizer = DownloadFinalizerImpl(),
            publisher = publisher,
            directories = dirs,
        )
        return Env(coordinator, repo, engine, backend)
    }

    private fun mediaDispatcher(
        media: ByteArray,
        extra: (RecordedRequest) -> MockResponse? = { null },
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            extra(request)?.let { return it }
            val range = request.getHeader("Range")
            if (range.isNullOrBlank()) {
                return MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Length", media.size.toString())
                    .setBody(Buffer().write(media))
            }
            val spec = range.removePrefix("bytes=")
            val start = spec.substringBefore('-').toLong().coerceAtLeast(0L)
            val endRaw = spec.substringAfter('-')
            val end = (if (endRaw.isBlank()) media.size - 1L else endRaw.toLong())
                .coerceAtMost(media.size - 1L)
            if (start >= media.size) {
                return MockResponse()
                    .setResponseCode(416)
                    .addHeader("Content-Range", "bytes */${media.size}")
            }
            val slice = media.copyOfRange(start.toInt(), (end + 1).toInt())
            return MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes $start-$end/${media.size}")
                .addHeader("Content-Length", slice.size.toString())
                .setBody(Buffer().write(slice))
        }
    }

    private fun muxedCatalog(url: String) = DownloadCatalog(
        videoId = "abcdefghijk",
        title = "Clip",
        durationSec = 1,
        formats = listOf(muxedFormat().copy(url = url, itag = 18)),
    )

    private fun mediaUrl(path: String, bytes: Long, itag: Int): String =
        server.url(path).newBuilder()
            .addQueryParameter("id", "mediaid")
            .addQueryParameter("itag", itag.toString())
            .addQueryParameter("clen", bytes.toString())
            .addQueryParameter("c", "ANDROID")
            .build()
            .toString()

    private fun load(path: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)).use { it.readBytes() }

    private class Env(
        val coordinator: DownloadCoordinator,
        val repo: InMemoryDownloadRepository,
        val engine: DownloadEngine,
        val backend: LocalPublishBackend,
    )
}
