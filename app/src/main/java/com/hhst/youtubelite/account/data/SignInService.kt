package com.hhst.youtubelite.account.data

import com.hhst.youtubelite.data.media.AuthStore
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.requireValue
import com.liskovsoft.youtubeapi.videoinfo.V2.AccessToken
import com.liskovsoft.youtubeapi.videoinfo.V2.Account
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.SignInClient
import com.liskovsoft.youtubeapi.videoinfo.V2.SignInCode
import com.liskovsoft.youtubeapi.videoinfo.V2.loadAccounts
import com.liskovsoft.youtubeapi.videoinfo.V2.loadSignInCode
import com.liskovsoft.youtubeapi.videoinfo.V2.loadSignInCredentials
import com.liskovsoft.youtubeapi.videoinfo.V2.pollSignIn
import java.util.UUID

/** Device-code sign-in steps; credentials themselves are only ever entered in the browser. */
interface SignInService {
    suspend fun loadCode(): SignInCode
    suspend fun poll(code: SignInCode): AccessToken
    suspend fun complete(token: AccessToken): List<Account>
}

class MediaSignInService(http: okhttp3.OkHttpClient, private val store: AuthStore) : SignInService {
    private val client = MediaClient(http)
    private var resolved: SignInClient? = null

    override suspend fun loadCode(): SignInCode = client.read { _, http ->
        val signInClient = resolveClient(http)
        resolved = signInClient
        loadSignInCode(signInClient, http).requireValue()
    }

    override suspend fun poll(code: SignInCode): AccessToken = client.read { _, http ->
        pollSignIn(code, resolved ?: error("loadCode must run before poll"), http).requireValue()
    }

    override suspend fun complete(token: AccessToken): List<Account> {
        val signInClient = resolved ?: error("loadCode must run before complete")
        MediaSession.completeSignIn(signInClient, token, emptyList())
        val generation = MediaSession.snapshot().generation
        return try {
            val accounts = client.read { _, http ->
                loadAccounts(RequestContext(accessToken = token.accessToken), http).requireValue()
            }
            if (!MediaSession.updateAccounts(accounts, generation)) throw kotlinx.coroutines.CancellationException("Session changed")
            accounts
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            com.hhst.youtubelite.diagnostics.AppLog.event(
                com.hhst.youtubelite.diagnostics.AppLog.Category.APP, "sign_in.accounts_unavailable", failure = error)
            emptyList()
        }
    }

    private fun resolveClient(http: HttpClient): SignInClient {
        val id = store.read(MediaSession.KEY_CLIENT_ID)
        val secret = store.read(MediaSession.KEY_CLIENT_SECRET)
        val credentials = if (!id.isNullOrBlank() && !secret.isNullOrBlank()) {
            com.liskovsoft.youtubeapi.videoinfo.V2.SignInCredentials(id, secret)
        } else {
            loadSignInCredentials(http).requireValue().also {
                store.write(MediaSession.KEY_CLIENT_ID, it.clientId)
                store.write(MediaSession.KEY_CLIENT_SECRET, it.clientSecret)
            }
        }
        val device = store.read(MediaSession.KEY_DEVICE_ID)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { store.write(MediaSession.KEY_DEVICE_ID, it) }
        return SignInClient(credentials.clientId, credentials.clientSecret, device)
    }
}
