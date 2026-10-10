@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.diagnostics

import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.google.gson.Gson
import java.io.IOException

/** EventTime owns the media identity, even while another video is being extracted. */
class PlaybackTelemetry : AnalyticsListener {
    private val gson = Gson()
    private val audioStarted = linkedSetOf<String>()
    internal fun context(time: AnalyticsListener.EventTime): DiagnosticContext? = runCatching {
        if (time.timeline.isEmpty || time.windowIndex !in 0 until time.timeline.windowCount) return@runCatching null
        val json = time.timeline.getWindow(time.windowIndex, Timeline.Window()).mediaItem.mediaMetadata.extras
            ?.getString("litube.diagnostic_context") ?: return@runCatching null
        if (json.length > 2048) return@runCatching null
        gson.fromJson(json, DiagnosticContext::class.java)
    }.getOrNull()
    private fun event(time: AnalyticsListener.EventTime, name: String, fields: Map<String, Any?> = emptyMap(), failure: Throwable? = null) =
        AppLog.event(AppLog.Category.PLAYER, name, fields + mapOf("media_identity_known" to (context(time) != null)), failure, context = context(time))
    private fun load(time: AnalyticsListener.EventTime, name: String, info: LoadEventInfo, data: MediaLoadData, failure: Throwable? = null, cancelled: Boolean = false) {
        val fields = DiagnosticRedaction.resource(info.uri.toString()) + mapOf("load_id" to info.loadTaskId, "data_type" to data.dataType,
            "track_type" to data.trackType, "bytes" to info.bytesLoaded, "duration_ms" to info.loadDurationMs,
            "outcome" to if (cancelled) "CANCELLED" else if (failure != null) "FAILURE" else name.substringAfterLast('.').uppercase(),
            "format" to data.trackFormat?.let(::format))
        if (failure != null && !cancelled) event(time, name, fields, failure)
        else AppLog.detail(AppLog.Category.PLAYER, name, fields, context(time))
    }
    private fun format(format: Format) = mapOf("id" to format.id, "mime" to format.sampleMimeType, "codecs" to format.codecs,
        "width" to format.width.takeIf { it > 0 }, "height" to format.height.takeIf { it > 0 }, "bitrate" to format.bitrate.takeIf { it > 0 },
        "language" to format.language, "channels" to format.channelCount.takeIf { it > 0 })
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) = load(eventTime, "media_load.started", loadEventInfo, mediaLoadData)
    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) = load(eventTime, "media_load.completed", loadEventInfo, mediaLoadData)
    override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) = load(eventTime, "media_load.cancelled", loadEventInfo, mediaLoadData, cancelled = true)
    override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) = load(eventTime, "media_load.failed", loadEventInfo, mediaLoadData, error, wasCanceled)
    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) = event(eventTime, if (state == Player.STATE_READY) "player_ready" else "player_state", mapOf("state" to state))
    override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) = event(eventTime, "play_intent", mapOf("play_when_ready" to playWhenReady, "reason" to reason))
    override fun onPlaybackSuppressionReasonChanged(eventTime: AnalyticsListener.EventTime, playbackSuppressionReason: Int) = event(eventTime, "playback_suppression", mapOf("reason" to playbackSuppressionReason))
    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) = event(eventTime, "playing", mapOf("is_playing" to isPlaying))
    @Suppress("UNUSED_PARAMETER")
    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) = event(eventTime, "first_frame", mapOf("render_time_ms" to renderTimeMs))
    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
        val id = context(eventTime)?.operationId ?: return
        if (audioStarted.add(id)) event(eventTime, "first_audio", mapOf("playout_time_ms" to playoutStartSystemTimeMs))
        while (audioStarted.size > 32) audioStarted.remove(audioStarted.first())
    }
    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) = event(eventTime, "tracks", mapOf("groups" to tracks.groups.take(16).map { group ->
        mapOf("type" to group.type, "adaptive" to group.isAdaptiveSupported, "tracks" to (0 until group.length).take(16).map { i ->
            format(group.getTrackFormat(i)) + mapOf("selected" to group.isTrackSelected(i), "support" to group.getTrackSupport(i)) })
    }))
    @Suppress("UNUSED_PARAMETER")
    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) = event(eventTime, "video_decoder", mapOf("decoder" to decoderName, "duration_ms" to initializationDurationMs))
    @Suppress("UNUSED_PARAMETER")
    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) = event(eventTime, "audio_decoder", mapOf("decoder" to decoderName, "duration_ms" to initializationDurationMs))
    override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) = event(eventTime, "video_codec_error", failure = videoCodecError)
    override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) = event(eventTime, "audio_codec_error", failure = audioCodecError)
    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) = event(eventTime, "playback_error", mapOf("error_code" to error.errorCode, "position_ms" to eventTime.eventPlaybackPositionMs), error)
}
