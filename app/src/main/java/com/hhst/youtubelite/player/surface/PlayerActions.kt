package com.hhst.youtubelite.player.surface

import com.hhst.youtubelite.player.queue.QueueItem

/**
 * Playback intents handled by [player.PlayerViewModel].
 * Window/activity side effects live on [PlayerWindowActions].
 */
interface PlayerPlaybackActions {
    fun onPlayPause()
    fun onSeek(positionMs: Long)
    fun onToggleControls()
    fun onHideControls()
    fun onFullscreenToggle()
    /** Shorts feed: vertical swipe switches the page video instead of brightness. */
    fun onShortsSwipe(up: Boolean) {}
    fun onSpeedHoldStart()
    fun onSpeedHoldEnd()
    fun onHint(text: String?)
    fun onSpeed(speed: Float)
    fun onQuality(label: String?)
    fun onSubtitle(trackKey: String?)
    fun onLoop()
    fun onNext()
    fun onPrevious()
    fun onDoubleTapSeek(offsetMs: Long)
    fun onScrubPreview(offsetMs: Long)
    fun onScrubCommit(offsetMs: Long)
    /** Time-bar drag preview: non-null target shows a bubble, null ends the drag. */
    fun onTimeBarPreview(targetMs: Long?)
    /** Time-bar drag released. */
    fun onTimeBarCommit()
    fun onLockToggle()
    fun onAudioTrack(trackKey: String?)
    /** Subtitle appearance changed (applies live and persists). */
    fun onSubtitleStyle(style: SubtitleStyle)
    fun onQueueItem(url: String)
    fun onResize(mode: ResizeMode)
    fun onQueueEnabled(enabled: Boolean)
    fun onQueueClear()
    fun onQueueRemove(videoId: String)
    /** Queue sheet drag-reorder commit. */
    fun onQueueMove(fromIndex: Int, toIndex: Int)
    /** Page media-menu "Add to queue". */
    fun onQueueAdd(item: QueueItem)
    fun onRetry()
    fun onCastStop()
    fun onCastDevice(id: String)
    fun onShareCastLink()
    fun onCloseCastLink()
    fun onMiniClose()
    fun onMiniRestore()
    /** User cancelled the sponsor pre-skip countdown. */
    fun onSponsorSkipCancel()
    /** Manual skip via the chip while inside a suppressed segment. */
    fun onSponsorChipSkip()
    /**
     * Cast dialog opened/closed: gates Cast device discovery scanning, which
     * is too battery-hungry to leave running outside the dialog. No-op by
     * default so implementors that do not own cast discovery stay unaffected.
     */
    fun onCastDiscovery(enabled: Boolean) {}
    /** Finger up: gesture overlays (edge sliders) clear. */
    fun onGestureEnd()
}

/** Activity-window side effects: back, brightness, volume, PiP, share. */
interface PlayerWindowActions {
    fun onBack()
    fun onBrightness(delta: Float)
    fun onVolume(delta: Float)
    fun onPip()
    fun onShare()
    /** Fires the system share sheet with the cast link (proxy already ensured by [PlayerPlaybackActions.onShareCastLink]). */
    fun shareCastLink()
    /** Finger up: reset per-gesture accumulators. */
    fun onGestureEnd() {}
}

/** Surface contract: playback intents + window side effects. */
interface PlayerSurfaceCallbacks : PlayerPlaybackActions, PlayerWindowActions {
    override fun onGestureEnd()
}

/** Compose-friendly merge of the two action surfaces. */
class PlayerCallbackBridge(
    private val playback: PlayerPlaybackActions,
    private val window: PlayerWindowActions,
) : PlayerSurfaceCallbacks,
    PlayerPlaybackActions by playback,
    PlayerWindowActions by window {
    override fun onGestureEnd() {
        playback.onGestureEnd()
        window.onGestureEnd()
    }

    override fun onShareCastLink() {
        // One user action, two surfaces: ensure the proxy serves the current
        // source, then hand the URL to the system share sheet.
        playback.onShareCastLink()
        window.shareCastLink()
    }
}
