package com.hhst.youtubelite.core

import android.content.Context
import androidx.annotation.Keep
import com.hhst.youtubelite.extractor.Metadata
import com.hhst.youtubelite.home.domain.FeedItem
import com.hhst.youtubelite.player.engine.ResumePoint
import java.io.File

@Keep
object StorageVerification {
    fun verify(context: Context) {
        val prefs = PreferenceStore.of(context, "r8-cache-test")
        val directory = File(context.cacheDir, "r8-snapshots")
        try {
            val cache = PreferenceJsonCache(prefs)
            cache.put("progress", ResumePoint(9_000), 60_000)
            cache.put("metadata", Metadata(id = "video", title = "Title"), 60_000)
            check(cache.get("progress", ResumePoint::class.java)?.positionMs == 9_000L)
            check(cache.get("metadata", Metadata::class.java)?.title == "Title")
            val snapshots = ContentSnapshots(directory)
            val video = FeedItem.Video("id", "Title", "Author", "100 views", "image", "1:00", "channel")
            snapshots.write("feed", "account", "query", listOf(video), FeedItem.Video::class.java)
            check(snapshots.read("feed", "account", "query", FeedItem.Video::class.java) == listOf(video))
        } finally {
            directory.deleteRecursively()
            prefs.clearAll()
        }
    }
}
