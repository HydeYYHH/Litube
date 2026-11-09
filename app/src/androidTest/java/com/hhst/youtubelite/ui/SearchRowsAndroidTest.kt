package com.hhst.youtubelite.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.PreferenceStore
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaSearchSuggestions
import com.hhst.youtubelite.ui.components.rememberSearchRows
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.AppTheme
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SearchRowsAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun localHistoryAndRemovalNeverWaitForRemoteSuggestions() {
        val store = PreferenceStore.of(InstrumentationRegistry.getInstrumentation().targetContext, "search-rows-motion")
        store.clearAll()
        val response = CountDownLatch(1)
        val calls = AtomicInteger()
        val identity = RequestContext()
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            check(response.await(10, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""["fi",["first remote"]]""".toResponseBody()).build()
        }.build()) { identity }
        val source = MediaSearchSuggestions(client, store) { identity }
        var query by mutableStateOf("")
        source.record("first local")
        try {
            compose.setContent { AppTheme {
                val rows = requireNotNull(rememberSearchRows(source, query))
                Column {
                    rows.history.forEach { text ->
                        AppText(text)
                        AppText("Remove", Modifier.testTag("remove-history").clickable { rows.onRemove(text) })
                    }
                    rows.suggestions.forEach { AppText(it) }
                }
            } }
            compose.onNodeWithText("first local").assertIsDisplayed()
            assertEquals("Opening history needs no remote request", 0, calls.get())
            compose.runOnIdle { query = "fi" }
            compose.onNodeWithText("first local").assertIsDisplayed()
            compose.waitUntil(5_000) { calls.get() > 0 }
            compose.onNodeWithText("first local").assertIsDisplayed()
            compose.onNodeWithTag("remove-history").performClick()
            compose.onNodeWithText("first local").assertDoesNotExist()
            compose.runOnIdle { query = "" }
            response.countDown()
            compose.waitForIdle()
            compose.onNodeWithText("first local").assertDoesNotExist()
            compose.onNodeWithText("first remote").assertDoesNotExist()
        } finally {
            response.countDown()
            store.clearAll()
        }
    }
}
