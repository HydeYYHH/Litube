package com.hhst.youtubelite.player.surface

import com.hhst.youtubelite.player.QueueItem
import com.hhst.youtubelite.player.QueueOwner

/**
 * Playback intents handled by [player.PlayerViewModel]; window/activity side
 * effects live on [PlayerWindowActions].
 */
interface PlayerPlaybackActions {
    fun onPlayPause()
    fun onSeek(positionMs: Long)
    fun onToggleControls()
    fun onHideControls()
    fun onFullscreenToggle()
    /** A swipe exit keeps the current direction; the button retains its own policy. */
    fun onFullscreenSwipeExit() = onFullscreenToggle()
    /** Downward exit restores the presentation from before fullscreen entry. */
    fun onFullscreenSwipeRestore() = onFullscreenToggle()
    fun onSpeedHoldStart()
    fun onSpeedHoldEnd()
    fun onHint(text: String?)
    fun onSpeed(speed: Float)
    fun onQuality(label: String?)
    fun onSubtitle(trackKey: String?)
    fun onLoopMode(mode: com.hhst.youtubelite.player.engine.LoopMode)
    fun onNext()
    fun onPrevious()
    fun onDoubleTapSeek(offsetMs: Long)
    fun onScrubPreview(offsetMs: Long)
    fun onScrubCommit(offsetMs: Long)
    /** Time-bar drag preview: non-null target shows a bubble, null ends the drag. */
    fun onTimeBarPreview(targetMs: Long?)
    fun onTimeBarCommit()
    fun onLockToggle()
    fun onAudioTrack(trackKey: String?)
    /** May trigger a bounded audio-catalog supplement. */
    fun onAudioMenuOpened() {}
    /** Applies live and persists. */
    fun onSubtitleStyle(style: SubtitleStyle)
    fun onQueueItem(url: String)
    fun onResize(mode: ResizeMode)
    fun onQueueEnabled(enabled: Boolean)
    fun onQueueClear()
    fun onQueueRemove(videoId: String)
    /**
     * [owner] is the queue owner captured at drag start; a commit from a
     * switched identity is dropped, never applied to the newly active queue.
     */
    fun onQueueMove(fromIndex: Int, toIndex: Int, owner: QueueOwner? = null)
    /** Page media-menu "Add to queue". */
    fun onQueueAdd(item: QueueItem)
    /** Video action panel "Play next": after the playing entry, moving an existing entry. */
    fun onQueueAddNext(item: QueueItem)
    fun onRetry()
    fun onErrorDetails() {}
    fun onCastStop()
    fun onCastDevice(id: String)
    fun onCastFailureDismiss() {}
    fun onShareCastLink(onReady: () -> Unit = {})
    fun onCloseCastLink()
    fun onMiniClose()
    fun onMiniRestore()
    fun onSponsorSkipCancel()
    /** User tapped the countdown card. */
    fun onSponsorSkipNow()
    /** Manual skip via the chip while inside a suppressed segment. */
    fun onSponsorChipSkip()
    /**
     * Gates cast device discovery scanning, too battery-hungry to leave
     * running outside the dialog. No-op by default so implementors that do
     * not own cast discovery stay unaffected.
     */
    fun onCastDiscovery(enabled: Boolean) {}
    /** Finger up: gesture overlays (edge sliders) clear. */
    fun onGestureEnd()
}

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

    override fun onShareCastLink(onReady: () -> Unit) {
        playback.onShareCastLink {
            window.shareCastLink()
            onReady()
        }
    }
}
