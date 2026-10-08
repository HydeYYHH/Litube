package com.hhst.youtubelite.cast

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hhst.youtubelite.cast.protocol.CastV2Session
import com.hhst.youtubelite.player.engine.CastSource

/** Media3 adapter so controls, notification and queue use the same existing PlaybackApi. */
@UnstableApi
internal class CastRemotePlayer(private val session: CastV2Session) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var item: MediaItem? = null
    private var durationMs = 0L
    private var positionMs = 0L
    private var sampledAt = SystemClock.elapsedRealtime()
    private var state = STATE_IDLE
    private var playing = false
    private var wanted = false
    private var repeat = REPEAT_MODE_OFF
    private var parameters = PlaybackParameters.DEFAULT
    private var error: PlaybackException? = null
    private var source: CastSource? = null
    private var manifestUrl: String? = null
    private var ratePending = false

    fun loading(url: String, source: CastSource, startMs: Long) {
        this.source = source
        manifestUrl = url
        item = MediaItem.Builder().setMediaId(source.videoId).setUri(url).setMimeType(MimeTypes.APPLICATION_MPD)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(source.title).setArtist(source.author).build()).build()
        positionMs = startMs.coerceAtLeast(0)
        durationMs = source.durationSec * 1000L
        sampledAt = SystemClock.elapsedRealtime()
        playing = false
        wanted = true
        error = null
        ratePending = true
        state = STATE_BUFFERING
        invalidateState()
    }

    fun status(raw: String?, position: Long, duration: Long, idleReason: String?) {
        if (item == null) return
        if (position >= 0) positionMs = position
        if (duration >= 0) durationMs = duration
        sampledAt = SystemClock.elapsedRealtime()
        playing = raw == "PLAYING"
        wanted = raw == "PLAYING" || raw == "BUFFERING"
        state = when (raw) {
            "PLAYING", "PAUSED" -> STATE_READY
            "BUFFERING" -> STATE_BUFFERING
            "IDLE" -> if (idleReason == "FINISHED") STATE_ENDED else STATE_IDLE
            else -> state
        }
        if (ratePending && state == STATE_READY) {
            ratePending = false
            if (parameters.speed != 1f) session.setPlaybackRate(parameters.speed.toDouble())
        }
        if (idleReason == "ERROR") fail("Receiver media error")
        else if (state == STATE_ENDED && repeat == REPEAT_MODE_ONE) reload(0)
        else invalidateState()
    }

    fun reset() {
        playing = false; wanted = false; item = null; source = null; manifestUrl = null
        state = STATE_IDLE; error = null
        invalidateState()
    }

    fun fail(reason: String) {
        playing = false
        error = PlaybackException(reason, null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        invalidateState()
    }

    private fun currentPosition() = (positionMs + if (playing) {
        ((SystemClock.elapsedRealtime() - sampledAt) * parameters.speed).toLong()
    } else 0L).coerceIn(0, durationMs.coerceAtLeast(positionMs))

    override fun getState(): State {
        val playlist = item?.let { listOf(MediaItemData.Builder(it.mediaId).setMediaItem(it)
            .setDurationUs(durationMs * 1000).setIsSeekable(true).build()) }.orEmpty()
        return State.Builder().setAvailableCommands(Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA, Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SPEED_AND_PITCH,
        ).build()).setPlaylist(playlist).setPlaybackState(state)
            .setPlayWhenReady(wanted, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(repeat).setPlaybackParameters(parameters).setPlayerError(error)
            .setContentPositionMs(PositionSupplier { currentPosition() }).build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wanted = playWhenReady
        if (playWhenReady) {
            if (state == Player.STATE_ENDED) reload(0) else session.play()
        } else session.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val position = positionMs.takeIf { it != C.TIME_UNSET } ?: 0
        if (state == Player.STATE_ENDED) reload(position) else session.seekTo(position)
        this.positionMs = position.coerceAtLeast(0)
        sampledAt = SystemClock.elapsedRealtime()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        repeat = repeatMode
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        positionMs = currentPosition(); sampledAt = SystemClock.elapsedRealtime()
        parameters = PlaybackParameters(playbackParameters.speed.coerceIn(0.5f, 2f))
        session.setPlaybackRate(parameters.speed.toDouble())
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        session.stop(); state = STATE_IDLE; playing = false; wanted = false
        return Futures.immediateVoidFuture()
    }

    private fun reload(position: Long) {
        val source = source ?: return
        val url = manifestUrl ?: return
        loading(url, source, position)
        session.load(url, MimeTypes.APPLICATION_MPD, source.title, source.thumbnailUrl, position, source.author)
    }
}
