package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ContentMetadataTest {
    @Test fun avatarIsKeptSeparateFromTheVideoCover() {
        val item = searchPage("""{"contents":[{"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Title"},"thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/cover.jpg"}]},"channelThumbnailSupportedRenderers":{"channelThumbnailWithLinkRenderer":{"thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/avatar.jpg"}]}}},"viewCountText":{"simpleText":"1,234 views"},"publishedTimeText":{"simpleText":"2 days ago"}}}]}""").items.single()
        assertEquals("https://i.ytimg.com/cover.jpg", item.thumbnail)
        assertEquals("https://yt3.ggpht.com/avatar.jpg", item.authorThumbnail)
        assertEquals("1,234 views", item.viewCount)
        assertEquals("2 days ago", item.published)
    }

    @Test fun watchKeepsTheExistingSubscriptionAndRating() {
        val page = watchPage("""{"contents":[{"videoSecondaryInfoRenderer":{"owner":{"videoOwnerRenderer":{"thumbnail":{"thumbnails":[{"url":"https://yt3.ggpht.com/avatar.jpg"}]}}}}},{"subscribeButtonRenderer":{"subscribed":true}},{"likeButtonRenderer":{"likeStatus":"DISLIKE"}}]}""")
        assertEquals(true, page.subscribed)
        assertEquals("DISLIKE", page.rating)
        assertEquals("https://yt3.ggpht.com/avatar.jpg", page.authorThumbnail)
    }
    @Test fun playlistKeepsTheServerCountAndLeavesUnknownCountsAbsent() {
        val item = searchPage("""{"contents":[{"playlistRenderer":{"playlistId":"PL123","title":{"simpleText":"Playlist"},"videoCountText":{"simpleText":"42 videos"}}}]}""").items.single()
        assertEquals("42 videos", item.videoCountText)
        val unknown = searchPage("""{"contents":[{"playlistRenderer":{"playlistId":"PL456","title":{"simpleText":"Playlist"}}}]}""").items.single()
        assertNull(unknown.videoCountText)
    }

}
