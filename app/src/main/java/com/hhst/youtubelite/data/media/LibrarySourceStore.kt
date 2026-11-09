package com.hhst.youtubelite.data.media

/**
 * Library storage follows the session identity: the device store answers when anonymous or
 * signed out; a signed-in session reads the account. Switching never moves data between
 * storages — there is no merge or upload.
 */
internal fun useDeviceLibrary(local: LocalLibraryStore?): Boolean =
    local != null && !MediaSession.signedIn()

/** Captures the destination before asynchronous work starts. */
internal class LibraryOperation(private val local: LocalLibraryStore?) {
    private val generation = MediaSession.snapshot().generation
    private val hasLocal = local != null
    val device = useDeviceLibrary(local)

    fun check() {
        if (MediaSession.snapshot().generation != generation ||
            device != (hasLocal && !MediaSession.signedIn()))
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
