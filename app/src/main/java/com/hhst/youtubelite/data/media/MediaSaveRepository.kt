package com.hhst.youtubelite.data.media

import com.hhst.youtubelite.saves.domain.SaveChoice
import com.hhst.youtubelite.saves.domain.SaveRepository
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import com.liskovsoft.youtubeapi.videoinfo.V2.ActionReply
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.PlaylistChange
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import com.liskovsoft.youtubeapi.videoinfo.V2.createPlaylist
import com.liskovsoft.youtubeapi.videoinfo.V2.editPlaylist
import com.liskovsoft.youtubeapi.videoinfo.V2.loadPlaylistChoices
import com.liskovsoft.youtubeapi.videoinfo.V2.savePlaylist

class MediaSaveRepository(private val client: MediaClient, private val local: LocalLibraryStore? = null,
    private val source: () -> com.hhst.youtubelite.library.domain.LibrarySource = { com.hhst.youtubelite.library.domain.LibrarySource.Account }) : SaveRepository {
    private fun operation() = LibraryOperation(local, source)
    override suspend fun choices(videoId: String): List<SaveChoice> {
        val operation = operation()
        if (operation.device) return device(operation) { it.choices(videoId) }
        return try {
            client.read { context, http ->
                operation.check()
                if (!context.signedIn) throw SignInRequiredException()
                loadPlaylistChoices(videoId, context, operation.transport(http)).requireValue()
                    .map { SaveChoice(it.id, it.title.orEmpty().ifBlank { it.id }, it.contains) }
                    .also { operation.check() }
            }
        } catch (error: MediaException) {
            if (error.failure.kind == RequestFailure.AUTH) throw SignInRequiredException()
            throw error
        }
    }

    override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) {
        val operation = operation()
        if (playlistId.startsWith("local:") != operation.device)
            throw kotlinx.coroutines.CancellationException("Playlist source changed")
        if (operation.device) {
            val video = localVideo(videoId)
            return device(operation) { it.setContaining(playlistId, video, contains) }
        }
        write(operation) { context, http ->
            editPlaylist(playlistId,
                listOf(if (contains) PlaylistChange.Add(videoId) else PlaylistChange.RemoveByVideoId(videoId)),
                context, http).requireValue().action
        }
    }

    override suspend fun saveWatchLater(videoId: String) {
        val operation = operation()
        if (operation.device) {
            val video = localVideo(videoId)
            return device(operation) { it.setContaining(LocalLibraryStore.WATCH_LATER, video, true) }
        }
        write(operation) { context, http -> editPlaylist("WL", listOf(PlaylistChange.Add(videoId)), context, http).requireValue().action }
    }

    override suspend fun savePlaylistToLibrary(playlistId: String) {
        val operation = operation()
        if (operation.device) throw UnsupportedOperationException("Account library required")
        write(operation) { context, http -> savePlaylist(playlistId, true, context, http).requireValue().action }
    }

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

    private suspend fun write(operation: LibraryOperation, block: (RequestContext, HttpClient) -> ActionReply) {
        try {
            client.read { context, http ->
                operation.check()
                if (!context.signedIn) throw SignInRequiredException()
                if (!block(context, operation.transport(http)).confirmed) throw UnconfirmedActionException()
            }
        } catch (error: MediaException) {
            if (error.failure.kind == RequestFailure.AUTH) throw SignInRequiredException()
            throw error
        }
    }
}
