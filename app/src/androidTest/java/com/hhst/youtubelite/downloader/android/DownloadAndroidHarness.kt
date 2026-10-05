package com.hhst.youtubelite.downloader.android

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.downloader.core.AssetKind
import com.hhst.youtubelite.downloader.core.DownloadConfig
import com.hhst.youtubelite.downloader.core.DownloadCoordinator
import com.hhst.youtubelite.downloader.core.DownloadPhase
import com.hhst.youtubelite.downloader.core.DownloadRequest
import com.hhst.youtubelite.downloader.core.DownloadStatus
import com.hhst.youtubelite.downloader.core.FileAvailability
import com.hhst.youtubelite.downloader.core.IdFactory
import com.hhst.youtubelite.downloader.core.NoOpPublisher
import com.hhst.youtubelite.downloader.core.NoOpScheduler
import com.hhst.youtubelite.downloader.core.NoOpTransport
import com.hhst.youtubelite.downloader.data.InMemoryDownloadRepository
import com.hhst.youtubelite.downloader.pip.PipAutoEnter
import com.hhst.youtubelite.downloader.resolve.DownloadCatalog
import com.hhst.youtubelite.downloader.resolve.DownloadCatalogSource
import com.hhst.youtubelite.downloader.share.DownloadShareOnce
import com.hhst.youtubelite.downloader.ui.DownloadViewModel
import com.hhst.youtubelite.extractor.Format
import com.hhst.youtubelite.extractor.Subtitle
import java.io.File
import java.io.FileInputStream

internal class SeqIdFactory : IdFactory {
    private val counts = mutableMapOf<String, Int>()
    override fun next(kind: String): String {
        val n = (counts[kind] ?: 0) + 1
        counts[kind] = n
        return "$kind-$n"
    }
}

internal class FakeCatalogs(
    private val catalog: DownloadCatalog = sampleCatalog(),
) : DownloadCatalogSource {
    override suspend fun catalog(videoId: String) = catalog.copy(videoId = videoId)
    override suspend fun refresh(videoId: String) = catalog(videoId)
}

internal class DownloadAndroidHarness(catalogs: DownloadCatalogSource = FakeCatalogs()) {
    val repo = InMemoryDownloadRepository()
    val coordinator = DownloadCoordinator(
        repository = repo,
        transport = NoOpTransport,
        scheduler = NoOpScheduler,
        publisher = NoOpPublisher,
        ids = SeqIdFactory(),
        clock = { 1_000L },
    )
    val viewModel = DownloadViewModel(coordinator, catalogs)
}

internal fun vid(raw: String): String = raw.padEnd(11, 'x').take(11)

internal fun request(
    id: String,
    title: String = "T$id",
    config: DownloadConfig = DownloadConfig(),
): DownloadRequest = DownloadRequest(
    videoId = vid(id),
    title = title,
    author = "Author",
    config = config,
)

internal fun sampleCatalog(): DownloadCatalog = DownloadCatalog(
    videoId = vid("sample"),
    title = "Sample clip",
    author = "Channel",
    durationSec = 60L,
    formats = listOf(
        Format(
            url = "https://rr.example/videoplayback?id=v&itag=22&clen=3000000",
            height = 720,
            width = 1280,
            bitrate = 2_500_000,
            qualityLabel = "720p",
            codec = "avc1.640028",
            container = "MPEG_4",
            itag = 22,
            videoOnly = true,
            mimeType = "video/mp4",
            approxDurationMs = 60_000,
        ),
        Format(
            url = "https://rr.example/videoplayback?id=a&itag=140&clen=200000",
            bitrate = 128_000,
            codec = "mp4a.40.2",
            container = "MPEG_4",
            itag = 140,
            audioOnly = true,
            mimeType = "audio/mp4",
            approxDurationMs = 60_000,
            audioLocale = "en",
            audioTrackId = "en",
            audioTrackType = "original",
            audioTrackOriginal = true,
        ),
        Format(
            url = "https://rr.example/videoplayback?id=v1080&itag=137&clen=8000000",
            height = 1080,
            width = 1920,
            bitrate = 5_000_000,
            qualityLabel = "1080p",
            codec = "avc1.640028",
            container = "MPEG_4",
            itag = 137,
            videoOnly = true,
            mimeType = "video/mp4",
            approxDurationMs = 60_000,
        ),
    ),
    subtitles = listOf(
        Subtitle(
            url = "https://www.youtube.com/api/timedtext?lang=en&fmt=vtt",
            languageCode = "en",
            mimeType = "text/vtt",
        ),
    ),
)

internal data class SeededTasks(
    val runningId: String,
    val waitingId: String,
    val completedId: String,
    val missingId: String,
    val attachmentsId: String,
    val runningBatchId: String,
)

internal suspend fun DownloadAndroidHarness.seedList(): SeededTasks {
    val running = viewModel.enqueue(request("dQw4w9wgGcQ", "Running clip"), "s-run")
    val runningId = running.created.single().taskId
    coordinator.reportExecution(runningId, 0, DownloadStatus.RUNNING, DownloadPhase.TRANSFER)

    val waiting = viewModel.enqueue(request("oHg5SJYRHA0", "Waiting clip"), "s-wait")
    val waitingId = waiting.created.single().taskId
    coordinator.reportExecution(waitingId, 0, DownloadStatus.WAITING_NETWORK, DownloadPhase.TRANSFER)

    val done = viewModel.enqueue(request("jNQXAC9IVRw", "Completed clip"), "s-done")
    val completedId = done.created.single().taskId
    coordinator.reportAssetPublished(
        completedId,
        0,
        AssetKind.VIDEO,
        "content://com.hhst.litube.debug.download.fileprovider/downloads/c.mp4",
    )

    val missing = viewModel.enqueue(request("zzzzzzzzzzz", "Missing clip"), "s-miss")
    val missingId = missing.created.single().taskId
    coordinator.reportAssetPublished(
        missingId,
        0,
        AssetKind.VIDEO,
        "content://com.hhst.litube.debug.download.fileprovider/downloads/m.mp4",
    )
    coordinator.reportFileAvailability(missingId, 0, AssetKind.VIDEO, FileAvailability.MISSING)

    val attachments = viewModel.enqueue(
        request(
            "yyyyyyyyyyy",
            "Subs only",
            DownloadConfig(attachmentsOnly = true, includeSubtitle = true, subtitleLanguage = "en"),
        ),
        "s-att",
    )
    val attachmentsId = attachments.created.single().taskId
    coordinator.reportAssetPublished(
        attachmentsId,
        0,
        AssetKind.SUBTITLE,
        "content://com.hhst.litube.debug.download.fileprovider/downloads/a.en.vtt",
    )
    return SeededTasks(
        runningId = runningId,
        waitingId = waitingId,
        completedId = completedId,
        missingId = missingId,
        attachmentsId = attachmentsId,
        runningBatchId = running.batchId,
    )
}

internal object DeviceEvidence {
    const val DATE = "2026-09-17"
    const val HOST_DIR = "app/src/androidTest/assets/downloader/device"

    fun deviceDir(): File {
        val dir = File("/data/local/tmp/device-$DATE")
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("mkdir -p ${dir.absolutePath}")
            .use { pfd -> FileInputStream(pfd.fileDescriptor).copyTo(java.io.ByteArrayOutputStream()) }
        return dir
    }

    fun capture(fileName: String) {
        val dest = File(deviceDir(), fileName).absolutePath
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p $dest")
            .use { pfd -> FileInputStream(pfd.fileDescriptor).copyTo(java.io.ByteArrayOutputStream()) }
    }

    fun writeMeta() {
        val sdk = Build.VERSION.SDK_INT
        val release = Build.VERSION.RELEASE
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val local = File(ctx.cacheDir, "device-runtime.json")
        local.writeText(
            """{"api":$sdk,"release":"$release","date":"$DATE","product":"${Build.PRODUCT}","model":"${Build.MODEL}","device":"${Build.DEVICE}"}""",
        )
        deviceDir()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cp ${local.absolutePath} /data/local/tmp/device-$DATE/runtime-api$sdk.json")
            .use { pfd -> FileInputStream(pfd.fileDescriptor).copyTo(java.io.ByteArrayOutputStream()) }
    }

    fun captureScene(baseName: String) {
        capture("$baseName-api${Build.VERSION.SDK_INT}.png")
    }

    fun dumpUi(fileName: String) {
        val dest = "/data/local/tmp/$fileName"
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("uiautomator dump $dest")
            .use { pfd -> FileInputStream(pfd.fileDescriptor).copyTo(java.io.ByteArrayOutputStream()) }
    }

    fun shell(command: String): String {
        val out = java.io.ByteArrayOutputStream()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { pfd -> FileInputStream(pfd.fileDescriptor).copyTo(out) }
        return out.toString(Charsets.UTF_8)
    }

    fun writeJson(fileName: String, json: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.filesDir, fileName).writeText(json)
        File(ctx.cacheDir, fileName).writeText(json)
        val dest = File(deviceDir(), fileName)
        runCatching { dest.writeText(json) }
        val b64 = android.util.Base64.encodeToString(json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        shell("base64 -d > ${dest.absolutePath} <<'LITUBEB64'\n$b64\nLITUBEB64")
    }

    fun dumpWindowsXml(): String {
        shell("uiautomator dump --compressed /data/local/tmp/device-$DATE/uidump.xml")
        return shell("cat /data/local/tmp/device-$DATE/uidump.xml")
    }
}

internal fun resetOverlayState() {
    DownloadShareOnce.reset()
    var guard = 0
    while (PipAutoEnter.isSuppressed() && guard++ < 16) {
        PipAutoEnter.restore()
    }
}
