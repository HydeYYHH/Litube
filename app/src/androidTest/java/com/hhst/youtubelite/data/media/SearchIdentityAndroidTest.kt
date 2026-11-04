package com.hhst.youtubelite.data.media

import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.PreferenceStore
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SearchIdentityAndroidTest {
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
        val source = MediaSearchSuggestions(client, store, identity::get)
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
