package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.AccessToken
import com.liskovsoft.youtubeapi.videoinfo.V2.Account
import com.liskovsoft.youtubeapi.videoinfo.V2.AccountSession
import com.liskovsoft.youtubeapi.videoinfo.V2.AccountToken
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.SignInClient
import java.net.URLDecoder
import java.net.URLEncoder

/** Persistent auth fields backing [MediaSession]. MMKV in production, memory in tests. */
interface AuthStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

class MmkvAuthStore(context: android.content.Context) : AuthStore {
    private val kv = com.tencent.mmkv.MMKV.mmkvWithID("media-auth", com.tencent.mmkv.MMKV.SINGLE_PROCESS_MODE,
        null, java.io.File(context.noBackupFilesDir, "auth").apply { mkdirs() }.absolutePath)

    init {
        // Migration may only delete a legacy key after its new-location write is
        // confirmed; a failed encode keeps the legacy copy as the source of truth.
        val legacy = com.tencent.mmkv.MMKV.defaultMMKV()
        val keys = legacy.allKeys().orEmpty().filter { it.startsWith("auth:") }
        val migrated = mutableListOf<String>()
        keys.forEach { key ->
            if (kv.containsKey(key)) {
                migrated += key
            } else legacy.decodeString(key)?.let { value ->
                if (kv.encode(key, value)) migrated += key
            }
        }
        kv.sync()
        migrated.forEach(legacy::removeValueForKey)
        legacy.sync()
        if (migrated.size != keys.size) com.hhst.youtubelite.diagnostics.AppLog.event(
            com.hhst.youtubelite.diagnostics.AppLog.Category.STORAGE, "auth_migration_incomplete",
            mapOf("legacy" to keys.size, "migrated" to migrated.size))
    }
    override fun read(key: String): String? = kv.decodeString(key, null)
    override fun write(key: String, value: String?) {
        if (value == null) {
            kv.removeValueForKey(key)
            return
        }
        // Identical values (e.g. an unchanged access token across refreshes)
        // must not rewrite the same credential on every request.
        if (kv.decodeString(key, null) == value) return
        check(kv.encode(key, value)) { "auth_write_failed" }
    }
}

class MemoryAuthStore : AuthStore {
    val values = mutableMapOf<String, String>()
    override fun read(key: String): String? = values[key]
    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

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

    private val lock = Any()
    private var store: AuthStore? = null
    private var session = AccountSession()
    private var token: AccountToken? = null
    private var client: SignInClient? = null
    private var visitor: String? = null
    private var privateMode: () -> Boolean = { false }
    private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val changes: kotlinx.coroutines.flow.StateFlow<Long> = _changes

    private fun changed() { _changes.value = session.snapshot().generation }

    /** Test hook: the default [AccountToken] refreshes over the network. */
    internal var tokenFactory: (AccessToken, SignInClient) -> AccountToken = { initial, signInClient ->
        AccountToken(initial, signInClient)
    }

    /** Rebuilds the session from the store; safe to call again (tests reset state this way). */
    fun initialize(store: AuthStore, privateMode: () -> Boolean = { false }) {
        synchronized(lock) {
            this.store = store
            this.privateMode = privateMode
            visitor = store.read(KEY_VISITOR)?.takeIf { it.isNotBlank() }
            session = AccountSession()
            token = null
            client = null
            val refresh = store.read(KEY_REFRESH_TOKEN)?.takeIf { it.isNotBlank() } ?: return
            val id = store.read(KEY_CLIENT_ID)
            val secret = store.read(KEY_CLIENT_SECRET)
            val device = store.read(KEY_DEVICE_ID)
            if (id.isNullOrBlank() || secret.isNullOrBlank() || device.isNullOrBlank()) {
                wipeLocked()
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

    /** Main-thread safe: the cached context, possibly holding a stale access token. */
    fun snapshot(): RequestContext = synchronized(lock) { session.snapshot().let {
        if (it.signedIn) it else it.copy(visitor = visitor)
    } }
    fun privateBrowsing(): Boolean = privateMode()

    internal fun <T> withAnonymousSession(generation: Long, block: () -> T): T = synchronized(lock) {
        val current = session.snapshot()
        if (current.signedIn || current.generation != generation) throw kotlinx.coroutines.CancellationException("Session changed")
        block()
    }

    /** Device-store access for the explicit device library source: guarded against identity
     * changes only, so a signed-in user may still read and write on-device collections. */
    internal fun <T> withUnchangedSession(generation: Long, block: () -> T): T = synchronized(lock) {
        if (session.snapshot().generation != generation) throw kotlinx.coroutines.CancellationException("Session changed")
        block()
    }

    fun rememberVisitor(value: String?, generation: Long) = synchronized(lock) {
        if (value.isNullOrBlank() || value.length > 4096 || session.snapshot().generation != generation ||
            session.snapshot().signedIn || visitor != null) return@synchronized
        visitor = value
        store?.write(KEY_VISITOR, value)
    }
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
        val (current, active) = synchronized(lock) { snapshot() to token }
        active ?: return current
        val fresh = active.token().requireValue()
        return synchronized(lock) {
            if (token !== active || session.snapshot().generation != current.generation) {
                throw MediaException(com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                    com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.AUTH, "session_changed"))
            }
            store?.write(KEY_ACCESS_TOKEN, fresh.accessToken)
            store?.write(KEY_ACCESS_EXPIRES, fresh.expiresAtMs.toString())
            current.copy(accessToken = fresh.accessToken)
        }
    }

    fun completeSignIn(client: SignInClient, token: AccessToken, accounts: List<Account>) {
        synchronized(lock) {
            this.client = client
            this.token = tokenFactory(token, client)
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
            }
        }
    }

    fun updateAccounts(accounts: List<Account>, expectedGeneration: Long): Boolean = synchronized(lock) {
        if (session.snapshot().generation != expectedGeneration || !session.updateAccounts(accounts)) return false
        store?.write(KEY_ACCOUNT_ID, session.snapshot().accountId)
        store?.write(KEY_ACCOUNTS, encodeAccounts(session.accounts()))
        changed()
        true
    }

    fun selectAccount(id: String): Boolean = synchronized(lock) {
        if (!session.select(id)) return false
        store?.write(KEY_ACCOUNT_ID, session.snapshot().accountId)
        store?.write(KEY_ACCOUNTS, encodeAccounts(session.accounts()))
        changed()
        true
    }

    fun signOut() {
        synchronized(lock) {
            session.clear()
            token = null
            client = null
            wipeLocked()
            changed()
        }
    }

    /** Drops account state; the client credentials and device id identify the install, not the account. */
    private fun wipeLocked() {
        val s = store ?: return
        listOf(KEY_REFRESH_TOKEN, KEY_ACCESS_TOKEN, KEY_ACCESS_EXPIRES, KEY_ACCOUNT_ID, KEY_ACCOUNTS)
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
