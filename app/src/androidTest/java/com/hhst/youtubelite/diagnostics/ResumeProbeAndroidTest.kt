package com.hhst.youtubelite.diagnostics

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaResumePosition
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.requireValue
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Read-only server checkpoint probe; runs only with -e resumeProbe 1 -e videoId <id>. */
class ResumeProbeAndroidTest {
    @Test fun probe() = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("resumeProbe") == "1")
        val videoId = requireNotNull(arguments.getString("videoId"))
        val captured = AtomicReference<String>()
        val http = GlobalContext.get().get<OkHttpClient>().newBuilder()
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (chain.request().url.encodedPath.endsWith("/next")) {
                    captured.set(response.peekBody(8L * 1024 * 1024).string())
                }
                response
            }.build()
        val client = MediaClient(http, MediaSession::freshContext)
        val report = JSONObject()
        val started = android.os.SystemClock.elapsedRealtime()
        val resume = MediaResumePosition(client).load(videoId)
        report.put("production_resumeSeconds", resume?.div(1000) ?: JSONObject.NULL)
        report.put("production_query_ms", android.os.SystemClock.elapsedRealtime() - started)
        val body = captured.get()
        if (body == null) {
            report.put("captured_next", false)
        } else {
            report.put("captured_bytes", body.length)
            report.put("has_resumePlaybackEndpoint", body.contains("\"resumePlaybackEndpoint\""))
            report.put("startTimeSeconds", JSONArray(Regex("\"startTimeSeconds\"\\s*:\\s*(-?\\d{1,7})")
                .findAll(body).map { it.groupValues[1].toLong() }.toList()))
            report.put("resumePositionMs", JSONArray(Regex("\"resumePositionMs\"\\s*:\\s*(-?\\d{1,10})")
                .findAll(body).map { it.groupValues[1].toLong() }.toList()))
            report.put("has_currentVideoEndpoint", body.contains("\"currentVideoEndpoint\""))
        }
        // Same probe against the player endpoint: checkpoints may ride on the player
        // response instead of watch-next depending on the client.
        val playerBody = AtomicReference<String>()
        val playerHttp = GlobalContext.get().get<OkHttpClient>().newBuilder()
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (chain.request().url.encodedPath.endsWith("/player")) {
                    playerBody.set(response.peekBody(8L * 1024 * 1024).string())
                }
                response
            }.build()
        MediaClient(playerHttp, MediaSession::freshContext).read { context, http ->
            com.liskovsoft.youtubeapi.videoinfo.V2.loadPlayerDetails(videoId, context, http).requireValue()
        }
        val player = playerBody.get()
        if (player == null) {
            report.put("captured_player", false)
        } else {
            report.put("captured_player_bytes", player.length)
            report.put("player_startSeconds", JSONArray(Regex("\"startSeconds\"\\s*:\\s*(-?\\d{1,7})")
                .findAll(player).map { it.groupValues[1].toLong() }.toList()))
            report.put("player_has_resumePlaybackEndpoint", player.contains("\"resumePlaybackEndpoint\""))
            report.put("player_has_playbackStart", player.contains("\"playbackStart\""))
            report.put("player_has_startTimeSeconds", JSONArray(Regex("\"startTimeSeconds\"\\s*:\\s*(-?\\d{1,7})")
                .findAll(player).map { it.groupValues[1].toLong() }.toList()))
        }
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "resume-probe.json")
            .writeText(report.toString(2))
        if (body != null) {
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "resume-probe-next.json")
                .writeText(body)
        }
    }
}
