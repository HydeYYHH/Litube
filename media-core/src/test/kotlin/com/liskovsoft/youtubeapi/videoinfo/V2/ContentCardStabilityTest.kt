package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class ContentCardStabilityTest {
    private fun lockup(rows: String, extra: String = "", image: String = "") = searchPage("""{
        "contents":[{"lockupViewModel":{
            "contentId":"abcdefghijk","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
            "metadata":{"lockupMetadataViewModel":{"title":{"content":"Title"},
                "metadata":{"contentMetadataViewModel":{"metadataRows":[$rows]}} $image}},
            "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"watchEndpoint":{"videoId":"abcdefghijk"}}}}}
            $extra
        }}]}
    """).items.single()

    @Test fun localizedModernStatsAreKeptWithoutInventingAnAuthor() {
        for ((views, age) in listOf(
            "1.2万次观看" to "3天前",
            "1.2萬次觀看" to "3 天前",
            "12万回視聴" to "3日前",
            "조회수 1.2만회" to "3일 전",
            "1,2 mil visualizaciones" to "hace 3 días",
            "1,2 k vues" to "il y a 3 jours",
            "1.200 Aufrufe" to "vor 3 Tagen",
        )) {
            val item = lockup("""{"metadataParts":[{"text":{"content":"$views"}},{"text":{"content":"$age"}}]}""")
            assertEquals(views, item.viewCount)
            assertEquals(age, item.published)
            assertNull("Stats must not be the author: $views", item.author)
        }
    }

    @Test fun statsSharingTheAuthorRowRemainVisible() {
        val item = lockup("""{"metadataParts":[
            {"text":{"content":"Creator"},"navigationEndpoint":{"browseEndpoint":{"browseId":"UCcreator"}}},
            {"text":{"content":"1.2M views"}}, {"text":{"content":"3 days ago"}}
        ]}""")
        assertEquals("Creator", item.author)
        assertEquals("UCcreator", item.channelId)
        assertEquals("1.2M views", item.viewCount)
        assertEquals("3 days ago", item.published)
    }

    @Test fun aStatsOnlyCardWithAnAvatarDoesNotLoseItsFirstRow() {
        val item = lockup("""{"metadataParts":[{"text":{"content":"2.5M views"}}]},
            {"metadataParts":[{"text":{"content":"2 weeks ago"}}]}""",
            image = """, "image":{"avatarViewModel":{"image":{"sources":[{"url":"https://avatar"}]}}}""")
        assertNull(item.author)
        assertEquals("2.5M views", item.viewCount)
        assertEquals("2 weeks ago", item.published)
        assertEquals("https://avatar", item.authorThumbnail)
    }

    @Test fun durationAndLiveStatusDoNotDependOnTheFirstThumbnailBadge() {
        val item = lockup("", extra = """, "contentImage":{"thumbnailViewModel":{"overlays":[
            {"thumbnailBadgeViewModel":{"text":"CC","badgeStyle":"DEFAULT"}},
            {"thumbnailBadgeViewModel":{"text":"12:34","badgeStyle":"DEFAULT"}},
            {"thumbnailBadgeViewModel":{"text":"LIVE","badgeStyle":"THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE"}}
        ]}}""")
        assertEquals("12:34", item.duration)
        assertTrue(item.live)
        val withoutDuration = lockup("", extra = """, "thumbnailBadgeViewModel":{"text":"4K","badgeStyle":"DEFAULT"}""")
        assertNull(withoutDuration.duration)
    }

    @Test fun shortsSecondaryViewCountIsNotMisreadAsItsCreator() {
        val item = searchPage("""{"contents":[{"shortsLockupViewModel":{
            "overlayMetadata":{"primaryText":{"content":"Short"},"secondaryText":{"content":"12万次观看"}},
            "onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"abcdefghijk"}}}
        }}]}""").items.single()
        assertEquals("12万次观看", item.viewCount)
        assertNull(item.author)
    }

    @Test fun blankLegacyFieldsDoNotHideModernFallbackMetadata() {
        val item = lockup("""{"metadataParts":[{"text":{"content":"Creator"}}]},
            {"metadataParts":[{"text":{"content":"9K views"}},{"text":{"content":"1 day ago"}}]}""",
            extra = """, "viewCountText":{"simpleText":""},"publishedTimeText":{"simpleText":" "},
                "shortBylineText":{"simpleText":""},"lengthText":{"simpleText":""},
                "thumbnailBadgeViewModel":{"text":"3:42"}""")
        assertEquals("Creator", item.author)
        assertEquals("9K views", item.viewCount)
        assertEquals("1 day ago", item.published)
        assertEquals("3:42", item.duration)
    }

    @Test fun duplicateCardsFillOnlyMissingDisplayFieldsAndKeepTheOriginalCommands() {
        val page = searchPage("""{"contents":[
            {"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Original title"},
                "navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk","startTimeSeconds":12}},
                "menu":{"buttonViewModel":{"title":"Hide","onTap":{"innertubeCommand":{"feedbackEndpoint":{"feedbackToken":"original-token"}}}}}}},
            {"videoRenderer":{"videoId":"secondvideo"}},
            {"videoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"Later title"},
                "shortBylineText":{"simpleText":"Creator"},"channelId":"UCcreator",
                "thumbnail":{"thumbnails":[{"url":"https://cover"}]},
                "channelThumbnail":{"thumbnails":[{"url":"https://avatar"}]},
                "lengthText":{"simpleText":"3:42"},"viewCountText":{"simpleText":"9K views"},
                "publishedTimeText":{"simpleText":"1 day ago"},
                "navigationEndpoint":{"watchEndpoint":{"videoId":"abcdefghijk","startTimeSeconds":99}},
                "menu":{"buttonViewModel":{"title":"Hide","onTap":{"innertubeCommand":{"feedbackEndpoint":{"feedbackToken":"later-token"}}}}}}}
        ]}""")
        assertEquals(listOf("abcdefghijk", "secondvideo"), page.items.map { it.id })
        val item = page.items.first()
        assertEquals("Original title", item.title)
        assertEquals("Creator", item.author)
        assertEquals("UCcreator", item.channelId)
        assertEquals("https://cover", item.thumbnail)
        assertEquals("https://avatar", item.authorThumbnail)
        assertEquals("3:42", item.duration)
        assertEquals("9K views", item.viewCount)
        assertEquals("1 day ago", item.published)
        assertEquals(12L, item.startSeconds)
        assertEquals("original-token", jsonObject(item.actions.single().endpoint.payload)!!.text("feedbackToken"))
    }
}
