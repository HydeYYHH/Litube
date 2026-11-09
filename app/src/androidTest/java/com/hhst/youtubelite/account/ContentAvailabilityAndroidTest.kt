package com.hhst.youtubelite.account

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.requireValue
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.loadChannel
import com.liskovsoft.youtubeapi.videoinfo.V2.loadComments
import com.liskovsoft.youtubeapi.videoinfo.V2.resolveChannelId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

class ContentAvailabilityAndroidTest {
    @Test fun publicChannelShortsExposeDownloadableImages() = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        val http = GlobalContext.get().get<OkHttpClient>()
        val client = MediaClient(http) { MediaSession.freshContext().anonymous() }
        client.read { context, transport ->
            val id = resolveChannelId("@MrBeast", context, transport).requireValue()
            val first = loadChannel(id, context, http = transport).requireValue()
            val tab = requireNotNull(first.page.tabs.firstOrNull { it.title.equals("Shorts", true) })
            val page = loadChannel(id, context, tab.params, http = transport).requireValue()
            val items = page.page.items.filter { it.kind in setOf("video", "short") }
            assertTrue("No channel Shorts returned", items.isNotEmpty())
            assertTrue("Missing thumbnail URLs", items.all { !it.thumbnail.isNullOrBlank() })
            val thumbnail = requireNotNull(items.first().thumbnail)
            val reply = transport.execute(HttpRequest("GET", thumbnail))
            assertEquals(200, reply.status)
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("availability", "channelShorts=${items.size}; imageStatus=${reply.status}")
            })
        }
    }

    @Test fun commentReadsRetainCreationCapabilityForTheCurrentAccount() = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        assumeTrue(MediaSession.signedIn())
        val client = GlobalContext.get().get<MediaClient>()
        client.read { context, transport ->
            val page = loadComments("GjjK6yD5I9I", context, http = transport).requireValue()
            assertTrue("No comment creation token in the signed-in response", !page.createKey.isNullOrBlank())
        }
    }
}
