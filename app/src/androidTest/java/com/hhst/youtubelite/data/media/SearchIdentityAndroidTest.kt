package com.hhst.youtubelite.data.media

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.PreferenceStore
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

class SearchIdentityAndroidTest {
    @Test fun privateBrowsingStopsNewHistoryWithoutRemovingExistingRowsOrSuggestions() = runBlocking {
        val store = PreferenceStore.of(InstrumentationRegistry.getInstrumentation().targetContext, "search-private-fixture")
        val prefs = GlobalContext.get().get<ExtensionManager>()
        val key = PreferenceKeys.PRIVATE_BROWSING
        val previous = prefs.isEnabled(key)
        var calls = 0
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""["fi",["first remote"]]""".toResponseBody("application/json".toMediaType())).build()
        }.build()) { MediaSession.snapshot() }
        val source = ContentRepositories(client, store).suggestions
        try {
            prefs.setEnabled(key, false)
            source.record("first local")
            prefs.setEnabled(key, true)
            source.record("private query")
            assertEquals(listOf("first local"), source.rows("").history)
            assertEquals(listOf("first remote"), source.rows("fi").suggestions)
            assertEquals(1, calls)
            source.remove("first local")
            assertTrue(source.rows("").history.isEmpty())
            prefs.setEnabled(key, false)
            source.record("public again")
            assertEquals(listOf("public again"), source.rows("").history)
        } finally {
            prefs.setEnabled(key, previous)
            store.clearAll()
        }
    }

    @Test fun localRowsDiscardAnIdentityChangeWithoutThrowingIntoComposition() {
        val store = PreferenceStore.of(InstrumentationRegistry.getInstrumentation().targetContext, "search-local-identity-fixture")
        val first = RequestContext(accountId = "first", accessToken = "access", generation = 7)
        var reads = 0
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { error("unexpected request") }.build()) { first }
        try {
            MediaSearchSuggestions(client, store) { first }.record("first local")
            val source = MediaSearchSuggestions(client, store) {
                if (reads++ == 0) first else first.copy(accountId = "second", generation = 8)
            }
            assertEquals(SearchRowSet(emptyList(), emptyList()), source.localRows("fi"))
        } finally { store.clearAll() }
    }

    @Test fun switchingIdentityDuringSuggestionsRejectsBothRemoteAndLocalRows() = runBlocking {
        val store = PreferenceStore.of(InstrumentationRegistry.getInstrumentation().targetContext, "search-identity-fixture")
        val first = RequestContext(accountId = "first", accessToken = "access", generation = 7)
        val identity = AtomicReference(first)
        var calls = 0
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            identity.set(first.copy(accountId = "second", generation = 8))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""["fi",["first remote"]]""".toResponseBody("application/json".toMediaType())).build()
        }.build()) { first }
        val source = MediaSearchSuggestions(client, store, identity = identity::get)
        try {
            source.record("first local")
            assertTrue(runCatching { source.rows("fi") }.exceptionOrNull() is CancellationException)
            assertEquals(1, calls)
            assertTrue(source.rows("").history.isEmpty())
            source.record("second local")
            assertEquals(listOf("second local"), source.rows("").history)
            identity.set(first)
            assertEquals(listOf("first local"), source.rows("").history)
        } finally { store.clearAll() }
    }

    @Test fun mismatchedRequestIdentityNeverSendsASuggestionRequest() = runBlocking {
        val store = PreferenceStore.of(InstrumentationRegistry.getInstrumentation().targetContext, "search-preflight-fixture")
        val first = RequestContext(accountId = "first", accessToken = "access", generation = 7)
        var calls = 0
        val client = MediaClient(OkHttpClient.Builder().addInterceptor { calls++; error("unexpected request") }.build()) {
            first.copy(accountId = "second", onBehalfOfUser = "second-channel")
        }
        try {
            assertTrue(runCatching { MediaSearchSuggestions(client, store) { first }.rows("query") }
                .exceptionOrNull() is CancellationException)
            assertEquals(0, calls)
        } finally { store.clearAll() }
    }
}
