package com.hhst.youtubelite.saves.presentation

import com.hhst.youtubelite.saves.domain.SaveChoice
import com.hhst.youtubelite.saves.domain.SaveRepository
import com.hhst.youtubelite.watch.domain.SignInRequiredException
import com.hhst.youtubelite.watch.domain.UnconfirmedActionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.hhst.youtubelite.core.AppNotice

/** Sheet state owned by [SaveSheetController]; null when the sheet is closed. */
data class SaveSheetState(
    val videoId: String,
    val newPlaylist: Boolean = false,
    val title: String = "",
    val description: String = "",
    val choices: List<SaveChoice>? = null,
    val choicesFailed: Boolean = false,
    val pending: Boolean = false,
)

/**
 * Shared live save-sheet flow for hosts that keep no sheet state of their own
 * (playlist entries, history rows, search results). Watch and Home deliberately keep
 * SavedStateHandle-backed overlay copies so an open sheet survives rotation and process
 * death; moving them here would need SavedState plumbing and is not worth the contract risk.
 */
class SaveSheetController(
    private val repository: SaveRepository,
    private val scope: CoroutineScope,
    private val onNotice: (AppNotice) -> Unit,
) {
    private val _state = MutableStateFlow<SaveSheetState?>(null)
    val state: StateFlow<SaveSheetState?> = _state.asStateFlow()
    private var job: Job? = null

    fun open(videoId: String) {
        _state.value = SaveSheetState(videoId)
        loadChoices()
    }

    fun close() {
        job?.cancel()
        _state.value = null
    }

    fun retry() {
        if (_state.value != null) {
            _state.update { it?.copy(choices = null, choicesFailed = false) }
            loadChoices()
        }
    }

    fun toggle(choice: SaveChoice) {
        val sheet = _state.value ?: return
        if (sheet.pending) return
        val target = !choice.contains
        _state.update { it?.copy(pending = true) }
        job = scope.launch {
            try {
                repository.setContaining(choice.id, sheet.videoId, target)
                edit(sheet.videoId) { current ->
                    current.copy(pending = false, choices = current.choices?.map {
                        if (it.id == choice.id) it.copy(contains = target) else it
                    })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) { close(); onNotice(AppNotice.SignIn) }
            catch (error: UnconfirmedActionException) { fail(sheet.videoId, UNCONFIRMED, "save_playlist_toggle", error) }
            catch (error: Exception) { fail(sheet.videoId, "Couldn't update this playlist. Try again.", "save_playlist_toggle", error) }
        }
    }

    fun openNewPlaylist() = _state.update { it?.copy(newPlaylist = true, title = "", description = "") }

    fun draftChanged(title: String, description: String) = _state.update { it?.copy(title = title, description = description) }

    fun submitNewPlaylist() {
        val sheet = _state.value ?: return
        val title = sheet.title.trim()
        if (title.isEmpty() || sheet.pending) return
        _state.update { it?.copy(pending = true) }
        job = scope.launch {
            try {
                val created = repository.create(title, sheet.description.trim(), sheet.videoId)
                edit(sheet.videoId) { current ->
                    current.copy(pending = false, newPlaylist = false, title = "", description = "",
                        choices = (current.choices ?: emptyList()) + created)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) { close(); onNotice(AppNotice.SignIn) }
            catch (error: UnconfirmedActionException) { fail(sheet.videoId, UNCONFIRMED, "save_playlist_create", error) }
            catch (error: Exception) { fail(sheet.videoId, "Couldn't create this playlist. Try again.", "save_playlist_create", error) }
        }
    }

    fun saveWatchLater(videoId: String) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                repository.saveWatchLater(videoId)
                onNotice(AppNotice.Message("Saved to Watch later"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) { onNotice(AppNotice.SignIn) }
            catch (error: UnconfirmedActionException) { onNotice(failure("save_watch_later", UNCONFIRMED, error)) }
            catch (error: Exception) { onNotice(failure("save_watch_later", "Couldn't save this video. Try again.", error)) }
        }
    }

    fun savePlaylistToLibrary(playlistId: String) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                repository.savePlaylistToLibrary(playlistId)
                onNotice(AppNotice.Message("Saved to your library"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) { onNotice(AppNotice.SignIn) }
            catch (error: UnconfirmedActionException) { onNotice(failure("save_playlist_library", UNCONFIRMED, error)) }
            catch (error: Exception) { onNotice(failure("save_playlist_library", "Couldn't save this playlist. Try again.", error)) }
        }
    }

    private fun loadChoices() {
        val sheet = _state.value ?: return
        job?.cancel()
        job = scope.launch {
            try {
                val choices = repository.choices(sheet.videoId)
                edit(sheet.videoId) { it.copy(choices = choices, choicesFailed = false, pending = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SignInRequiredException) { close(); onNotice(AppNotice.SignIn) }
            catch (_: Exception) { edit(sheet.videoId) { it.copy(choices = null, choicesFailed = true, pending = false) } }
        }
    }

    private fun fail(videoId: String, message: String, operation: String, error: Exception) {
        edit(videoId) { it.copy(pending = false) }
        onNotice(failure(operation, message, error))
    }

    private fun failure(operation: String, message: String, error: Exception): AppNotice =
        com.hhst.youtubelite.diagnostics.writeFailureNotice(operation, error,
            mapOf("video_id" to _state.value?.videoId), message)

    private fun edit(videoId: String, edit: (SaveSheetState) -> SaveSheetState) {
        _state.update { if (it?.videoId == videoId) edit(it) else it }
    }

    private companion object {
        const val UNCONFIRMED = "The server did not confirm this change. Please refresh before trying again."
    }
}
