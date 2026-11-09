package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.saves.domain.SaveChoice
import com.hhst.youtubelite.saves.domain.SaveRepository
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.liskovsoft.youtubeapi.videoinfo.V2.ActionReply
import com.liskovsoft.youtubeapi.videoinfo.V2.ActionState
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.LibrarySection
import com.liskovsoft.youtubeapi.videoinfo.V2.PlaylistChange
import com.liskovsoft.youtubeapi.videoinfo.V2.ReadResult
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import com.liskovsoft.youtubeapi.videoinfo.V2.createPlaylist
import com.liskovsoft.youtubeapi.videoinfo.V2.editPlaylist
import com.liskovsoft.youtubeapi.videoinfo.V2.loadLibrary
import com.liskovsoft.youtubeapi.videoinfo.V2.loadPlaylistChoices
import com.liskovsoft.youtubeapi.videoinfo.V2.savePlaylist
import kotlinx.coroutines.CancellationException

class MediaSaveRepository(private val client: MediaClient, private val local: LocalLibraryStore? = null) : SaveRepository {
    // The device store backs local collections exactly when the session is not signed in
    // (the whole-app anonymous switch makes signedIn() false while credentials stay).
    private fun operation() = LibraryOperation(local)
    override suspend fun choices(videoId: String): List<SaveChoice> {
        val operation = operation()
        if (operation.device) return device(operation) { it.choices(videoId) }
        return accountOperation {
            client.readIdempotent { context, http ->
                operation.check()
                if (!context.signedIn) throw SignInRequiredException()
                loadPlaylistChoices(videoId, context, operation.transport(http)).requireValue()
                    .map { SaveChoice(it.id, it.title.orEmpty().ifBlank { it.id }, it.contains) }
                    .also { operation.check() }
            }
        }
    }

    override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) {
        val operation = operation()
        if (playlistId.startsWith("local:") != operation.device)
            throw CancellationException("Playlist source changed")
        if (operation.device) {
            if (!contains) return device(operation) { it.removeFromPlaylist(playlistId, videoId) }
            val video = localVideo(videoId)
            return device(operation) { it.setContaining(playlistId, video, true) }
        }
        setMembership(operation, playlistId, videoId, contains)
    }

    override suspend fun saveWatchLater(videoId: String) {
        val operation = operation()
        if (operation.device) {
            val video = localVideo(videoId)
            return device(operation) { it.setContaining(LocalLibraryStore.WATCH_LATER, video, true) }
        }
        setMembership(operation, "WL", videoId, contains = true)
    }

    private suspend fun setMembership(operation: LibraryOperation, playlistId: String, videoId: String, contains: Boolean) =
        accountOperation {
            client.desiredStateWrites.setState(
                key = "playlist_membership:${playlistKey(playlistId)}:$videoId",
                stateName = "playlist_contains",
                expected = contains.toString(),
                // Adding creates a distinct setVideoId, so a sent append is confirmed, never replayed.
                mode = if (contains) DesiredStateRecoveryMode.ConfirmOnly else DesiredStateRecoveryMode.RetryStateSet,
                checkSource = operation::check,
                write = { context, http ->
                    operation.check()
                    val transport = operation.transport(http)
                    val alreadyContains = contains && preflightContains(playlistId, videoId, context, transport)
                    val reply = if (alreadyContains) ActionReply("ALREADY_CONTAINS", "", ActionState.CONFIRMED)
                    else editPlaylist(playlistId,
                        listOf(if (contains) PlaylistChange.Add(videoId) else PlaylistChange.RemoveByVideoId(videoId)),
                        context, transport).requireValue().action
                    DesiredStateAttempt(reply, Unit)
                },
                observe = { context, http ->
                    operation.check()
                    membership(playlistId, videoId, context, operation.transport(http))
                },
                onConfirmed = { operation.check() },
            )
        }

    /** A failed optional read does not spend the append's single write attempt. */
    private fun preflightContains(playlistId: String, videoId: String, context: RequestContext, http: HttpClient): Boolean {
        val result = membership(playlistId, videoId, context, http)
        if (result.ok) return result.value!!.state == "true"
        result.error?.let { error ->
            val transient = error.kind == RequestFailure.TRANSPORT ||
                error.kind == RequestFailure.PARSE && error.code != "response_too_large"
            if (!transient) throw MediaException(error)
        }
        return false
    }

    /** A missing option or flag is unknown, not evidence the video is absent. */
    private fun membership(playlistId: String, videoId: String, context: RequestContext, http: HttpClient): ReadResult<DesiredStateRead<Unit>> {
        val result = loadPlaylistChoices(videoId, context, http)
        if (!result.ok) return ReadResult(error = result.error)
        val contains = result.value!!.firstOrNull { playlistKey(it.id) == playlistKey(playlistId) }?.containsState
        return ReadResult(DesiredStateRead(contains?.toString(), Unit))
    }

    override suspend fun savePlaylistToLibrary(playlistId: String) {
        val operation = operation()
        if (operation.device) throw UnsupportedOperationException("Account library required")
        accountOperation {
            client.desiredStateWrites.setState(
                key = "playlist_saved:${playlistKey(playlistId)}",
                stateName = "playlist_saved",
                expected = "true",
                checkSource = operation::check,
                write = { context, http ->
                    operation.check()
                    DesiredStateAttempt(savePlaylist(playlistId, true, context, operation.transport(http)).requireValue().action, Unit)
                },
                observe = { context, http ->
                    operation.check()
                    savedPlaylist(playlistId, context, operation.transport(http))
                },
                onConfirmed = { operation.check() },
            )
        }
    }

    /** Only reaching the actual end proves absence; continuation cycles and the cap stay unknown. */
    private fun savedPlaylist(playlistId: String, context: RequestContext, http: HttpClient): ReadResult<DesiredStateRead<Unit>> {
        var cursor: String? = null
        val visited = mutableSetOf<String>()
        var recognizedCollection = false
        repeat(8) {
            val result = loadLibrary(LibrarySection.PLAYLISTS, context, cursor, http)
            if (!result.ok) return ReadResult(error = result.error)
            val page = result.value!!
            val playlists = (page.items + page.groups.flatMap { it.items }).filter { it.kind == "playlist" }
            recognizedCollection = recognizedCollection || playlists.isNotEmpty() || page.groups.isNotEmpty() || page.tabs.isNotEmpty()
            if (playlists.any { playlistKey(it.id) == playlistKey(playlistId) })
                return ReadResult(DesiredStateRead("true", Unit))
            val next = page.nextKey ?: return ReadResult(DesiredStateRead(if (recognizedCollection) "false" else null, Unit))
            if (!visited.add(next)) return ReadResult(DesiredStateRead(null, Unit))
            cursor = next
        }
        return ReadResult(DesiredStateRead(null, Unit))
    }

    private fun playlistKey(id: String) = id.removePrefix("VL")

    override suspend fun create(title: String, description: String, videoId: String): SaveChoice {
        val operation = operation()
        if (operation.device) {
            val video = localVideo(videoId)
            return device(operation) { it.create(title, description, video) }
        }
        var created: SaveChoice? = null
        write(operation) { context, http ->
            val reply = createPlaylist(title, videoId, context, description.ifBlank { null }, http).requireValue()
            val id = reply.playlistId ?: throw UnconfirmedActionException()
            created = SaveChoice(id, title, contains = true)
            reply.action
        }
        return created!!
    }

    private suspend fun <T> device(operation: LibraryOperation, block: (LocalLibraryStore) -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            operation.local { block(requireNotNull(local)) }
        }

    private suspend fun localVideo(id: String): com.hhst.youtubelite.library.domain.HistoryEntry {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            local?.history()?.entries?.firstOrNull { it.videoId == id }
        }?.let { return it }
        return client.read { context, http ->
            val page = com.liskovsoft.youtubeapi.videoinfo.V2.loadWatch(id, context, http = http).requireValue()
            val title = page.title?.takeIf { it.isNotBlank() } ?: throw UnconfirmedActionException()
            com.hhst.youtubelite.library.domain.HistoryEntry(id, id, title, page.author.orEmpty(), page.channelId.orEmpty(),
                "https://i.ytimg.com/vi/$id/hqdefault.jpg", "", section = "On this device")
        }
    }

    private suspend fun write(operation: LibraryOperation, block: (RequestContext, HttpClient) -> ActionReply) = accountOperation {
        client.write { context, http ->
            operation.check()
            if (!context.signedIn) throw SignInRequiredException()
            block(context, operation.transport(http)).requireConfirmation()
            operation.check()
        }
    }

    private suspend fun <T> accountOperation(block: suspend () -> T): T = try {
        block()
    } catch (error: MediaException) {
        if (error.failure.kind == RequestFailure.CANCELLED || error.failure.code == "session_changed")
            throw CancellationException("Library source changed")
        if (error.failure.kind == RequestFailure.AUTH) throw SignInRequiredException()
        throw error
    }
}
