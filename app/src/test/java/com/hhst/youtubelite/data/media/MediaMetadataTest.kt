package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.watch.domain.WatchCommentsStatus
import com.liskovsoft.youtubeapi.videoinfo.V2.playerAnswer
import com.liskovsoft.youtubeapi.videoinfo.V2.watchPage
import org.junit.Assert.*
import org.junit.Test

class MediaMetadataTest {
    @Test fun blankPlayerDetailsCannotEraseTheWatchMetadata() {
        val page = watchPage("""{"videoPrimaryInfoRenderer":{"title":{"simpleText":"Movie"}},
            "videoSecondaryInfoRenderer":{"owner":{"videoOwnerRenderer":{"title":{"simpleText":"Blender"},
            "navigationEndpoint":{"browseEndpoint":{"browseId":"UCchannel"}}}}}}""", "aqz-KE-bpKQ")
        val details = playerAnswer("""{"videoDetails":{"title":"","author":"","channelId":"","viewCount":"2726356813"}}""").details
        val video = page.toAppWatchPage("aqz-KE-bpKQ", details).video
        assertEquals("Movie", video.title)
        assertEquals("Blender", video.channel)
        assertEquals("UCchannel", video.channelId)
        assertEquals("2,726,356,813 views", video.views)
        assertEquals(WatchCommentsStatus.Loading, video.commentsStatus)
    }

    @Test fun popularSortUsesDisplayedViewMagnitude() {
        assertEquals(9_500_000L, viewMagnitude("9.5M views"))
        assertEquals(2_500_000_000L, viewMagnitude("2.5B views"))
        assertEquals(12_345L, viewMagnitude("12,345 views"))
        assertEquals(0L, viewMagnitude(null))
    }
}
