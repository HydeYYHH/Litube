package com.hhst.youtubelite.post

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.hhst.youtubelite.core.PreferenceStore
import com.hhst.youtubelite.data.media.ContentRepositories
import com.hhst.youtubelite.data.media.ContentSource
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.navigation.ContentLink
import com.hhst.youtubelite.navigation.intent
import com.hhst.youtubelite.post.domain.PostIdentity
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Runs the production host and repositories against an offline HTTP response. */
class ProductionPostLinkAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun aPostLinkWithoutAChannelStaysOpenAndResolvesTheAuthor() {
        val koin = GlobalContext.get()
        val previous = koin.get<ContentRepositories>()
        val fixtures = ContentSource.fixtures
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/post/Ugkx_link", chain.request().url.encodedPath)
            val html = """<script>var ytInitialData = {"backstagePostRenderer":{"postId":"Ugkx_link",
                "authorEndpoint":{"browseEndpoint":{"browseId":"UCauthor"}},"authorText":{"simpleText":"Author"},
                "contentText":{"simpleText":"Opened from a post link"}}};</script>"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(html.toResponseBody()).build()
        }.build()
        val preferences = PreferenceStore.of(ApplicationProvider.getApplicationContext(), "post-link-test")
        val replacement = ContentRepositories(MediaClient(transport), preferences)
        var scenario: ActivityScenario<PostDetailActivity>? = null
        try {
            ContentSource.fixtures = false
            koin.declare(replacement, allowOverride = true)
            val link = ContentLink.parse("https://www.youtube.com/post/Ugkx_link")!!
            scenario = ActivityScenario.launch(link.intent(ApplicationProvider.getApplicationContext()))
            compose.waitUntil(15_000) {
                runCatching { compose.onAllNodesWithTag("post-detail-body").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
            }
            compose.onNodeWithTag("post-detail-body").assertTextContains("Opened from a post link")
            scenario.onActivity {
                assertFalse(it.isFinishing)
                assertTrue(it.postDetailViewModel.uiState.value.live)
                assertEquals(PostIdentity("UCauthor", "Ugkx_link"), it.postDetailViewModel.uiState.value.identity)
            }
        } finally {
            scenario?.close()
            koin.declare(previous, allowOverride = true)
            ContentSource.fixtures = fixtures
            preferences.clearAll()
        }
    }
}
