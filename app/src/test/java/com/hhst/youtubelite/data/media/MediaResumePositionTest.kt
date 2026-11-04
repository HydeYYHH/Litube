package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MediaResumePositionTest {
    @Before fun prepare() { MediaSession.initialize(MemoryAuthStore()); ContentSource.fixtures = false }
    @After fun restore() { MediaSession.initialize(MemoryAuthStore()); ContentSource.fixtures = false }

    @Test fun accountProgressUsesAFreshReadAndPreservesZeroAsARealPosition() = runBlocking {
        MockWebServer().use { server ->
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val resume = MediaResumePosition(MediaClient(http) { RequestContext(accessToken = "access") })
            for (position in listOf(42, 0)) {
                server.enqueue(MockResponse().setBody("""{"currentVideoEndpoint":{"watchEndpoint":{"startTimeSeconds":$position}}}"""))
                assertEquals(position * 1_000L, resume.load("abcdefghijk"))
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun anonymousAndFixturePlaybackDoNotMakeAccountProgressRequests() = runBlocking {
        MockWebServer().use { server ->
            val http = OkHttpClient.Builder().addInterceptor { error("Unexpected network request") }.build()
            assertNull(MediaResumePosition(MediaClient(http) { RequestContext() }).load("abcdefghijk"))
            ContentSource.fixtures = true
            assertNull(MediaResumePosition(MediaClient(http) { RequestContext(accessToken = "access") }).load("abcdefghijk"))
            assertEquals(0, server.requestCount)
        }
    }
}
