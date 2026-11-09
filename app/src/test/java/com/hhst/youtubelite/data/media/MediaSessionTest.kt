package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.AccessToken
import com.liskovsoft.youtubeapi.videoinfo.V2.Account
import com.liskovsoft.youtubeapi.videoinfo.V2.AccountToken
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.SignInClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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

    @Test fun anonymousVisitorAndCookieRemainPairedAcrossIdentitySwitches() {
        val generation = MediaSession.snapshot().generation
        MediaSession.rememberVisitorIdentity("visitor", "VISITOR_INFO1_LIVE=cookie; SOCS=CAI", generation)
        MediaSession.initialize(store)
        assertEquals("visitor", MediaSession.freshContext().visitor)
        assertEquals("VISITOR_INFO1_LIVE=cookie; SOCS=CAI", MediaSession.freshContext().visitorCookie)
        assertTrue(MediaSession.anonymousHomeIdentityVerified(MediaSession.snapshot()))
        MediaSession.completeSignIn(client(), token(), accounts())
        assertNull(MediaSession.snapshot().visitorCookie)
        MediaSession.setAnonymousMode(true)
        assertEquals("visitor", MediaSession.snapshot().visitor)
        assertEquals("VISITOR_INFO1_LIVE=cookie; SOCS=CAI", MediaSession.snapshot().visitorCookie)
        MediaSession.selectAccount("owner")
        assertNull(MediaSession.snapshot().visitorCookie)
    }

    @Test fun unverifiedStoredPairIsRepairedOnceWithoutReplacingTheCookieIdentity() {
        store.write(MediaSession.KEY_VISITOR, "unrelated-api-visitor")
        store.write(MediaSession.KEY_VISITOR_COOKIE, "VISITOR_INFO1_LIVE=keep")
        MediaSession.initialize(store)
        val context = MediaSession.snapshot()
        assertNull(context.visitor)
        assertEquals("VISITOR_INFO1_LIVE=keep", context.visitorCookie)
        assertFalse(MediaSession.anonymousHomeIdentityVerified(context))
        MediaSession.rememberVisitor("another-api-visitor", context.generation)
        assertNull(MediaSession.snapshot().visitor)
        MediaSession.rememberVisitorIdentity("document-visitor", context.visitorCookie!!, context.generation)
        MediaSession.initialize(store)
        assertEquals("document-visitor", MediaSession.snapshot().visitor)
        assertTrue(MediaSession.anonymousHomeIdentityVerified(MediaSession.snapshot()))
        MediaSession.rememberVisitorIdentity("late-response", "VISITOR_INFO1_LIVE=other", MediaSession.snapshot().generation)
        assertEquals("document-visitor", MediaSession.snapshot().visitor)
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

    @Test fun selectingASavedAccountExitsDeviceIdentityAndSurvivesRestart() {
        MediaSession.completeSignIn(client(), token(), accounts())
        MediaSession.setAnonymousMode(true)
        assertFalse(MediaSession.signedIn())
        assertFalse(MediaSession.selectAccount("missing"))
        assertFalse(MediaSession.signedIn())
        assertTrue(MediaSession.selectAccount("brand"))
        assertTrue(MediaSession.signedIn())
        assertEquals("brand", MediaSession.snapshot().accountId)
        MediaSession.initialize(store)
        assertTrue(MediaSession.signedIn())
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
        MediaSession.completeSignIn(client(), token(expires = 0), accounts())
        val context = MediaSession.snapshot()
        val fresh = MediaSession.freshContext()
        assertEquals("fresh-access", fresh.accessToken)
        assertEquals("fresh-access", MediaSession.snapshot().accessToken)
        assertEquals(context.generation, fresh.generation)
        assertEquals(context.generation, MediaSession.snapshot().generation)
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

    @Test fun repeatedFreshContextsDoNotReadOrWriteAnAlreadyPersistedAccessPair() {
        val counting = CountingAuthStore(store)
        MediaSession.initialize(counting)
        MediaSession.completeSignIn(client(), token(), accounts())
        val generation = MediaSession.snapshot().generation
        counting.reset()

        repeat(20) {
            val fresh = MediaSession.freshContext()
            assertEquals("access", fresh.accessToken)
            assertEquals(generation, fresh.generation)
        }

        assertEquals(emptyList<String>(), counting.writes)
        assertEquals(0, counting.reads)
    }

    @Test fun restoredAccessPairIsVerifiedOnceEvenWhenContextsAreConcurrent() {
        MediaSession.completeSignIn(client(), token(), accounts())
        val counting = CountingAuthStore(store)
        MediaSession.initialize(counting)
        counting.reset()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val reads = List(20) { executor.submit<String?> { MediaSession.freshContext().accessToken } }
            reads.forEach { assertEquals("access", it.get(3, java.util.concurrent.TimeUnit.SECONDS)) }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(listOf(MediaSession.KEY_ACCESS_TOKEN, MediaSession.KEY_ACCESS_EXPIRES), counting.writes)
        assertEquals(0, counting.reads)
    }

    @Test fun refreshedAccessAndExpiryArePersistedOnlyOnce() {
        var calls = 0
        val refreshHttp = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls++
                return HttpReply(200, """{"access_token":"fresh-access","expires_in":3600}""", emptyMap())
            }
        }
        MediaSession.tokenFactory = { initial, client -> AccountToken(initial, client, refreshHttp) }
        val counting = CountingAuthStore(store)
        MediaSession.initialize(counting)
        MediaSession.completeSignIn(client(), token(expires = 0), accounts())
        val generation = MediaSession.snapshot().generation
        counting.reset()

        repeat(10) {
            assertEquals("fresh-access", MediaSession.freshContext().accessToken)
            assertEquals(generation, MediaSession.snapshot().generation)
        }

        assertEquals(1, calls)
        assertEquals(listOf(MediaSession.KEY_ACCESS_TOKEN, MediaSession.KEY_ACCESS_EXPIRES), counting.writes)
        assertEquals("fresh-access", store.read(MediaSession.KEY_ACCESS_TOKEN))
        assertTrue(store.read(MediaSession.KEY_ACCESS_EXPIRES)!!.toLong() > System.currentTimeMillis())
    }

    @Test fun aRefreshWithTheSameAccessTokenStillPersistsItsNewExpiry() {
        val refreshHttp = object : HttpClient {
            override fun execute(request: HttpRequest) =
                HttpReply(200, """{"access_token":"access","expires_in":3600}""", emptyMap())
        }
        MediaSession.tokenFactory = { initial, client -> AccountToken(initial, client, refreshHttp) }
        val counting = CountingAuthStore(store)
        MediaSession.initialize(counting)
        MediaSession.completeSignIn(client(), token(expires = 0), accounts())
        counting.reset()

        assertEquals("access", MediaSession.freshContext().accessToken)
        MediaSession.freshContext()

        assertEquals(listOf(MediaSession.KEY_ACCESS_TOKEN, MediaSession.KEY_ACCESS_EXPIRES), counting.writes)
        assertTrue(store.read(MediaSession.KEY_ACCESS_EXPIRES)!!.toLong() > System.currentTimeMillis())
    }

    @Test fun aPartlyFailedAccessPairWriteIsRetriedBeforeItCanBeSkipped() {
        var refreshes = 0
        val refreshHttp = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                refreshes++
                return HttpReply(200, """{"access_token":"fresh-access","expires_in":3600}""", emptyMap())
            }
        }
        MediaSession.tokenFactory = { initial, client -> AccountToken(initial, client, refreshHttp) }
        val counting = CountingAuthStore(store)
        MediaSession.initialize(counting)
        MediaSession.completeSignIn(client(), token(expires = 0), accounts())
        counting.reset()
        counting.failOnceOn = MediaSession.KEY_ACCESS_EXPIRES

        assertEquals("auth_write_failed", assertThrows(IllegalStateException::class.java) {
            MediaSession.freshContext()
        }.message)
        assertEquals("0", store.read(MediaSession.KEY_ACCESS_EXPIRES))
        assertEquals("fresh-access", MediaSession.freshContext().accessToken)
        MediaSession.freshContext()

        assertEquals(1, refreshes)
        assertEquals(List(2) { listOf(MediaSession.KEY_ACCESS_TOKEN, MediaSession.KEY_ACCESS_EXPIRES) }.flatten(), counting.writes)
        assertTrue(store.read(MediaSession.KEY_ACCESS_EXPIRES)!!.toLong() > System.currentTimeMillis())
    }

    @Test fun aNewStoreDoesNotInheritThePreviousStoresPersistenceConfirmation() {
        val first = CountingAuthStore(store)
        MediaSession.initialize(first)
        MediaSession.completeSignIn(client(), token(), accounts())
        val replacement = MemoryAuthStore().also { it.values.putAll(store.values) }
        val second = CountingAuthStore(replacement)
        MediaSession.initialize(second)
        second.reset()

        MediaSession.freshContext()
        MediaSession.freshContext()

        assertEquals(listOf(MediaSession.KEY_ACCESS_TOKEN, MediaSession.KEY_ACCESS_EXPIRES), second.writes)
    }

    @Test fun persistedRefreshTokenRecoversAMissingAccessToken() {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) =
                HttpReply(200, """{"access_token":"recovered","expires_in":3600}""", emptyMap())
        }
        MediaSession.tokenFactory = { initial, client -> AccountToken(initial, client, http) }
        MediaSession.completeSignIn(client(), token(access = "", expires = 0), accounts())
        MediaSession.initialize(store)
        val generation = MediaSession.snapshot().generation
        assertEquals("recovered", MediaSession.freshContext().accessToken)
        assertTrue(MediaSession.signedIn())
        assertEquals("recovered", MediaSession.snapshot().accessToken)
        assertEquals("owner", MediaSession.snapshot().accountId)
        assertEquals(generation, MediaSession.snapshot().generation)
        assertFalse(useDeviceLibrary(LocalLibraryStore(MemoryAuthStore())))
        assertTrue(MediaSession.updateAccounts(accounts(), generation))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun recoveringAMissingTokenPublishesSignedInWithoutMovingTheGeneration() = runTest {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) =
                HttpReply(200, """{"access_token":"recovered","expires_in":3600}""", emptyMap())
        }
        MediaSession.tokenFactory = { initial, client -> AccountToken(initial, client, http) }
        MediaSession.completeSignIn(client(), token(access = "", expires = 0), accounts())
        MediaSession.initialize(store)
        val generation = MediaSession.snapshot().generation
        val observed = mutableListOf<MediaSessionIdentity>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { MediaSession.changes.toList(observed) }

        MediaSession.freshContext()
        MediaSession.freshContext()

        assertEquals(listOf(MediaSessionIdentity(generation, false), MediaSessionIdentity(generation, true)), observed)
        assertEquals(generation, MediaSession.snapshot().generation)
        MediaSession.signOut()
        val signedOut = MediaSession.snapshot()
        assertEquals(MediaSessionIdentity(signedOut.generation, false), observed.last())
        assertNotEquals(generation, signedOut.generation)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun rotatingAnExistingTokenDoesNotPublishAnIdentityChange() = runTest {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) =
                HttpReply(200, """{"access_token":"rotated","expires_in":3600}""", emptyMap())
        }
        MediaSession.tokenFactory = { initial, client -> AccountToken(initial, client, http) }
        MediaSession.completeSignIn(client(), token(expires = 0), accounts())
        val identity = MediaSession.changes.value
        val observed = mutableListOf<MediaSessionIdentity>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { MediaSession.changes.toList(observed) }

        assertEquals("rotated", MediaSession.freshContext().accessToken)
        MediaSession.freshContext()

        assertEquals(listOf(identity), observed)
        assertEquals(identity.generation, MediaSession.snapshot().generation)
    }

    @Test fun reinitializingWithoutCredentialsPublishesTheActualAnonymousIdentity() {
        MediaSession.completeSignIn(client(), token(), accounts())
        MediaSession.initialize(MemoryAuthStore())
        val current = MediaSession.snapshot()
        assertEquals(MediaSessionIdentity(current.generation, false), MediaSession.changes.value)
    }

    @Test fun aMissingAccessTokenCannotReuseThePersistedFutureExpiry() {
        var refreshes = 0
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                refreshes++
                return HttpReply(200, """{"access_token":"recovered","expires_in":3600}""", emptyMap())
            }
        }
        MediaSession.tokenFactory = { initial, client -> AccountToken(initial, client, http) }
        MediaSession.completeSignIn(client(), token(), accounts())
        store.write(MediaSession.KEY_ACCESS_TOKEN, null)
        MediaSession.initialize(store)
        val generation = MediaSession.snapshot().generation

        assertEquals("recovered", MediaSession.freshContext().accessToken)
        assertEquals("recovered", MediaSession.freshContext().accessToken)
        assertEquals(1, refreshes)
        assertTrue(MediaSession.signedIn())
        assertEquals("recovered", store.read(MediaSession.KEY_ACCESS_TOKEN))
        assertEquals(generation, MediaSession.snapshot().generation)
    }

    @Test fun aRecoveredAccessTokenCanBootstrapTheAccountList() {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) =
                HttpReply(200, """{"access_token":"recovered","expires_in":3600}""", emptyMap())
        }
        MediaSession.tokenFactory = { initial, client -> AccountToken(initial, client, http) }
        MediaSession.completeSignIn(client(), token(access = "", expires = 0), emptyList())
        MediaSession.initialize(store)
        MediaSession.freshContext()

        assertTrue(MediaSession.updateAccounts(accounts(), MediaSession.snapshot().generation))
        assertEquals("owner", MediaSession.snapshot().accountId)
        assertEquals(2, MediaSession.accounts().size)
        assertTrue(MediaSession.signedIn())
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

    @Test fun aRefreshCannotPersistAcrossTheAnonymousSwitch() = refreshAcrossIdentityChange {
        MediaSession.setAnonymousMode(true)
        val verify: () -> Unit = {
            assertFalse(MediaSession.freshContext().signedIn)
            assertEquals("access", store.read(MediaSession.KEY_ACCESS_TOKEN))
            MediaSession.setAnonymousMode(false)
            assertEquals("old-refreshed", MediaSession.freshContext().accessToken)
            assertEquals("old-refreshed", store.read(MediaSession.KEY_ACCESS_TOKEN))
        }
        verify
    }

    @Test fun aRefreshCannotPersistAcrossAnAccountSelection() = refreshAcrossIdentityChange {
        assertTrue(MediaSession.selectAccount("brand"))
        val verify: () -> Unit = {
            assertEquals("access", store.read(MediaSession.KEY_ACCESS_TOKEN))
            val fresh = MediaSession.freshContext()
            assertEquals("brand", fresh.accountId)
            assertEquals("brand-page", fresh.onBehalfOfUser)
            assertEquals("old-refreshed", fresh.accessToken)
        }
        verify
    }

    private class CountingAuthStore(private val delegate: AuthStore) : AuthStore {
        val writes = mutableListOf<String>()
        var reads = 0
        var failOnceOn: String? = null
        override fun read(key: String): String? {
            reads++
            return delegate.read(key)
        }
        override fun write(key: String, value: String?) {
            writes += key
            if (key == failOnceOn) {
                failOnceOn = null
                throw IllegalStateException("auth_write_failed")
            }
            delegate.write(key, value)
        }
        fun reset() {
            writes.clear()
            reads = 0
        }
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
            val current = MediaSession.snapshot()
            assertEquals(MediaSessionIdentity(current.generation, current.signedIn), MediaSession.changes.value)
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

    @Test fun credentialsSurviveAProfileBootstrapWithoutAccounts() {
        MediaSession.completeSignIn(client(), token(), emptyList())
        MediaSession.initialize(store)
        assertTrue(MediaSession.signedIn())
        assertEquals("access", MediaSession.snapshot().accessToken)
        assertTrue(MediaSession.updateAccounts(accounts(), MediaSession.snapshot().generation))
        MediaSession.initialize(store)
        assertEquals("owner", MediaSession.snapshot().accountId)
    }

    @Test fun lateProfilesCannotRestoreASignedOutSession() {
        MediaSession.completeSignIn(client(), token(), emptyList())
        val generation = MediaSession.snapshot().generation
        MediaSession.signOut()
        assertFalse(MediaSession.updateAccounts(accounts(), generation))
        assertFalse(MediaSession.signedIn())
        assertTrue(MediaSession.accounts().isEmpty())
    }

    @Test fun anonymousModeForcesTheWholeAppOntoTheDeviceIdentityWhileKeepingCredentials() {
        MediaSession.completeSignIn(client(), token(), accounts())
        assertTrue(MediaSession.signedIn())
        val generation = MediaSession.snapshot().generation
        assertTrue(MediaSession.setAnonymousMode(true))
        assertFalse(MediaSession.signedIn())
        assertFalse(MediaSession.snapshot().signedIn)
        assertNull(MediaSession.snapshot().accessToken)
        assertNull(MediaSession.snapshot().accountId)
        assertTrue(MediaSession.snapshot().generation > generation)
        assertTrue(MediaSession.anonymousMode())
        assertEquals("refresh", store.read(MediaSession.KEY_REFRESH_TOKEN))
        assertEquals("owner", store.read(MediaSession.KEY_ACCOUNT_ID))
    }

    @Test fun freshContextNeverAttachesATokenWhileAnonymousModeIsEngaged() {
        val refreshHttp = object : HttpClient {
            override fun execute(request: HttpRequest) =
                HttpReply(200, """{"access_token":"fresh-access","expires_in":3600}""", emptyMap())
        }
        MediaSession.tokenFactory = { initial, refreshClient -> AccountToken(initial, refreshClient, refreshHttp) }
        MediaSession.completeSignIn(client(), token(expires = 0), accounts())
        MediaSession.setAnonymousMode(true)
        val fresh = MediaSession.freshContext()
        assertNull(fresh.accessToken)
        assertFalse(fresh.signedIn)
        assertEquals("access", store.read(MediaSession.KEY_ACCESS_TOKEN))
        MediaSession.setAnonymousMode(false)
        assertEquals("fresh-access", MediaSession.freshContext().accessToken)
    }

    @Test fun anonymousModePersistsAcrossARestartAndSwitchingBackSelectsTheAccount() {
        MediaSession.completeSignIn(client(), token(), accounts())
        val owner = MediaSession.snapshot().accountId
        MediaSession.setAnonymousMode(true)
        MediaSession.initialize(store)
        assertTrue(MediaSession.anonymousMode())
        assertFalse(MediaSession.signedIn())
        assertTrue(MediaSession.setAnonymousMode(false))
        assertTrue(MediaSession.signedIn())
        assertEquals(owner, MediaSession.snapshot().accountId)
        MediaSession.initialize(store)
        assertFalse(MediaSession.anonymousMode())
        assertTrue(MediaSession.signedIn())
    }

    @Test fun signingInAlwaysEndsTheAnonymousSwitch() {
        MediaSession.completeSignIn(client(), token(), accounts())
        MediaSession.setAnonymousMode(true)
        MediaSession.signOut()
        MediaSession.completeSignIn(client(), token("second-access"), accounts())
        assertFalse(MediaSession.anonymousMode())
        assertTrue(MediaSession.signedIn())
        assertEquals("second-access", MediaSession.snapshot().accessToken)
        assertNull(store.read(MediaSession.KEY_ANONYMOUS_MODE))
    }

    @Test fun setAnonymousModeIsIdempotent() {
        assertFalse(MediaSession.setAnonymousMode(false)) // already off
        assertTrue(MediaSession.setAnonymousMode(true))
        assertFalse(MediaSession.setAnonymousMode(true))
    }
}
