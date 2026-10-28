package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.library.domain.LibrarySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The signed-in user's explicit library storage choice, persisted across restarts.
 * Signed out there is no choice: every surface reads the device store. Switching the
 * selection never moves data between the two storages — there is no merge or upload.
 */
class LibrarySourceStore(private val store: AuthStore) {
    private val _source = MutableStateFlow(read())
    val source: StateFlow<LibrarySource> = _source

    fun current(): LibrarySource = _source.value

    fun select(value: LibrarySource) {
        if (value == _source.value) return
        store.write(KEY, if (value == LibrarySource.Device) VALUE_DEVICE else VALUE_ACCOUNT)
        _source.value = value
    }

    private fun read() = if (store.read(KEY) == VALUE_DEVICE) LibrarySource.Device else LibrarySource.Account

    private companion object {
        const val KEY = "library:source"
        const val VALUE_DEVICE = "device"
        const val VALUE_ACCOUNT = "account"
    }
}

/** The device store answers when signed out, or when the signed-in user explicitly chose it. */
internal fun useDeviceLibrary(local: LocalLibraryStore?, source: () -> LibrarySource): Boolean =
    local != null && (!MediaSession.signedIn() || source() == LibrarySource.Device)

/** Captures the destination before asynchronous work starts. */
internal class LibraryOperation(local: LocalLibraryStore?, private val source: () -> LibrarySource) {
    private val generation = MediaSession.snapshot().generation
    private val hasLocal = local != null
    val device = useDeviceLibrary(local, source)

    fun check() {
        if (MediaSession.snapshot().generation != generation ||
            device != (hasLocal && (!MediaSession.signedIn() || source() == LibrarySource.Device)))
            throw kotlinx.coroutines.CancellationException("Library source changed")
    }

    fun <T> local(block: () -> T): T = MediaSession.withUnchangedSession(generation) {
        check()
        block()
    }

    fun transport(http: com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient) =
        object : com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient {
            override fun execute(request: com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest): com.liskovsoft.youtubeapi.videoinfo.V2.HttpReply {
                check()
                return http.execute(request).also { check() }
            }
        }
}
