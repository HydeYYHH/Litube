package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.AccessToken
import com.liskovsoft.youtubeapi.videoinfo.V2.Account
import com.liskovsoft.youtubeapi.videoinfo.V2.AccountSession
import com.liskovsoft.youtubeapi.videoinfo.V2.AccountToken
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.SignInClient
import java.net.URLDecoder
import java.net.URLEncoder

/** Persistent auth fields backing [MediaSession]. Encrypted prefs in production, memory in tests. */
interface AuthStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

/**
 * Credential storage over SharedPreferences with every value sealed by
 * [com.hhst.youtubelite.core.CredentialCipher] (AndroidKeyStore AES-GCM).
 */
class PreferencesAuthStore(context: android.content.Context) : AuthStore {
    private val prefs = context.getSharedPreferences("media-auth", android.content.Context.MODE_PRIVATE)

    override fun read(key: String): String? =
        prefs.getString(key, null)?.let(com.hhst.youtubelite.core.CredentialCipher::decrypt)

    override fun write(key: String, value: String?) {
        if (value == null) {
            check(prefs.edit().remove(key).commit()) { "auth_write_failed" }
            return
        }
        // Identical values (e.g. an unchanged access token across refreshes)
        // must not rewrite the same credential on every request.
        if (read(key) == value) return
        check(prefs.edit().putString(key, com.hhst.youtubelite.core.CredentialCipher.encrypt(value)).commit()) {
            "auth_write_failed"
        }
    }
}

class MemoryAuthStore : AuthStore {
    val values = mutableMapOf<String, String>()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** Credential-free session state observed by UI and owner-scoped local stores. */
data class MediaSessionIdentity(val generation: Long, val signedIn: Boolean)

/**
 * A persisted refresh token yields access tokens
 * through [AccountToken]; [AccountSession] carries the account list and bumps the generation
 * on every identity change so in-flight reads from the previous identity are discarded.
 * Never persist or log access tokens beyond the auth keys below, and never log tokens at all.
 */
object MediaSession {
    internal const val KEY_CLIENT_ID = "auth:client_id"
    internal const val KEY_CLIENT_SECRET = "auth:client_secret"
    internal const val KEY_DEVICE_ID = "auth:device_id"
    internal const val KEY_REFRESH_TOKEN = "auth:refresh_token"
    internal const val KEY_ACCESS_TOKEN = "auth:access_token"
    internal const val KEY_ACCESS_EXPIRES = "auth:access_expires"
    internal const val KEY_ACCOUNT_ID = "auth:account_id"
    internal const val KEY_ACCOUNTS = "auth:accounts"
    internal const val KEY_VISITOR = "visitor:identity"
    internal const val KEY_VISITOR_COOKIE = "visitor:cookie"
    internal const val KEY_VISITOR_PAIR = "visitor:pair"
    /** Persisted "On this device" switch: the whole app runs anonymous; credentials stay. */
    internal const val KEY_ANONYMOUS_MODE = "auth:anonymous_mode"

    private val lock = Any()
    private var store: AuthStore? = null
    private var session = AccountSession()
    private var token: AccountToken? = null
    /** Access fields confirmed written to this store; guarded by [lock]. */
    private var persistedAccess: AccessToken? = null
    private var client: SignInClient? = null
    private var visitor: String? = null
    private var visitorCookie: String? = null
    private var visitorPairVerified = false
    private var anonymousMode = false
    /** Extra generation offset from anonymous-mode flips; the derived generation never collides. */
    private var identityEpoch = 0L
    private var privateMode: () -> Boolean = { false }
    private val _changes = kotlinx.coroutines.flow.MutableStateFlow(MediaSessionIdentity(0L, false))
    val changes: kotlinx.coroutines.flow.StateFlow<MediaSessionIdentity> = _changes

    private fun changed() {
        val current = snapshot()
        _changes.value = MediaSessionIdentity(current.generation, current.signedIn)
    }

    /** Test hook: the default [AccountToken] refreshes over the network. */
    internal var tokenFactory: (AccessToken, SignInClient) -> AccountToken = { initial, signInClient ->
        AccountToken(initial, signInClient)
    }

    /** Rebuilds the session from the store; safe to call again (tests reset state this way). */
    fun initialize(store: AuthStore, privateMode: () -> Boolean = { false }) {
        synchronized(lock) {
            this.store = store
            persistedAccess = null
            this.privateMode = privateMode
            visitor = store.read(KEY_VISITOR)?.takeIf { it.isNotBlank() }
            visitorCookie = store.read(KEY_VISITOR_COOKIE)?.takeIf { it.isNotBlank() }
            visitorPairVerified = visitor?.let { id -> visitorCookie?.let { cookie ->
                store.read(KEY_VISITOR_PAIR) == visitorPairDigest(id, cookie)
            } } == true
            if (visitorCookie != null && !visitorPairVerified) {
                visitor = null
                store.write(KEY_VISITOR, null)
            }
            anonymousMode = store.read(KEY_ANONYMOUS_MODE) == "1"
            session = AccountSession()
            token = null
            client = null
            val refresh = store.read(KEY_REFRESH_TOKEN)?.takeIf { it.isNotBlank() } ?: run {
                changed()
                return
            }
            val id = store.read(KEY_CLIENT_ID)
            val secret = store.read(KEY_CLIENT_SECRET)
            val device = store.read(KEY_DEVICE_ID)
            if (id.isNullOrBlank() || secret.isNullOrBlank() || device.isNullOrBlank()) {
                wipeLocked()
                changed()
                return
            }
            val signInClient = SignInClient(id, secret, device)
            val restored = AccessToken(store.read(KEY_ACCESS_TOKEN).orEmpty(), refresh,
                store.read(KEY_ACCESS_EXPIRES)?.toLongOrNull() ?: 0L)
            client = signInClient
            token = tokenFactory(restored, signInClient)
            session.replace(restored, decodeAccounts(store.read(KEY_ACCOUNTS).orEmpty(), store.read(KEY_ACCOUNT_ID)))
            changed()
        }
    }

    /** Main-thread safe: the cached context, possibly holding a stale access token.
     *  The persisted anonymous mode forces the whole app (Home, Watch, library) onto
     *  the device identity while the signed-in account stays selectable. */
    fun snapshot(): RequestContext = synchronized(lock) { session.snapshot().let {
        val base = when {
            anonymousMode -> it.anonymous().copy(visitor = visitor, visitorCookie = visitorCookie)
            it.signedIn -> it
            else -> it.copy(visitor = visitor, visitorCookie = visitorCookie)
        }
        base.copy(generation = base.generation + identityEpoch)
    } }
    fun privateBrowsing(): Boolean = privateMode()

    fun anonymousMode(): Boolean = synchronized(lock) { anonymousMode }

    /**
     * Switches the whole app between the device identity and the signed-in account.
     * Bumps the generation, so in-flight account reads abort with "session_changed"
     * and caches keyed by identity stop matching. Returns true when the mode changed.
     */
    fun setAnonymousMode(enabled: Boolean): Boolean = synchronized(lock) {
        if (anonymousMode == enabled) return false
        anonymousMode = enabled
        identityEpoch += 1
        store?.write(KEY_ANONYMOUS_MODE, if (enabled) "1" else null)
        changed()
        true
    }

    internal fun <T> withAnonymousSession(generation: Long, block: () -> T): T = synchronized(lock) {
        val current = snapshot()
        if (current.signedIn || current.generation != generation) throw kotlinx.coroutines.CancellationException("Session changed")
        block()
    }

    /** Device-store access for the explicit device library source: guarded against identity
     * changes only, so a signed-in user may still read and write on-device collections. */
    internal fun <T> withUnchangedSession(generation: Long, block: () -> T): T = synchronized(lock) {
        if (snapshot().generation != generation) throw kotlinx.coroutines.CancellationException("Session changed")
        block()
    }

    fun rememberVisitor(value: String?, generation: Long) = synchronized(lock) {
        if (value.isNullOrBlank() || value.length > 4096 || snapshot().generation != generation ||
            snapshot().signedIn || visitor != null || visitorCookie != null && !visitorPairVerified) return@synchronized
        visitor = value
        store?.write(KEY_VISITOR, value)
    }

    internal fun rememberVisitorIdentity(value: String?, cookie: String, generation: Long) = synchronized(lock) {
        val current = snapshot()
        if (current.signedIn || current.generation != generation || value.isNullOrBlank() || value.length > 4096 ||
            cookie.length > 4096 || cookie.any { it == '\r' || it == '\n' } || visitorPairVerified) return@synchronized
        store?.write(KEY_VISITOR, value)
        store?.write(KEY_VISITOR_COOKIE, cookie)
        store?.write(KEY_VISITOR_PAIR, visitorPairDigest(value, cookie))
        visitor = value
        visitorCookie = cookie
        visitorPairVerified = true
    }
    internal fun anonymousHomeIdentityVerified(context: RequestContext): Boolean = synchronized(lock) {
        visitorPairVerified && !context.signedIn && context.visitor == visitor && context.visitorCookie == visitorCookie
    }
    private fun visitorPairDigest(visitor: String, cookie: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest("$visitor\u0000$cookie".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    fun signedIn(): Boolean = snapshot().signedIn
    fun accounts(): List<Account> = session.accounts()
    fun signInClient(): SignInClient? = synchronized(lock) { client }
    fun selectedAccount(): Account? {
        val current = snapshot().accountId
        return accounts().firstOrNull { it.id == current } ?: accounts().firstOrNull { it.selected }
    }

    /**
     * Worker threads only ([MediaClient] resolves its context inside its pool): overlays a
     * transparently refreshed access token. A refresh is not an identity change, so the
     * generation never moves and in-flight reads stay valid.
     */
    fun freshContext(): RequestContext {
        val (current, active, anonymous) = synchronized(lock) { Triple(snapshot(), token, anonymousMode) }
        active ?: return current
        // The anonymous switch keeps credentials stored but must never attach them.
        if (anonymous) return current
        val fresh = active.token().requireValue()
        return synchronized(lock) {
            if (token !== active || snapshot().generation != current.generation) {
                throw MediaException(com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                    com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.AUTH, "session_changed"))
            }
            store?.let { target ->
                // Even an unchanged write decrypts the stored value to compare it.
                // Publish the pair only after both writes succeed so failures retry.
                if (persistedAccess?.accessToken != fresh.accessToken || persistedAccess?.expiresAtMs != fresh.expiresAtMs) {
                    target.write(KEY_ACCESS_TOKEN, fresh.accessToken)
                    target.write(KEY_ACCESS_EXPIRES, fresh.expiresAtMs.toString())
                    persistedAccess = fresh
                }
            }
            session.updateAccessToken(fresh.accessToken)
            // Recovering a missing token changes signed-in presentation and local
            // ownership, while ordinary token rotation leaves this value equal.
            changed()
            current.copy(accessToken = fresh.accessToken)
        }
    }

    fun completeSignIn(client: SignInClient, token: AccessToken, accounts: List<Account>) {
        synchronized(lock) {
            persistedAccess = null
            this.client = client
            this.token = tokenFactory(token, client)
            // Signing in always ends the "On this device" switch: the account is the identity.
            if (anonymousMode) {
                anonymousMode = false
                store?.write(KEY_ANONYMOUS_MODE, null)
            }
            session.replace(token, accounts)
            // Device-flow accounts sometimes arrive with no selection flag: anchor the first enabled one.
            if (session.snapshot().accountId == null) accounts.firstOrNull { !it.disabled }?.let { session.select(it.id) }
            changed()
            val selected = session.snapshot().accountId
            store?.let { s ->
                s.write(KEY_CLIENT_ID, client.clientId)
                s.write(KEY_CLIENT_SECRET, client.clientSecret)
                s.write(KEY_DEVICE_ID, client.deviceId)
                s.write(KEY_REFRESH_TOKEN, token.refreshToken)
                s.write(KEY_ACCESS_TOKEN, token.accessToken)
                s.write(KEY_ACCESS_EXPIRES, token.expiresAtMs.toString())
                s.write(KEY_ACCOUNT_ID, selected)
                s.write(KEY_ACCOUNTS, encodeAccounts(accounts.map { it.copy(selected = it.id == selected) }))
                persistedAccess = token
            }
        }
    }

    fun updateAccounts(accounts: List<Account>, expectedGeneration: Long): Boolean = synchronized(lock) {
        if (snapshot().generation != expectedGeneration || !session.updateAccounts(accounts)) return false
        store?.write(KEY_ACCOUNT_ID, session.snapshot().accountId)
        store?.write(KEY_ACCOUNTS, encodeAccounts(session.accounts()))
        changed()
        true
    }

    fun selectAccount(id: String): Boolean = synchronized(lock) {
        if (!session.select(id)) return false
        if (anonymousMode) {
            anonymousMode = false
            identityEpoch += 1
            store?.write(KEY_ANONYMOUS_MODE, null)
        }
        store?.write(KEY_ACCOUNT_ID, session.snapshot().accountId)
        store?.write(KEY_ACCOUNTS, encodeAccounts(session.accounts()))
        changed()
        true
    }

    fun signOut() {
        synchronized(lock) {
            session.clear()
            token = null
            persistedAccess = null
            client = null
            wipeLocked()
            changed()
        }
    }

    /** Drops account state; the client credentials and device id identify the install, not the account. */
    private fun wipeLocked() {
        val s = store ?: return
        listOf(KEY_REFRESH_TOKEN, KEY_ACCESS_TOKEN, KEY_ACCESS_EXPIRES, KEY_ACCOUNT_ID, KEY_ACCOUNTS,
            com.hhst.youtubelite.watch.chat.ChatWebSessionStore.KEY)
            .forEach { s.write(it, null) }
    }

    // URL-encoded fields never contain the space/newline separators, so the format stays parseable.
    private fun encodeAccounts(accounts: List<Account>): String = accounts.joinToString("\n") { account ->
        listOf(account.id, account.name.orEmpty(), account.email.orEmpty(), account.photo.orEmpty(),
            account.pageId.orEmpty(), account.disabled.toString()).joinToString(" ") { URLEncoder.encode(it, Charsets.UTF_8) }
    }

    private fun decodeAccounts(body: String, selectedId: String?): List<Account> = body.lines().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val fields = runCatching { line.split(" ").map { URLDecoder.decode(it, Charsets.UTF_8) } }
            .getOrNull() ?: return@mapNotNull null
        if (fields.size !in 5..6 || fields[0].isBlank()) return@mapNotNull null
        Account(fields[0], fields[1].ifBlank { null }, fields[2].ifBlank { null }, fields[3].ifBlank { null },
            fields[0] == selectedId, fields.getOrNull(5) == "true", fields[4].ifBlank { null })
    }
}
