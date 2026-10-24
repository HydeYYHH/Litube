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

class MediaSaveRepository(private val client: MediaClient, private val local: LocalLibraryStore? = null) : SaveRepository {
    override suspend fun choices(videoId: String): List<SaveChoice> = if (!MediaSession.signedIn() && local != null)
        device { it.choices(videoId) } else try {
        client.read { context, http ->
            if (!context.signedIn) throw SignInRequiredException()
            loadPlaylistChoices(videoId, context, http).requireValue()
                .map { SaveChoice(it.id, it.title.orEmpty().ifBlank { it.id }, it.contains) }
        }
    } catch (error: MediaException) {
        if (error.failure.kind == RequestFailure.AUTH) throw SignInRequiredException()
        throw error
    }

    override suspend fun setContaining(playlistId: String, videoId: String, contains: Boolean) {
        if (!MediaSession.signedIn() && local != null) {
            val generation = MediaSession.snapshot().generation
            val video = localVideo(videoId)
            return device(generation) { it.setContaining(playlistId, video, contains) }
        }
        write { context, http ->
            editPlaylist(playlistId,
                listOf(if (contains) PlaylistChange.Add(videoId) else PlaylistChange.RemoveByVideoId(videoId)),
                context, http).requireValue().action
        }
    }

    override suspend fun saveWatchLater(videoId: String) {
        if (!MediaSession.signedIn() && local != null) return setContaining(LocalLibraryStore.WATCH_LATER, videoId, true)
        write { context, http -> editPlaylist("WL", listOf(PlaylistChange.Add(videoId)), context, http).requireValue().action }
    }

    override suspend fun savePlaylistToLibrary(playlistId: String) {
        write { context, http -> savePlaylist(playlistId, true, context, http).requireValue().action }
    }

    override suspend fun create(title: String, description: String, videoId: String): SaveChoice {
        if (!MediaSession.signedIn() && local != null) {
            val generation = MediaSession.snapshot().generation
            val video = localVideo(videoId)
            return device(generation) { it.create(title, description, video) }
        }
        var created: SaveChoice? = null
        write { context, http ->
            val reply = createPlaylist(title, videoId, context, description.ifBlank { null }, http).requireValue()
            val id = reply.playlistId ?: throw UnconfirmedActionException()
            created = SaveChoice(id, title, contains = true)
            reply.action
        }
        return created!!
    }

    private suspend fun <T> device(generation: Long = MediaSession.snapshot().generation, block: (LocalLibraryStore) -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            MediaSession.withAnonymousSession(generation) { block(requireNotNull(local)) }
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

    private suspend fun write(block: (RequestContext, HttpClient) -> ActionReply) {
        try {
            client.read { context, http ->
                if (!context.signedIn) throw SignInRequiredException()
                if (!block(context, http).confirmed) throw UnconfirmedActionException()
            }
        } catch (error: MediaException) {
            if (error.failure.kind == RequestFailure.AUTH) throw SignInRequiredException()
            throw error
        }
    }
}
