package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.AccessToken
import com.liskovsoft.youtubeapi.videoinfo.V2.Account
import com.liskovsoft.youtubeapi.videoinfo.V2.AccountToken
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.SignInClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MediaSessionTest {

    private lateinit var store: MemoryAuthStore

    @Before fun setUp() {
        store = MemoryAuthStore()
        MediaSession.initialize(store)
    }
    @After fun tearDown() {
        MediaSession.tokenFactory = { initial, client -> AccountToken(initial, client) }
        MediaSession.initialize(MemoryAuthStore())
    }

    private fun client() = SignInClient("id", "secret", "device")
    private fun token(access: String = "access", refresh: String = "refresh", expires: Long = 4_000_000_000_000) =
        AccessToken(access, refresh, expires)
    private fun accounts() = listOf(
        Account("owner", "Owner", "owner@example.com", "https://photo/owner", selected = true, disabled = false, pageId = null),
        Account("brand", "Brand", null, "", selected = false, disabled = false, pageId = "brand-page"))

    @Test fun anonymousVisitorPersistsWithoutBecomingAnAccountAndSurvivesSignOut() {
        val generation = MediaSession.snapshot().generation
        MediaSession.rememberVisitor("visitor-one", generation)
        assertFalse(MediaSession.signedIn())
        assertEquals("visitor-one", MediaSession.freshContext().visitor)
        MediaSession.rememberVisitor("replacement", generation)
        assertEquals("visitor-one", MediaSession.snapshot().visitor)
        MediaSession.completeSignIn(client(), token(), accounts())
        assertNull(MediaSession.snapshot().visitor)
        MediaSession.signOut()
        MediaSession.initialize(store)
        assertEquals("visitor-one", MediaSession.snapshot().visitor)
    }

    @Test fun startsAnonymousWithoutPersistedState() {
        assertFalse(MediaSession.signedIn())
        assertTrue(MediaSession.accounts().isEmpty())
        assertNull(MediaSession.snapshot().accessToken)
    }

    @Test fun localOperationCannotCrossAnIdentityChange() {
        val generation = MediaSession.snapshot().generation
        var writes = 0
        MediaSession.withAnonymousSession(generation) { writes++ }
        MediaSession.completeSignIn(client(), token(), accounts())
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            MediaSession.withAnonymousSession(generation) { writes++ }
        }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            MediaSession.withAnonymousSession(MediaSession.snapshot().generation) { writes++ }
        }
        MediaSession.signOut()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            MediaSession.withAnonymousSession(generation) { writes++ }
        }
        MediaSession.withAnonymousSession(MediaSession.snapshot().generation) { writes++ }
        assertEquals(2, writes)
    }

    @Test fun completeSignInPersistsAndBumpsTheGeneration() {
        val before = MediaSession.snapshot().generation
        MediaSession.completeSignIn(client(), token(), accounts())
        assertTrue(MediaSession.signedIn())
        assertTrue(MediaSession.snapshot().generation > before)
        assertEquals("owner", MediaSession.snapshot().accountId)
        assertEquals(2, MediaSession.accounts().size)
        assertEquals("refresh", store.read(MediaSession.KEY_REFRESH_TOKEN))
        assertEquals("owner", store.read(MediaSession.KEY_ACCOUNT_ID))
        assertEquals("id", store.read(MediaSession.KEY_CLIENT_ID))
        assertEquals("owner", MediaSession.selectedAccount()?.id)
    }

    @Test fun restoredSessionSurvivesAProcessRestart() {
        MediaSession.completeSignIn(client(), token(), accounts())
        MediaSession.initialize(store) // process restart: fresh in-memory state, same store
        assertTrue(MediaSession.signedIn())
        assertEquals("owner", MediaSession.snapshot().accountId)
        assertEquals(listOf("owner", "brand"), MediaSession.accounts().map { it.id })
        assertEquals("brand-page", MediaSession.accounts().first { it.id == "brand" }.pageId)
        assertEquals("owner", MediaSession.selectedAccount()?.id)
    }

    @Test fun selectAccountPersistsAndBumps() {
        MediaSession.completeSignIn(client(), token(), accounts())
        val generation = MediaSession.snapshot().generation
        assertTrue(MediaSession.selectAccount("brand"))
        assertEquals("brand", MediaSession.snapshot().accountId)
        assertEquals("brand-page", MediaSession.snapshot().onBehalfOfUser)
        assertTrue(MediaSession.snapshot().generation > generation)
        assertEquals("brand", store.read(MediaSession.KEY_ACCOUNT_ID))
        assertFalse(MediaSession.selectAccount("missing"))
        MediaSession.initialize(store)
        assertEquals("brand", MediaSession.snapshot().accountId)
    }

    @Test fun signOutWipesTheAccountStateButKeepsTheInstallCredentials() {
        MediaSession.completeSignIn(client(), token(), accounts())
        MediaSession.signOut()
        assertFalse(MediaSession.signedIn())
        assertTrue(MediaSession.accounts().isEmpty())
        assertNull(store.read(MediaSession.KEY_REFRESH_TOKEN))
        assertNull(store.read(MediaSession.KEY_ACCOUNT_ID))
        assertNull(store.read(MediaSession.KEY_ACCESS_TOKEN))
        assertEquals("id", store.read(MediaSession.KEY_CLIENT_ID))
        assertEquals("device", store.read(MediaSession.KEY_DEVICE_ID))
    }

    @Test fun freshContextRefreshesWithoutMovingTheGeneration() {
        val refreshHttp = object : HttpClient {
            override fun execute(request: HttpRequest) =
                HttpReply(200, """{"access_token":"fresh-access","expires_in":3600}""", emptyMap())
        }
        MediaSession.tokenFactory = { initial, client -> AccountToken(initial, client, refreshHttp) }
        MediaSession.completeSignIn(client(), token(expires = 0), accounts()) // already expired
        val context = MediaSession.snapshot()
        val fresh = MediaSession.freshContext()
        assertEquals("fresh-access", fresh.accessToken)
        assertEquals(context.generation, fresh.generation)
        assertEquals("fresh-access", store.read(MediaSession.KEY_ACCESS_TOKEN))
    }

    @Test fun freshContextKeepsTheCachedTokenWhileItIsValid() {
        var calls = 0
        val counting = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply { calls++; return HttpReply(500, "", emptyMap()) }
        }
        MediaSession.tokenFactory = { initial, client -> AccountToken(initial, client, counting) }
        MediaSession.completeSignIn(client(), token(), accounts())
        assertEquals("access", MediaSession.freshContext().accessToken)
        assertEquals(0, calls)
    }

    @Test fun persistedRefreshTokenRecoversAMissingAccessToken() {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) =
                HttpReply(200, """{"access_token":"recovered","expires_in":3600}""", emptyMap())
        }
        MediaSession.tokenFactory = { initial, client -> AccountToken(initial, client, http) }
        MediaSession.completeSignIn(client(), token(access = "", expires = 0), accounts())
        MediaSession.initialize(store)
        assertEquals("recovered", MediaSession.freshContext().accessToken)
    }

    @Test fun aRefreshCannotRestoreCredentialsAfterSignOut() = refreshAcrossIdentityChange {
        MediaSession.signOut()
        val verify: () -> Unit = { assertFalse(MediaSession.signedIn()); assertNull(store.read(MediaSession.KEY_ACCESS_TOKEN)) }
        verify
    }

    @Test fun aRefreshCannotOverwriteANewerSignIn() = refreshAcrossIdentityChange {
        MediaSession.completeSignIn(client(), token("new-account-access", "new-account-refresh"), accounts())
        val verify: () -> Unit = { assertEquals("new-account-access", store.read(MediaSession.KEY_ACCESS_TOKEN)) }
        verify
    }

    private fun refreshAcrossIdentityChange(change: () -> (() -> Unit)) {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                started.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return HttpReply(200, """{"access_token":"old-refreshed","expires_in":3600}""", emptyMap())
            }
        }
        MediaSession.tokenFactory = { initial, client -> AccountToken(initial, client, http) }
        MediaSession.completeSignIn(client(), token(expires = 0), accounts())
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val work = executor.submit<Throwable?> { runCatching { MediaSession.freshContext() }.exceptionOrNull() }
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val verify = change()
            release.countDown()
            assertEquals("session_changed", work.get(3, java.util.concurrent.TimeUnit.SECONDS)?.message)
            verify()
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun freshContextWithoutASessionStaysAnonymous() {
        assertNull(MediaSession.freshContext().accessToken)
        assertFalse(MediaSession.freshContext().signedIn)
    }

    @Test fun accountFieldsSurviveTheEncodingRoundTrip() {
        val tricky = listOf(Account("id 1", "Name With Spaces & +=", "mail@example.com",
            "https://p/x y?z=1&2", selected = true, disabled = false, pageId = "page id"))
        MediaSession.completeSignIn(client(), token(), tricky)
        MediaSession.initialize(store)
        val restored = MediaSession.accounts().single()
        assertEquals("Name With Spaces & +=", restored.name)
        assertEquals("https://p/x y?z=1&2", restored.photo)
        assertEquals("page id", restored.pageId)
        assertEquals("id 1", MediaSession.snapshot().accountId)
    }

    @Test fun completeSignInAnchorsTheFirstEnabledAccountWhenNoneIsSelected() {
        val unselected = accounts().map { it.copy(selected = false) }
        MediaSession.completeSignIn(client(), token(), unselected)
        assertEquals("owner", MediaSession.snapshot().accountId)
    }

    @Test fun disabledAccountsStayDisabledAfterRestart() {
        MediaSession.completeSignIn(client(), token(), accounts() +
            Account("disabled", "Disabled", null, null, false, true, null))
        MediaSession.initialize(store)
        assertTrue(MediaSession.accounts().single { it.id == "disabled" }.disabled)
        assertFalse(MediaSession.selectAccount("disabled"))
    }

    @Test fun malformedCachedAccountRowsDoNotPreventStartup() {
        MediaSession.completeSignIn(client(), token(), accounts())
        store.write(MediaSession.KEY_ACCOUNTS, store.read(MediaSession.KEY_ACCOUNTS) + "\ninvalid%zz row")
        MediaSession.initialize(store)
        assertEquals(2, MediaSession.accounts().size)
    }
}
