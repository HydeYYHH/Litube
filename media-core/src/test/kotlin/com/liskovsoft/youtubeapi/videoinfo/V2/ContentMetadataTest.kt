package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ContentMetadataTest {
    private fun lockup(rows: String) = searchPage("""{"contents":[{"lockupViewModel":{
        "contentId":"abcdefghijk","contentType":"LOCKUP_CONTENT_TYPE_MUSIC",
        "metadata":{"lockupMetadataViewModel":{"title":{"content":"Song title"},
        "metadata":{"contentMetadataViewModel":{"metadataRows":[$rows]}}}},
        "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"abcdefghijk"}}}}}
    }}]}""").items.single()

    @Test fun tvMusicSearchKeepsAnUnlinkedAuthorAndItsStats() {
        val item = lockup("""{"metadataParts":[{"text":{"content":"Aleesa Sky"}}]},
            {"metadataParts":[{"text":{"content":"Song"}},{"text":{"content":"2.5K views"}}]}""")
        assertEquals("Aleesa Sky", item.author)
        assertEquals("2.5K views", item.viewCount)
        assertNull(item.published)
        assertNull(item.duration)
    }

    @Test fun tvRecommendationsKeepAnAuthorOnlyRow() {
        assertEquals("Electric Dreams", lockup("""{"metadataParts":[{"text":{"content":"Electric Dreams"}}]}""").author)
    }

    @Test fun statsOnlyRowsAreNeverUsedAsChannelNames() {
        for (text in listOf("2.5K views", "3 weeks ago", "Streamed 2 days ago", "Song")) {
            assertNull(text, lockup("""{"metadataParts":[{"text":{"content":"$text"}}]}""").author)
        }
    }

    @Test fun watchUsesMusicBylineInsteadOfAPanelButtonLabel() {
        val page = watchPage("""{"contents":{"singleColumnWatchNextResults":{"results":{"results":{
            "musicWatchMetadataRenderer":{"title":{"simpleText":"Know My Name"},"byline":{"simpleText":"Aleesa Sky"}}
        }}}},"transportControls":{"transportControlsRenderer":{"channelButton":{"videoOwnerRenderer":{
            "title":{"simpleText":"Channel"},"navigationEndpoint":{"showEngagementPanelEndpoint":{"identifier":{"tag":"channel-actions-panel"}}}
        }}}}}""")
        assertEquals("Aleesa Sky", page.author)
    }

    @Test fun aRealChannelNamedChannelStillKeepsItsName() {
        val page = watchPage("""{"contents":{"videoSecondaryInfoRenderer":{"owner":{"videoOwnerRenderer":{
            "title":{"simpleText":"Channel"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCowner"}}}
        }}}}""")
        assertEquals("Channel", page.author)
        assertEquals("UCowner", page.channelId)
    }

    @Test fun playerDetailsKeepTheActualPublicationDate() {
        val details = readPlayerDetails(jsonObject("""{"videoDetails":{"videoId":"abcdefghijk","lengthSeconds":"221"},
            "microformat":{"playerMicroformatRenderer":{"publishDate":"2025-11-10T02:02:12-08:00"}}}""")!!)
        assertEquals(221L, details?.durationSeconds)
        assertEquals("2025-11-10T02:02:12-08:00", details?.published)
    }
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
