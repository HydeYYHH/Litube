package com.hhst.youtubelite.cast

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.player.engine.CastSource
import com.yinnho.upnpcast.DLNACast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Control surface [DlnaController] needs from its remote player; the interface keeps the
 *  controller testable without a Looper-backed player. */
interface DlnaPlayerDelegate {
    fun loading(source: CastSource, startMs: Long, autoplay: Boolean = true)
    fun status(transport: DLNACast.PlaybackState?, position: Long?, duration: Long?)
    fun reset()
    fun expectsPlayback(): Boolean
    fun position(): Long
}

/**
 * Media3 adapter over a DLNA renderer, the same attach point as [CastRemotePlayer]. State
 * arrives from the controller's 1 s poll ([status]). This adapter does not implement renderer
 * playback-rate commands, so COMMAND_SET_SPEED_AND_PITCH is not advertised.
 */
@UnstableApi
class DlnaRemotePlayer(
    private val scope: CoroutineScope,
    private val engine: DlnaEngine,
    private val onReload: (startMs: Long) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()), DlnaPlayerDelegate {
    private var item: MediaItem? = null
    private var durationMs = 0L
    private var positionMs = 0L
    private var sampledAt = SystemClock.elapsedRealtime()
    private var state = STATE_IDLE
    private var playing = false
    private var wanted = false
    private var pendingPlayWhenReady: Boolean? = null
    private var repeat = REPEAT_MODE_OFF
    private var error: PlaybackException? = null
    private var source: CastSource? = null
    private var generation = 0L

    override fun loading(source: CastSource, startMs: Long, autoplay: Boolean) {
        generation++
        this.source = source
        item = MediaItem.Builder().setMediaId(source.videoId)
            .setUri(source.video?.url ?: "")
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(source.title).setArtist(source.author).build(),
            ).build()
        positionMs = startMs.coerceAtLeast(0)
        durationMs = source.durationSec * 1000L
        sampledAt = SystemClock.elapsedRealtime()
        playing = false
        wanted = autoplay
        pendingPlayWhenReady = autoplay
        error = null
        state = STATE_BUFFERING
        invalidateState()
    }

    override fun status(transport: DLNACast.PlaybackState?, position: Long?, duration: Long?) {
        if (item == null || error != null) return
        if (position != null && position >= 0) {
            positionMs = position
            sampledAt = SystemClock.elapsedRealtime()
        }
        if (duration != null && duration > 0) durationMs = duration
        if (transport != null) {
            if (position == null) positionMs = currentPosition()
            sampledAt = SystemClock.elapsedRealtime()
            playing = transport == DLNACast.PlaybackState.PLAYING
            val observed = when (transport) {
                DLNACast.PlaybackState.PLAYING -> true
                DLNACast.PlaybackState.PAUSED -> false
                else -> null
            }
            if (observed != null) {
                if (observed == pendingPlayWhenReady) pendingPlayWhenReady = null
                wanted = pendingPlayWhenReady ?: observed
            }
        }
        state = when (transport) {
            DLNACast.PlaybackState.PLAYING, DLNACast.PlaybackState.PAUSED -> STATE_READY
            DLNACast.PlaybackState.BUFFERING -> STATE_BUFFERING
            // STOPPED/IDLE mean ended only at the tail; otherwise the controller treats it as a remote stop echo.
            DLNACast.PlaybackState.STOPPED, DLNACast.PlaybackState.IDLE ->
                if (reachedEnd()) STATE_ENDED else state
            DLNACast.PlaybackState.ERROR -> {
                fail("Renderer transport error")
                return
            }
            null -> state
        }
        if (state == STATE_ENDED && repeat == REPEAT_MODE_ONE) {
            reload(0)
            return
        }
        invalidateState()
    }

    override fun reset() {
        generation++
        pendingPlayWhenReady = null
        playing = false; wanted = false; item = null; source = null
        state = STATE_IDLE; error = null
        invalidateState()
    }

    private fun fail(reason: String) {
        playing = false
        state = STATE_IDLE
        error = PlaybackException(reason, null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        invalidateState()
    }

    /** True while user playback intent is live; the poll uses it to spot remote stops. */
    override fun expectsPlayback(): Boolean = item != null && state != STATE_ENDED && error == null

    override fun position(): Long = currentPosition()

    private fun reachedEnd(): Boolean =
        durationMs > 0 && positionMs >= durationMs - END_EPSILON_MS

    private fun currentPosition() = (positionMs + if (playing) {
        SystemClock.elapsedRealtime() - sampledAt
    } else 0L).coerceIn(0, durationMs.coerceAtLeast(positionMs))

    override fun getState(): State {
        val playlist = item?.let { listOf(MediaItemData.Builder(it.mediaId).setMediaItem(it)
            .setDurationUs(durationMs * 1000).setIsSeekable(true).build()) }.orEmpty()
        return State.Builder().setAvailableCommands(Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_SET_REPEAT_MODE,
        ).build()).setPlaylist(playlist).setPlaybackState(state)
            .setPlayWhenReady(wanted, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(repeat).setPlayerError(error)
            .setContentPositionMs(PositionSupplier { currentPosition() }).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wanted = playWhenReady
        pendingPlayWhenReady = playWhenReady
        if (playWhenReady) {
            if (state == STATE_ENDED) reload(0) else command { engine.play() }
        } else command { engine.pause() }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val position = positionMs.takeIf { it != C.TIME_UNSET } ?: 0
        this.positionMs = position.coerceAtLeast(0)
        sampledAt = SystemClock.elapsedRealtime()
        if (state == STATE_ENDED) reload(position) else command { engine.seek(position) }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        command { engine.stop() }
        state = STATE_IDLE; playing = false; wanted = false
        return Futures.immediateVoidFuture()
    }

    private fun reload(position: Long) {
        if (source == null) return
        positionMs = position.coerceAtLeast(0)
        sampledAt = SystemClock.elapsedRealtime()
        state = STATE_BUFFERING
        playing = false
        wanted = true
        error = null
        invalidateState()
        onReload(position.coerceAtLeast(0))
    }

    private fun command(command: suspend () -> Boolean) {
        val request = generation
        scope.launch {
            if (request != generation || item == null) return@launch
            try {
                val accepted = command()
                if (!accepted && request == generation) fail("Renderer rejected command")
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                AppLog.event(AppLog.Category.CAST, "dlna_command_timeout", failure = timeout)
                if (request == generation) fail("Renderer command timed out")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.event(AppLog.Category.CAST, "dlna_command_failed", failure = error)
                if (request == generation) fail("Renderer command failed")
            }
        }
    }

    private companion object {
        /** Renderer clocks lag the poll; a near-total position counts as ended. */
        const val END_EPSILON_MS = 2_000L
    }
}
