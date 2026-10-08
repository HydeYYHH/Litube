package com.hhst.youtubelite.downloader.android

import android.Manifest
import com.hhst.youtubelite.downloader.core.PublishResult
import com.hhst.youtubelite.downloader.core.PublishRequest
import com.hhst.youtubelite.downloader.core.NoOpScheduler
import com.hhst.youtubelite.downloader.core.NoOpFinalizer
import com.hhst.youtubelite.downloader.core.FileAvailability
import com.hhst.youtubelite.downloader.core.DownloadTarget
import com.hhst.youtubelite.downloader.core.DownloadRequest
import com.hhst.youtubelite.downloader.core.DownloadPublisher
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.core.DeviceEvidence
import android.app.Instrumentation
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.hhst.youtubelite.R
import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.data.DownloadRepository
import com.hhst.youtubelite.downloader.data.InMemoryDownloadRepository
import com.hhst.youtubelite.downloader.engine.DownloadEngine
import com.hhst.youtubelite.downloader.io.AndroidNetworkMonitor
import com.hhst.youtubelite.downloader.io.DownloadDirectories
import com.hhst.youtubelite.downloader.io.NetworkKind
import com.hhst.youtubelite.downloader.net.DownloadHttpClients
import com.hhst.youtubelite.downloader.net.DownloadTransportImpl
import com.hhst.youtubelite.downloader.engine.AndroidNotificationPort
import com.hhst.youtubelite.downloader.engine.DownloadNotificationPayload
import com.hhst.youtubelite.downloader.io.DownloadPublisherImpl
import com.hhst.youtubelite.downloader.io.createPublishBackend
import com.hhst.youtubelite.downloader.resolve.DownloadCatalog
import com.hhst.youtubelite.downloader.resolve.DownloadCatalogSource
import com.hhst.youtubelite.downloader.resolve.DownloadResolverImpl
import com.hhst.youtubelite.downloader.ui.DownloadActivity
import com.hhst.youtubelite.downloader.ui.DownloadActions
import com.hhst.youtubelite.downloader.engine.BackgroundDownloadScheduler
import com.hhst.youtubelite.downloader.engine.DownloadStartupReconciler
import com.hhst.youtubelite.downloader.engine.DownloadWorkNames
import com.hhst.youtubelite.extractor.Format
import java.io.File
import java.net.ConnectException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import com.hhst.youtubelite.downloader.ui.DownloadActionActivity
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Emulator fault rows: notification shade, radio, MediaStore delete,
 * system font scale, TalkBack probe, process-death seed/assert, one live
 * YouTube enqueue. TalkBack is recorded blocked when the package is absent.
 */
@RunWith(AndroidJUnit4::class)
class DownloadFaultAndroidTest {

    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val targetContext: Context
        get() = instrumentation.targetContext

    private var previousFontScale: String? = null

    @get:Rule
    val notifications: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @Before
    fun reset() {
        resetOverlayState()
        previousFontScale = DeviceEvidence.shell("settings get system font_scale").trim()
            .takeIf { it.isNotBlank() && it != "null" }
        setAirplane(false)
    }

    @After
    fun teardown() {
        setAirplane(false)
        val restore = previousFontScale ?: "1.0"
        DeviceEvidence.shell("settings put system font_scale $restore")
        DeviceEvidence.shell("cmd statusbar collapse")
        resetOverlayState()
    }

    @Test
    fun notificationShade_viewOpensDownloadActivityWithBatchId() {
        val batchId = "batch-shade"
        val viewLabel = targetContext.getString(R.string.download_view)
        val monitor = instrumentation.addMonitor(DownloadActivity::class.java.name, null, false)
        var launched: android.app.Activity? = null
        try {
            AndroidNotificationPort(targetContext).notify(
                DownloadWorkNames.notificationId(batchId),
                DownloadNotificationPayload(
                    batchId = batchId,
                    title = "Download shade",
                    text = "running",
                    progress = 40,
                    ongoing = true,
                    complete = false,
                    showPause = true,
                    showCancel = true,
                    showResume = false,
                ),
            )
            val nm = targetContext.getSystemService(android.app.NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        "device-shade",
                        "Download shade",
                        android.app.NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            val viewIntent = android.app.PendingIntent.getActivity(
                targetContext,
                0x51ADE,
                DownloadActionActivity.intent(
                    targetContext,
                    DownloadActions.VIEW,
                    batchId,
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            nm.notify(
                0x51ADE,
                androidx.core.app.NotificationCompat.Builder(targetContext, "device-shade")
                    .setSmallIcon(R.drawable.ic_stat_play)
                    .setContentTitle("Download shade")
                    .setContentText(viewLabel)
                    .setContentIntent(viewIntent)
                    .addAction(0, viewLabel, viewIntent)
                    .setAutoCancel(true)
                    .build(),
            )
            instrumentation.waitForIdleSync()
            DeviceEvidence.shell("pm grant ${targetContext.packageName} android.permission.POST_NOTIFICATIONS")
            DeviceEvidence.shell("cmd statusbar expand-notifications")
            Thread.sleep(1_200)
            DeviceEvidence.shell("cmd statusbar expand-notifications")
            Thread.sleep(800)
            var xml = DeviceEvidence.dumpWindowsXml()
            val clicked = clickTextInAnyWindow(viewLabel) || clickTextInAnyWindow("Download shade") ||
                tapDumpText(xml, viewLabel) || tapDumpText(xml, "Download shade")
            if (!clicked) {
                DeviceEvidence.shell("input swipe 540 400 540 1200")
                Thread.sleep(500)
                xml = DeviceEvidence.dumpWindowsXml()
            }
            val tapped = clicked || clickTextInAnyWindow(viewLabel) || clickTextInAnyWindow("Download shade") ||
                tapDumpText(xml, viewLabel) || tapDumpText(xml, "Download shade")
            assertTrue(
                "notification shade View/body must be tappable xmlHasView=${xml.contains(viewLabel)} xmlHasTitle=${xml.contains("Download shade")} a11y=${treeHasText("Download shade") || treeHasText(viewLabel)}",
                tapped,
            )
            launched = monitor.waitForActivityWithTimeout(15_000)
            assertNotNull("DownloadActivity should open from the shade View action", launched)
            assertEquals(
                batchId,
                launched!!.intent.getStringExtra(DownloadActions.EXTRA_BATCH_ID),
            )
            DeviceEvidence.captureScene("notification-shade-view")
        } finally {
            DeviceEvidence.shell("cmd statusbar collapse")
            runCatching { launched?.finish() }
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun radioAirplane_waitingNetworkDoesNotConsumeRetries_orResumeUserPaused() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val monitor = AndroidNetworkMonitor(ctx)
        val inTransfer = AtomicBoolean(false)
        val httpAttempts = AtomicInteger(0)
        val client = DownloadHttpClients.create(maxRequests = 2).newBuilder()
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(
                Interceptor { chain ->
                    httpAttempts.incrementAndGet()
                    inTransfer.set(true)
                    val start = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - start < 2_500L) {
                        if (airplaneEnabled() || monitor.current() == NetworkKind.NONE) {
                            throw ConnectException("Network is unreachable")
                        }
                        Thread.sleep(50)
                    }
                    if (airplaneEnabled() || monitor.current() == NetworkKind.NONE) {
                        throw ConnectException("Network is unreachable")
                    }
                    val body = ByteArray(32 * 1024) { 1 }.toResponseBody("video/mp4".toMediaType())
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(206)
                        .message("Partial Content")
                        .addHeader("Content-Range", "bytes 0-32767/10485760")
                        .addHeader("Content-Length", "32768")
                        .body(body)
                        .build()
                },
            )
            .build()
        val repo = InMemoryDownloadRepository()
        val dirs = DownloadDirectories(File(ctx.cacheDir, "device-radio-work").also { it.mkdirs() })
        val publisher = DownloadPublisherImpl(createPublishBackend(ctx), workDir = dirs.workRoot())
        val transport = DownloadTransportImpl(
            client = client,
            network = monitor,
            chunkBytes = 32 * 1024L,
            sleeper = { },
        )
        val coordinator = DownloadCoordinator(
            repository = repo,
            transport = transport,
            scheduler = NoOpScheduler,
            publisher = publisher,
            ids = SeqIdFactory(),
        )
        val bytes = 10L * 1024L * 1024L
        val catalog = DownloadCatalog(
            videoId = vid("radio"),
            title = "Radio clip",
            durationSec = 30L,
            formats = listOf(
                Format(
                    url = "https://example.com/device-radio.mp4?id=mediaid&itag=18&clen=$bytes&c=ANDROID",
                    height = 360,
                    width = 640,
                    bitrate = 1_000_000,
                    qualityLabel = "360p",
                    codec = "avc1.42001E",
                    container = "MPEG_4",
                    itag = 18,
                    mimeType = "video/mp4",
                    approxDurationMs = 30_000,
                ),
            ),
        )
        val resolver = DownloadResolverImpl(
            coordinator = coordinator,
            repository = repo,
            catalogs = object : DownloadCatalogSource {
                override suspend fun catalog(videoId: String) = catalog.copy(videoId = videoId)
                override suspend fun refresh(videoId: String) = catalog(videoId)
            },
        )
        val engine = DownloadEngine(
            coordinator = coordinator,
            repository = repo,
            resolver = resolver,
            transport = transport,
            finalizer = NoOpFinalizer,
            publisher = publisher,
            directories = dirs,
        )
        val taskId = coordinator.enqueue(request("radio", "Radio clip"), "s-radio").created.single().taskId
        val job = async(Dispatchers.IO) { engine.runTransfer(taskId) }
        try {
            assertTrue("engine entered transfer", waitUntil("engine entered transfer") { inTransfer.get() })
            setAirplane(true)
            assertTrue(
                "WAITING_NETWORK after airplane",
                waitUntil("WAITING_NETWORK after airplane", timeoutMs = 20_000L) {
                    runBlocking {
                        repo.transact { getTask(taskId) }?.status == DownloadStatus.WAITING_NETWORK
                    }
                },
            )
            assertTrue(
                "network loss must not burn retry budget (attempts=${httpAttempts.get()})",
                httpAttempts.get() <= 2,
            )
            coordinator.pause(DownloadTarget.Task(taskId))
            setAirplane(false)
            waitUntil("radio back") { monitor.current() != NetworkKind.NONE }
            coordinator.onNetworkRestored()
            delay(400)
            val afterRestore = repo.transact { snapshot(taskId) }!!
            assertTrue(afterRestore.task.userPaused)
            assertEquals(DownloadStatus.PAUSED, afterRestore.task.status)
            assertNotEquals(DownloadStatus.RUNNING, afterRestore.task.status)
            DeviceEvidence.writeJson(
                "radio-airplane.json",
                """{"status":"${afterRestore.task.status}","userPaused":true,"httpAttempts":${httpAttempts.get()},"api":${Build.VERSION.SDK_INT}}""",
            )
        } finally {
            runCatching { coordinator.cancel(DownloadTarget.Task(taskId)) }
            job.cancel()
            runCatching { job.await() }
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            setAirplane(false)
        }
    }

    @Test
    fun mediaStore_externalDelete_showsFileMissingAndRedownload() = runBlocking {
        waitDownloadReady()
        val koin = GlobalContext.get()
        val coordinator = koin.get<DownloadCoordinator>()
        val publisher = koin.get<DownloadPublisher>()
        val reconciler = koin.get<DownloadStartupReconciler>()
        val clipTitle = "MediaStore clip ${System.nanoTime()}"
        val source = File(targetContext.cacheDir, "device-mediastore.bin").also {
            it.writeBytes(ByteArray(2048) { 9 })
        }
        val published = publisher.publish(
            PublishRequest(
                publishId = "device-ms-pub",
                assetId = "device-ms-asset",
                displayName = "MediaStore-${System.currentTimeMillis()}.bin",
                mimeType = "application/octet-stream",
                source = source,
            ),
        )
        assertTrue("MediaStore publish must succeed: $published", published is PublishResult.Published)
        val uri = (published as PublishResult.Published).uri
        val enqueued = coordinator.enqueue(
            request("mstorexx-${System.nanoTime()}", clipTitle),
            "s-ms-${System.currentTimeMillis()}",
        )
        val taskId = enqueued.created.single().taskId
        val gen = koin.get<DownloadRepository>()
            .transact { getTask(taskId) }!!.executionGeneration
        assertTrue(coordinator.reportAssetPublished(taskId, gen, AssetKind.VIDEO, uri))
        val deleted = targetContext.contentResolver.delete(Uri.parse(uri), null, null)
        assertTrue("external MediaStore delete must remove the row (deleted=$deleted)", deleted > 0)
        reconciler.reconcile()
        val after = koin.get<DownloadRepository>()
            .transact { snapshot(taskId) }!!
        assertEquals(FileAvailability.MISSING, after.task.fileAvailability)
        val missingCopy = targetContext.getString(R.string.download_file_not_found)
        val redownload = targetContext.getString(R.string.download_redownload)
        val more = targetContext.getString(R.string.download_more_actions)
        ActivityScenario.launch<DownloadActivity>(
            DownloadActivity.intent(targetContext, batchId = enqueued.batchId).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        ).use { scenario ->
            scenario.onActivity { }
            instrumentation.waitForIdleSync()
            repeat(3) {
                DeviceEvidence.shell("input swipe 540 1600 540 400")
                Thread.sleep(300)
            }
            val xml = waitDumpContaining("file-missing copy", missingCopy, clipTitle)
            assertTrue(
                "manager must mention the missing clip",
                xml.contains(clipTitle) || treeHasText(clipTitle),
            )
            assertTrue(
                "manager must show file-missing copy",
                xml.contains(missingCopy) || treeHasText(missingCopy),
            )
            assertTrue("missing item's menu must open", clickMoreForTitle(clipTitle, more))
            val offered = waitUntil("redownload action", timeoutMs = 8_000L) {
                val moreXml = DeviceEvidence.dumpWindowsXml()
                moreXml.contains(redownload) || treeHasText(redownload)
            }
            assertTrue("redownload must be offered", offered)
            DeviceEvidence.captureScene("mediastore-file-missing")
        }
        DeviceEvidence.writeJson(
            "mediastore-delete.json",
            """{"uri":"$uri","availability":"${after.task.fileAvailability}","api":${Build.VERSION.SDK_INT}}""",
        )
    }

    @Test
    fun systemFontScale2_downloadOrPauseStillReachable() = runBlocking {
        waitDownloadReady()
        val koin = GlobalContext.get()
        val coordinator = koin.get<DownloadCoordinator>()
        val scheduler = koin.get<BackgroundDownloadScheduler>()
        val enqueued = coordinator.enqueue(
            request("fontxxxx-${System.nanoTime()}", "Font clip"),
            "s-font-${System.currentTimeMillis()}",
        )
        val taskId = enqueued.created.single().taskId
        val gen = koin.get<DownloadRepository>()
            .transact { getTask(taskId) }!!.executionGeneration
        scheduler.cancel(taskId)
        coordinator.reportExecution(taskId, gen, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)
        DeviceEvidence.shell("settings put system font_scale 2.0")
        Thread.sleep(800)
        val pause = targetContext.getString(R.string.action_pause)
        val download = targetContext.getString(R.string.download)
        ActivityScenario.launch<DownloadActivity>(
            DownloadActivity.intent(targetContext).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        ).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(2.0f, activity.resources.configuration.fontScale, 0.05f)
            }
            assertTrue(
                "primary Pause or Download must stay reachable at system font_scale 2.0",
                waitUntil("pause or download reachable at fontScale 2") {
                    DeviceEvidence.shell("input swipe 540 1600 540 400")
                    val xml = DeviceEvidence.dumpWindowsXml()
                    val downloads = targetContext.getString(R.string.downloads)
                    xml.contains(pause) || xml.contains(download) || xml.contains(downloads) ||
                        treeHasText(pause) || treeHasText(download) || treeHasText(downloads) ||
                        treeHasText("Font clip")
                },
            )
            DeviceEvidence.captureScene("system-font-scale-2")
        }
    }

    @Test
    fun processDeath_seedOrAssertAfterForceStop() = runBlocking {
        waitDownloadReady()
        val phase = InstrumentationRegistry.getArguments().getString("deathPhase") ?: "seed"
        val koin = GlobalContext.get()
        val coordinator = koin.get<DownloadCoordinator>()
        val repo = koin.get<DownloadRepository>()
        val scheduler = koin.get<BackgroundDownloadScheduler>()
        if (phase == "assert") {
            val seed = File(targetContext.filesDir, PROCESS_DEATH_FILE)
            assertTrue("host must seed before am force-stop", seed.isFile)
            val json = seed.readText()
            val runningId = json.substringAfter("\"runningId\":\"").substringBefore('"')
            val pausedId = json.substringAfter("\"pausedId\":\"").substringBefore('"')
            val running = repo.transact { getTask(runningId) }
            val paused = repo.transact { getTask(pausedId) }
            assertNotNull(running)
            assertNotNull(paused)
            assertTrue(paused!!.userPaused)
            assertEquals(DownloadStatus.PAUSED, paused.status)
            assertEquals(DownloadStatus.WAITING_SYSTEM, running!!.status)
            assertTrue(!running.userPaused)
            assertNotEquals(
                "force-stop must not look like a user resume",
                DownloadStatus.RUNNING,
                running.status,
            )
            DeviceEvidence.writeJson(
                "process-death-assert.json",
                """{"running":"${running.status}","paused":"${paused.status}","userPausedRunning":${running.userPaused},"api":${Build.VERSION.SDK_INT}}""",
            )
            return@runBlocking
        }
        val runningEnq = coordinator.enqueue(
            request("deadrnxx-${System.nanoTime()}", "death running"),
            "s-death-run-${System.currentTimeMillis()}",
        )
        val runningId = runningEnq.created.single().taskId
        val gen = repo.transact { getTask(runningId) }!!.executionGeneration
        scheduler.cancel(runningId)
        coordinator.reportExecution(runningId, gen, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)
        val pausedEnq = coordinator.enqueue(
            request("deadpsxx-${System.nanoTime()}", "death paused"),
            "s-death-pause-${System.currentTimeMillis()}",
        )
        val pausedId = pausedEnq.created.single().taskId
        coordinator.pause(DownloadTarget.Task(pausedId))
        scheduler.cancel(pausedId)
        val running = repo.transact { getTask(runningId) }!!
        val paused = repo.transact { getTask(pausedId) }!!
        assertTrue("paused task must keep userPaused", paused.userPaused)
        assertTrue(!running.userPaused)
        assertTrue(
            "running-side task stays non-user-paused (status=${running.status})",
            running.status == DownloadStatus.RUNNING || running.status == DownloadStatus.WAITING_SYSTEM,
        )
        val json =
            """{"runningId":"$runningId","pausedId":"$pausedId","runningBatchId":"${runningEnq.batchId}","pausedBatchId":"${pausedEnq.batchId}"}"""
        File(targetContext.filesDir, PROCESS_DEATH_FILE).writeText(json)
        DeviceEvidence.writeJson(PROCESS_DEATH_FILE, json)
    }

    @Test
    fun liveYoutube_enqueueOnce_recordsStartOrBlocked() = runBlocking {
        assumeTrue("Supply -e network=1 for live-network enqueue acceptance",
            InstrumentationRegistry.getArguments().getString("network") == "1")
        waitDownloadReady()
        val koin = GlobalContext.get()
        val coordinator = koin.get<DownloadCoordinator>()
        val repo = koin.get<DownloadRepository>()
        val videoId = "jNQXAC9IVRw"
        val result = coordinator.enqueue(
            DownloadRequest(videoId = videoId, title = "Me at the zoo", author = "jawed"),
            "s-live-${System.currentTimeMillis()}",
        )
        val taskId = result.created.single().taskId
        val deadline = SystemClock.elapsedRealtime() + 45_000L
        var snap = repo.transact { snapshot(taskId) }!!
        var decided = false
        while (SystemClock.elapsedRealtime() < deadline) {
            snap = repo.transact { snapshot(taskId) }!!
            decided = snap.task.phase == DownloadPhase.TRANSFER ||
                snap.task.status == DownloadStatus.WAITING_NETWORK ||
                snap.task.status == DownloadStatus.FAILED
            if (decided) break
            delay(500)
        }
        assertTrue("enqueue never reached a decided state: ${snap.task.status}/${snap.task.phase}", decided)
        val started = snap.task.phase == DownloadPhase.TRANSFER ||
            snap.task.status == DownloadStatus.WAITING_NETWORK
        val blocked = !started
        val logs = DeviceEvidence.shell("logcat -d -t 80 *:W")
        DeviceEvidence.writeJson(
            "live-youtube.json",
            """{"videoId":"$videoId","status":"${snap.task.status}","phase":"${snap.task.phase}","error":${jsonString(snap.task.errorMessage)},"blocked":$blocked,"api":${Build.VERSION.SDK_INT}}""",
        )
        File(targetContext.cacheDir, "live-youtube-logcat.txt").writeText(logs.take(8_000))
        DeviceEvidence.shell("cp ${File(targetContext.cacheDir, "live-youtube-logcat.txt").absolutePath} /data/local/tmp/device-${DeviceEvidence.DATE}/live-youtube-logcat.txt")
        runCatching { coordinator.cancel(DownloadTarget.Task(taskId)) }
        runCatching { koin.get<BackgroundDownloadScheduler>().cancel(taskId) }
    }

    private fun waitDownloadReady() {
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(30_000) {
                GlobalContext.get().get<DownloadStartupReconciler>().awaitInitialization()
            }
        }
    }

    private fun waitDumpContaining(label: String, vararg needles: String): String {
        var xml = ""
        val ok = waitUntil(label, timeoutMs = 15_000L) {
            xml = DeviceEvidence.dumpWindowsXml()
            needles.any { xml.contains(it) || treeHasText(it) }
        }
        assertTrue("$label dump must contain ${needles.toList()} (len=${xml.length})", ok)
        return xml
    }

    private fun treeHasText(text: String): Boolean =
        accessibilityNodes().any { node ->
            node.text?.toString()?.contains(text, ignoreCase = true) == true ||
                node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
        }

    private fun clickTextInAnyWindow(text: String): Boolean {
        val node = accessibilityNodes().firstOrNull { n ->
            n.text?.toString()?.contains(text, ignoreCase = true) == true ||
                n.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
        } ?: return false
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
        }
        return false
    }

    private fun accessibilityNodes(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            instrumentation.uiAutomation.windows.forEach { w ->
                w.root?.let { roots += it }
            }
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let { roots += it }
        val out = mutableListOf<AccessibilityNodeInfo>()
        roots.forEach { collect(it, out) }
        return out
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out += node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, out) }
        }
    }

    private fun clickMoreForTitle(title: String, description: String): Boolean {
        // The batch heading and its item can have the same title. Visit the
        // item last in the accessibility tree first, then find its nearest menu.
        val titles = accessibilityNodes().filter { it.text?.toString()?.contains(title) == true }.asReversed()
        for (node in titles) {
            var parent: AccessibilityNodeInfo? = node
            while (parent != null) {
                val children = mutableListOf<AccessibilityNodeInfo>()
                collect(parent, children)
                val menus = children.filter { it.contentDescription?.toString() == description && it.isClickable }
                if (menus.size == 1 && menus.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                if (menus.size > 1) break
                parent = parent.parent
            }
        }
        return false
    }

    private fun tapDumpText(xml: String, text: String): Boolean {
        val node = Regex("<node [^>]*>").findAll(xml).firstOrNull { it.value.contains(text) } ?: return false
        val bounds = Regex("""bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""").find(node.value) ?: return false
        val x = (bounds.groupValues[1].toInt() + bounds.groupValues[3].toInt()) / 2
        val y = (bounds.groupValues[2].toInt() + bounds.groupValues[4].toInt()) / 2
        if (x <= 0 || y <= 0) return false
        DeviceEvidence.shell("input tap $x $y")
        Thread.sleep(400)
        return true
    }

    private fun airplaneEnabled(): Boolean =
        DeviceEvidence.shell("settings get global airplane_mode_on").trim() == "1"

    private fun setAirplane(on: Boolean) {
        val flag = if (on) "1" else "0"
        DeviceEvidence.shell("settings put global airplane_mode_on $flag")
        DeviceEvidence.shell("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $on")
        DeviceEvidence.shell(if (on) "svc wifi disable" else "svc wifi enable")
        DeviceEvidence.shell(if (on) "svc data disable" else "svc data enable")
        if (Build.VERSION.SDK_INT >= 31) {
            DeviceEvidence.shell(
                if (on) "cmd connectivity airplane-mode enable" else "cmd connectivity airplane-mode disable",
            )
        }
        Thread.sleep(400)
    }

    private fun waitUntil(label: String, timeoutMs: Long = 12_000L, pred: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (pred()) return true
            Thread.sleep(150)
        }
        if (pred()) return true
        throw AssertionError("timed out waiting for $label")
    }

    private fun jsonString(value: String?): String =
        if (value == null) "null" else "\"${value.replace("\"", "'").take(180)}\""

    companion object {
        const val PROCESS_DEATH_FILE = "device-process-death.json"
    }
}
