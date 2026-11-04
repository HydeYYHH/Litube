package com.hhst.youtubelite.watch

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.MediaVideoActions
import com.hhst.youtubelite.data.media.requireValue
import com.hhst.youtubelite.watch.domain.VideoRating
import com.liskovsoft.youtubeapi.videoinfo.V2.loadWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

/** Opt-in live diagnosis. Always restore the original viewer rating, including on failure. */
class VideoRatingProbeAndroidTest {
    @Test fun verifyWritesAndRestoreOriginalRating(): Unit = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("ratingWriteProbe") == "1")
        val id = requireNotNull(args.getString("ratingVideo"))
        val client = MediaClient(GlobalContext.get().get<OkHttpClient>(), MediaSession::freshContext).forWrites()
        val actions = MediaVideoActions(client)
        suspend fun read(): VideoRating? = client.read { context, transport ->
            assumeTrue("The probe requires a signed-in account", context.signedIn)
            when (loadWatch(id, context, http = transport).requireValue().rating) {
                "LIKE" -> VideoRating.Like
                "DISLIKE" -> VideoRating.Dislike
                "INDIFFERENT" -> VideoRating.None
                else -> null
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.filesDir, "video-rating-probe.json")
        // Recovery entry point for a probe interrupted before its finally block completed; the neutral endpoint removes either kind of rating.
        args.getString("ratingRestoreTo")?.let { saved ->
            val target = VideoRating.valueOf(saved)
            actions.rate(id, target, VideoRating.Like)
            val observed = read()
            val report = if (output.exists()) JSONObject(output.readText()) else JSONObject()
            report.put("restored", observed?.name ?: "unknown")
            output.writeText(report.toString(2))
            assertEquals(target, observed)
            return@runBlocking
        }
        val original = requireNotNull(read()) { "The original rating must be readable before any write" }
        val report = JSONObject().put("original", original.name)
        var last = original
        var started = false
        try {
            for ((step, target) in listOf("like" to VideoRating.Like, "unlike" to VideoRating.None,
                "dislike" to VideoRating.Dislike, "undislike" to VideoRating.None)) {
                started = true
                // The requested state is also the best restoration hint if readback fails.
                val previous = last
                last = target
                actions.rate(id, target, previous)
                val observed = read()
                report.put(step, observed?.name ?: "unknown")
                output.writeText(report.toString(2))
                assertEquals(target, observed)
            }
        } finally {
            if (started) {
                actions.rate(id, original, last)
                val restored = read()
                report.put("restored", restored?.name ?: "unknown")
                output.writeText(report.toString(2))
                assertEquals("Restore the account's original rating", original, restored)
            }
        }
    }
}
