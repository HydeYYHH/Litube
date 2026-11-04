package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.search.domain.SearchResult
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ContentIdentityRepositoryTest {
    private fun client(reply: (String, Boolean) -> String) = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(reply(request.url.encodedPath.substringAfterLast('/'), request.header("Authorization") != null).toResponseBody()).build()
    }.build(), { RequestContext(accessToken = "test-token", generation = 7) })

    private val publicDetails = """{"videoDetails":{"videoId":"abcdefghijk","author":"Artist - Topic","channelId":"UCartist",
        "title":"Song","lengthSeconds":"221"},"microformat":{"playerMicroformatRenderer":{"publishDate":"2025-11-10T02:02:12-08:00"}},
        "playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"https://www.youtube.com/api/stats/playback?identity=guest"}}}"""

    @Test fun missingSongFieldsComeFromPublicDetailsWithoutChangingExistingFields() = runBlocking {
        val client = client { endpoint, signedIn ->
            assertEquals("player", endpoint); assertFalse(signedIn); publicDetails
        }
        val row = SearchResult("abcdefghijk", "Search title", "Artist", "UCartist", "2.5K views", "", "", "cover", avatar = "avatar")
        val result = MediaSearchRepository(client).metadata(row)
        assertEquals("3:41", result.duration)
        assertEquals("2025-11-10", result.age)
        assertEquals(row.copy(duration = "3:41", age = "2025-11-10"), result)
    }

    @Test fun topicChannelKeepsItsAlbumShelvesWithoutRequiringAPlaylistsTab() = runBlocking {
        val client = client { endpoint, _ ->
            assertEquals("browse", endpoint)
            """{"metadata":{"channelMetadataRenderer":{"title":"Artist - Topic"}},"contents":{"shelfRenderer":{
                "title":{"simpleText":"Albums & Singles"},"content":{"items":[{"playlistRenderer":{
                    "playlistId":"OLAKalbum","title":{"simpleText":"Know My Name"},"videoCountText":{"simpleText":"1 video"}
                }}]}}}}"""
        }
        val page = MediaChannelRepository(client, MediaPostRepository(client)).channel("UCartist")
        assertEquals("Artist - Topic", page.profile.name)
        assertEquals("Albums & Singles", page.homeShelves.single().title)
        assertEquals("OLAKalbum", page.homeShelves.single().playlists.single().id)
        assertEquals(page.playlists, page.homeShelves.single().playlists)
        assertFalse(page.playlistsDeferred)
    }

    @Test fun sparseSearchRowsRecoverViewsAndZeroIsARealCount() = runBlocking {
        for ((count, expected) in listOf("12345" to "12,345 views", "0" to "0 views")) {
            val client = client { endpoint, signedIn ->
                assertEquals("player", endpoint)
                assertFalse(signedIn)
                """{"videoDetails":{"videoId":"abcdefghijk","viewCount":"$count"}}"""
            }
            val row = SearchResult("abcdefghijk", "Title", "", "", "", "", "", "cover")
            assertEquals(expected, MediaSearchRepository(client).metadata(row).views)
        }
    }

    @Test fun musicWatchSupplementRetainsSignedInTrackingAndRealArtist() = runBlocking {
        val client = client { endpoint, signedIn -> when {
            endpoint == "next" -> """{"contents":{"singleColumnWatchNextResults":{"results":{"results":{
                "musicWatchMetadataRenderer":{"title":{"simpleText":"Song"},"byline":{"simpleText":"Artist"}}
            }}}},"transportControls":{"transportControlsRenderer":{"channelButton":{"videoOwnerRenderer":{
                "title":{"simpleText":"Channel"},"navigationEndpoint":{"showEngagementPanelEndpoint":{}}
            }}}}}"""
            signedIn -> """{"videoDetails":{"videoId":"abcdefghijk","channelId":"UCartist","lengthSeconds":"221"},
                "playbackTracking":{"videostatsPlaybackUrl":{"baseUrl":"https://www.youtube.com/api/stats/playback?identity=account"}}}"""
            else -> publicDetails
        } }
        val video = MediaWatchRepository(client).watch("abcdefghijk").video
        assertEquals("Artist", video.channel)
        assertEquals("UCartist", video.channelId)
        assertEquals("2025-11-10", video.age)
        assertTrue(video.tracking?.playbackUrl.orEmpty().contains("identity=account"))
    }

    @Test fun unavailableOptionalMetadataDoesNotDiscardTheWatchPage() = runBlocking {
        val client = client { endpoint, signedIn -> when {
            endpoint == "next" -> """{"videoDetails":{"videoId":"abcdefghijk","title":"Song","author":"Artist","channelId":"UCartist"}}"""
            signedIn -> """{"videoDetails":{"videoId":"abcdefghijk","lengthSeconds":"221"}}"""
            else -> throw java.io.IOException("metadata offline")
        } }
        val video = MediaWatchRepository(client).watch("abcdefghijk").video
        assertEquals("Song", video.title)
        assertEquals("Artist", video.channel)
        assertEquals("", video.age)
    }
}
