package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.library.domain.HistoryEntry
import com.liskovsoft.youtubeapi.videoinfo.V2.AccessToken
import com.liskovsoft.youtubeapi.videoinfo.V2.Account
import com.liskovsoft.youtubeapi.videoinfo.V2.SignInClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LibrarySourceIsolationTest {

    private lateinit var sessionStore: MemoryAuthStore
    private lateinit var calls: MutableList<String>
    private lateinit var local: LocalLibraryStore

    private class Recording(val calls: MutableList<String>) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val body = request.body?.let { okio.Buffer().also(it::writeTo).readUtf8() }.orEmpty()
            synchronized(calls) { calls += request.url.encodedPath + " " + body }
            val payload = when {
                "accounts_list" in request.url.encodedPath -> """{"contents":[{"accountItemRenderer":
                    {"accountId":{"simpleText":"owner"},"accountName":{"runs":[{"text":"Owner"}]},
                    "accountByline":{"runs":[{"text":"owner@example.com"}]},"isSelected":true}}]}"""
                // Writes must win over the browse prefix: edit_playlist lives under /browse.
                "edit_playlist" in request.url.encodedPath || "playlist/create" in request.url.encodedPath
                    || "/like/" in request.url.encodedPath -> """{"success":true,"playlistId":"srv-list"}"""
                "/browse" in request.url.encodedPath -> """{"contents":[{"videoRenderer":{"videoId":"srv-video",
                    "title":{"simpleText":"Server video"},"lengthText":{"simpleText":"1:00"}}}]}"""
                else -> """{"success":true,"playlistId":"srv-list"}"""
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }
    }

    @Before fun setUp() {
        sessionStore = MemoryAuthStore()
        MediaSession.initialize(sessionStore)
        calls = mutableListOf()
        local = LocalLibraryStore(MemoryAuthStore())
    }

    @After fun tearDown() {
        MediaSession.tokenFactory = { initial, client -> com.liskovsoft.youtubeapi.videoinfo.V2.AccountToken(initial, client) }
        MediaSession.initialize(MemoryAuthStore())
    }

    private fun signIn() = MediaSession.completeSignIn(SignInClient("id", "secret", "device"),
        AccessToken("access", "refresh", 4_000_000_000_000),
        listOf(Account("owner", "Owner", "owner@example.com", "", selected = true, disabled = false, pageId = null)))

    private fun repositories(): Triple<MediaLibraryRepository, MediaSaveRepository, MediaPlaylistRepository> {
        val client = MediaClient(OkHttpClient.Builder().addInterceptor(Recording(calls)).build()) { MediaSession.freshContext() }
        return Triple(MediaLibraryRepository(client, local),
            MediaSaveRepository(client, local), MediaPlaylistRepository(client, local))
    }

    private fun entry(id: String) = HistoryEntry(id, id, "Title $id", "Author", "channel", "art", "1:00", section = "Today")

    @Test fun anonymousReadsAndWritesStayOnTheDevice() = runBlocking {
        val (library, saves, _) = repositories()
        local.record(entry("seed"))
        val accountCalls = calls.size
        assertEquals(listOf("seed"), library.history().entries.map { it.videoId })
        library.playlists()
        saves.choices("seed")
        saves.saveWatchLater("seed")
        saves.setContaining("local:watch-later", "seed", false)
        saves.create("Mine", "", "seed")
        library.removeFromHistory("seed")
        library.setHistoryPaused(true)
        library.setHistoryPaused(false)
        library.clearHistory()
        assertEquals("no server call may fire for an anonymous library", accountCalls, calls.size)
    }

    @Test fun signedInReadsAndWritesHitTheServerAndLeaveTheDeviceAlone() = runBlocking {
        signIn()
        local.record(entry("seed"))
        val (library, saves, playlist) = repositories()
        val before = calls.size
        library.history()
        library.playlists()
        saves.choices("seed")
        saves.saveWatchLater("srv-video")
        assertTrue("signed-in writes must reach the server", calls.size > before)
        assertTrue(calls.any { it.contains("\"playlistId\":\"WL\"") && it.contains("srv-video") })
        assertEquals(listOf("seed"), local.history().entries.map { it.videoId })
        assertTrue(local.playlist(LocalLibraryStore.WATCH_LATER)!!.entries.isEmpty())
        // "WL" resolves to the server list, not the device one (parse shape is irrelevant here).
        runCatching { playlist.playlist("WL") }
        assertTrue(calls.last().contains("WL"))
    }

    @Test fun removingSavedVideosAfterClearingHistoryNeverNeedsTheNetwork() = runBlocking {
        val seed = entry("saved")
        local.record(seed)
        val playlist = local.create("Offline", "", seed)
        local.setContaining(LocalLibraryStore.WATCH_LATER, seed, true)
        local.clear()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val offline = MediaClient(OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            throw java.io.IOException("offline")
        }.build())
        val saves = MediaSaveRepository(offline, local)

        saves.setContaining(playlist.id, seed.videoId, false)
        saves.setContaining(LocalLibraryStore.WATCH_LATER, seed.videoId, false)

        assertEquals(0, requests.get())
        assertTrue(local.playlist(playlist.id)!!.entries.isEmpty())
        assertTrue(local.playlist(LocalLibraryStore.WATCH_LATER)!!.entries.isEmpty())
        assertTrue(local.history().entries.isEmpty())
    }

    @Test fun theWholeAppAnonymousSwitchKeepsEveryLibraryCallLocalWhileCredentialsStay() = runBlocking {
        signIn()
        MediaSession.setAnonymousMode(true)
        local.record(entry("seed"))
        val (library, saves, playlist) = repositories()
        val before = calls.size
        assertEquals(listOf("seed"), library.history().entries.map { it.videoId })
        library.playlists()
        saves.choices("seed")
        saves.saveWatchLater("seed")
        saves.create("Mine", "", "seed")
        library.setHistoryPaused(true)
        library.setHistoryPaused(false)
        assertEquals("no server call may fire while the app is anonymous", before, calls.size)
        assertEquals(listOf("seed"), local.playlist(LocalLibraryStore.WATCH_LATER)!!.entries.map { it.videoId })
        assertEquals("refresh", sessionStore.read(MediaSession.KEY_REFRESH_TOKEN))
        MediaSession.setAnonymousMode(false)
        saves.saveWatchLater("srv-video")
        assertTrue(calls.size > before)
    }

    @Test fun identityTransitionsKeepDeviceDataAndNeverWriteTheServer() = runBlocking {
        val (library, saves, _) = repositories()
        local.record(entry("seed"))
        saves.saveWatchLater("seed")
        val deviceBefore = local.history().entries.map { it.videoId } to
            local.playlist(LocalLibraryStore.WATCH_LATER)!!.entries.map { it.videoId }
        val callsBefore = calls.size
        signIn()
        MediaSession.selectAccount("owner")
        MediaSession.signOut()
        assertEquals("transitions never write the server", callsBefore, calls.size)
        assertEquals(deviceBefore, local.history().entries.map { it.videoId } to
            local.playlist(LocalLibraryStore.WATCH_LATER)!!.entries.map { it.videoId })
        assertEquals(listOf("seed"), library.history().entries.map { it.videoId })
        assertEquals(callsBefore, calls.size)
    }

    @Test fun watchLaterIsADistinctListPerIdentity() = runBlocking {
        signIn()
        local.record(entry("local-one"))
        val (_, saves, playlist) = repositories()
        saves.saveWatchLater("srv-video")
        assertTrue(calls.any { it.contains("\"playlistId\":\"WL\"") })
        assertTrue(local.playlist(LocalLibraryStore.WATCH_LATER)!!.entries.isEmpty())
        MediaSession.setAnonymousMode(true)
        saves.saveWatchLater("local-one")
        val callsAfterDeviceSave = calls.size
        assertEquals(listOf("local-one"), local.playlist(LocalLibraryStore.WATCH_LATER)!!.entries.map { it.videoId })
        assertEquals("Watch later", playlist.playlist("WL")?.summary?.title)
        assertEquals(callsAfterDeviceSave, calls.size)
    }

    @Test fun devicePlaylistIdsCannotBeSentToTheAccount() = runBlocking {
        signIn()
        val (_, saves, _) = repositories()
        val before = calls.size
        val result = runCatching { saves.setContaining(LocalLibraryStore.WATCH_LATER, "seed", true) }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(before, calls.size)
    }

    @Test fun accountPlaylistChoicesCannotMutateTheDevice() = runBlocking {
        signIn()
        MediaSession.setAnonymousMode(true)
        val (_, saves, _) = repositories()
        val result = runCatching { saves.setContaining("server-playlist", "seed", true) }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(calls.isEmpty())
    }

    @Test fun queuedAccountWritesAreCancelledAfterTheIdentitySwitches() = runBlocking {
        signIn()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val client = MediaClient(OkHttpClient.Builder().addInterceptor(Recording(calls)).build()) {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            MediaSession.snapshot()
        }
        val saves = MediaSaveRepository(client, local)
        val pending = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).let { scope ->
            scope.async { runCatching { saves.saveWatchLater("server-video") } }
        }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            MediaSession.setAnonymousMode(true)
        } finally { release.countDown() }
        assertTrue(pending.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertTrue(calls.isEmpty())
        assertTrue(local.playlist(LocalLibraryStore.WATCH_LATER)!!.entries.isEmpty())
    }

}
