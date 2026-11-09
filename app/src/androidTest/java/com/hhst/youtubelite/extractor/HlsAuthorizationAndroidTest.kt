@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hhst.youtubelite.extractor

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.player.datasource.PlayerDataSource
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.diagnostics.DiagnosticContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

class HlsAuthorizationAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)

    @Test fun compareManifestTokensAndRenditions() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val ids = args.getString("videoIds").orEmpty().split(',').mapNotNull(VideoId::parse)
        assumeTrue("Supply -e network 1 and -e videoIds", args.getString("network") == "1" && ids.isNotEmpty())
        val host = GlobalContext.get().get<YoutubeExtractionHost>()
        val sources = GlobalContext.get().get<PlayerDataSource>()
        val records = mutableListOf<Map<String, Any>>()
        fun read(url: String, plan: MediaRequestPlan, limit: Int): ByteArray {
            val source = sources.manifest(plan).createDataSource()
            try {
                source.open(DataSpec(Uri.parse(url)))
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (out.size() < limit) {
                    val count = source.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                    if (count == C.RESULT_END_OF_INPUT) break
                    if (count > 0) out.write(buffer, 0, count)
                }
                return out.toByteArray()
            } finally {
                source.close()
            }
        }
        try {
            for (id in ids) {
                val solver = GlobalContext.get().get<SignatureSolver>()
                val solvedThrottles = mutableMapOf<String, String>()
                val captured = linkedMapOf<String, Any>()
                val originalContext = host.context(id, false, false, null, { true },
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(60), true, DiagnosticContext())
                fun inspect(value: JsonElement) {
                    when (value) {
                        is JsonObject -> {
                            (value["instreamVideoAdRenderer"] as? JsonObject)?.let { ad ->
                                captured["videoAdCount"] = (captured["videoAdCount"] as? Int ?: 0) + 1
                                ad["skipOffsetMilliseconds"]?.asLongNum?.let { captured["skipOffsetMs"] = it }
                            }
                            value.values.forEach(::inspect)
                        }
                        is JsonArray -> value.forEach(::inspect)
                        else -> Unit
                    }
                }
                val context = ExtractionContext(originalContext.session, object : ExtractionTransport {
                    override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>) =
                        originalContext.get(url, headers)
                    override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse =
                        originalContext.post(url, headers, body).also { response ->
                            if (url.contains("/youtubei/v1/player")) {
                                val root = parseJson(response.responseBody) as? JsonObject
                                captured["adSlots"] = (root?.get("adSlots") as? JsonArray)?.size ?: 0
                                captured["adPlacements"] = (root?.get("adPlacements") as? JsonArray)?.size ?: 0
                                if (root != null) inspect(root)
                            }
                        }
                }, false, false, true, deadlineNanos = originalContext.deadlineNanos,
                    current = originalContext.current, diagnostics = originalContext.diagnostics)
                val playback = MscPlayback(object : SignatureSolver by solver {
                    override fun solve(playerUrl: String, signatures: Set<String>, throttles: Set<String>, context: ExtractionContext) =
                        solver.solve(playerUrl, signatures, throttles, context).also { solvedThrottles.putAll(it.throttles) }
                }, GlobalContext.get().get(), MediaSession::freshContext, MediaSession::rememberVisitor)
                val stream = playback.resolve(id, context, null, false,
                    StreamClient.entries.filter { it != StreamClient.WEB_SAFARI }.toSet()).stream
                val original = stream.hlsUrl ?: continue
                val plan = requireNotNull(stream.hlsRequestPlan)
                val modes = mutableListOf("content" to original,
                    "none" to original.toHttpUrl().newBuilder().removeAllQueryParameters("pot").build().toString())
                val pathChallenge = Regex("/n/([^/?#]+)").find(original)
                val raw = solvedThrottles.entries.firstOrNull { it.value == pathChallenge?.groupValues?.get(1) }?.key
                if (raw != null && pathChallenge != null) modes += "unresolved" to original.replaceRange(
                    requireNotNull(pathChallenge.groups[1]).range, raw)
                for ((mode, url) in modes) {
                    val master = HlsPlaylistParser().parse(Uri.parse(url),
                        ByteArrayInputStream(read(url, plan, 1024 * 1024))) as HlsMultivariantPlaylist
                    val variants = master.variants.filter { it.format.codecs.orEmpty().contains("avc") }
                        .filter { it.format.height in 1..720 }.sortedBy { it.format.height }.distinctBy { it.format.height }
                    for (variant in variants) {
                        val row = linkedMapOf<String, Any>("videoId" to id, "manifestToken" to mode,
                            "client" to plan.client.name, "height" to variant.format.height)
                        row.putAll(captured)
                        try {
                            val playlist = HlsPlaylistParser().parse(variant.url,
                                ByteArrayInputStream(read(variant.url.toString(), plan, 1024 * 1024))) as HlsMediaPlaylist
                            val segment = UriUtil.resolve(playlist.baseUri, playlist.segments.first().url)
                            row["bytes"] = read(segment, plan, 4096).size
                            row["status"] = 200
                        } catch (error: Exception) {
                            row["status"] = generateSequence<Throwable>(error) { it.cause }
                                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode ?: 0
                            row["failure"] = error.javaClass.simpleName
                        }
                        records += row
                        instrumentation.sendStatus(2, Bundle().apply {
                            putString("hlsAuthorization", com.hhst.youtubelite.core.TestJson.any(row))
                        })
                    }
                }
            }
        } finally {
            File(instrumentation.targetContext.filesDir, "hls-authorization.json")
                .writeText(com.hhst.youtubelite.core.TestJson.any(records))
        }
    }
}
