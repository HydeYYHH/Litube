package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.home.domain.FeedItem
import com.liskovsoft.youtubeapi.videoinfo.V2.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFeedMappingTest {
    private fun video(id: String) = MediaItem("video", id, id, "Blender", "thumb", "9:56")
    private fun short(id: String) = MediaItem("short", id, id, "Blender", "thumb", "0:30")

    @Test fun shelvesStayInServerOrderWithInlineRails() {
        val feed = homeFeed(listOf(video("v1"), short("s1"), short("s2"), video("v2"), short("s3")))
        assertEquals(listOf("v1", "shorts-s1", "v2", "shorts-s3"), feed.map { it.id })
        val rails = feed.filterIsInstance<FeedItem.Shorts>()
        assertEquals(listOf(listOf("s1", "s2"), listOf("s3")), rails.map { rail -> rail.videos.map { it.id } })
    }

    @Test fun railsWithoutShortsDoNotAppear() {
        assertEquals(emptyList<FeedItem>(), homeFeed(emptyList()))
        assertEquals(listOf("v1", "v2"), homeFeed(listOf(video("v1"), video("v2"))).map { it.id })
    }

    @Test fun playlistRowsHaveNoRouteOnHomeAndStayOutInsteadOfFakingContainers() {
        val mix = MediaItem("playlist", "RDmix", "Mix", "Blender", "thumb", "")
        assertEquals(listOf("v1"), homeFeed(listOf(mix, video("v1"))).map { it.id })
    }
}
