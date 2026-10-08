package com.hhst.youtubelite.player.surface

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.util.Rational
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.PipSupport
import kotlin.math.roundToInt

/**
 * Activity-window side effects for the player overlay.
 *
 * Brightness origin is captured on the first gesture and restored when the
 * player hides. Playback intents stay on [PlayerPlaybackActions].
 */
class PlayerWindowHost(
    private val activity: Activity?,
    private val audioManager: AudioManager,
    private val back: () -> Unit,
    private val pipAvailable: () -> Boolean,
    private val sharePayload: () -> Pair<String, String>?,
    private val videoSize: () -> Pair<Int, Int> = { 0 to 0 },
    private val castLinkUrl: () -> String? = { null },
    /** Edge-slider gesture feedback (structured overlay, not text hint). */
    private val edgeSlider: (volume: Boolean, percent: Int) -> Unit = { _, _ -> },
) : PlayerWindowActions {

    private var brightnessOrigin: Float? = null
    /** Per-gesture brightness; avoids re-reading OEM-quantized window attributes. */
    private var brightnessLevel: Float? = null
    /** Per-gesture music volume in stream units; drives a smooth overlay percent. */
    private var volumeLevel: Float? = null
    private var lastVolumeSet: Int? = null
    private var lastBrightnessSet: Float? = null

    override fun onBack() = back()

    override fun onBrightness(delta: Float) {
        val window = activity?.window ?: return
        val lp = window.attributes
        if (brightnessOrigin == null) brightnessOrigin = lp.screenBrightness
        val current = brightnessLevel
            ?: if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
        val next = GestureMath.accumulateLevel(current, delta, 0.01f, 1f)
        brightnessLevel = next
        val snapped = (next * 100f).roundToInt() / 100f
        if (lastBrightnessSet != snapped) {
            lp.screenBrightness = snapped
            window.attributes = lp
            lastBrightnessSet = snapped
        }
        edgeSlider(false, GestureMath.levelPercent(next, 1f))
    }

    override fun onVolume(delta: Float) {
        val stream = AudioManager.STREAM_MUSIC
        val max = audioManager.getStreamMaxVolume(stream)
        if (max <= 0) return
        val current = volumeLevel ?: audioManager.getStreamVolume(stream).toFloat()
        val next = GestureMath.accumulateLevel(current, delta * max, 0f, max.toFloat())
        volumeLevel = next
        val snapped = next.roundToInt().coerceIn(0, max)
        if (lastVolumeSet != snapped) {
            audioManager.setStreamVolume(stream, snapped, 0)
            lastVolumeSet = snapped
        }
        edgeSlider(true, GestureMath.levelPercent(next, max.toFloat()))
    }

    override fun onPip() {
        if (!pipAvailable()) return
        val act = activity ?: return
        if (!PipSupport.isSupported(act)) return
        val (w, h) = videoSize()
        val (n, d) = PlayerUi.pipAspect(w, h)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(n, d))
            .build()
        runCatching { act.enterPictureInPictureMode(params) }
    }

    override fun onShare() {
        val act = activity ?: return
        val payload = sharePayload() ?: return
        shareText(act, payload.first, payload.second)
    }

    override fun shareCastLink() {
        val act = activity ?: return
        val url = castLinkUrl() ?: return
        shareText(act, url, act.getString(R.string.cast))
    }

    override fun onGestureEnd() {
        volumeLevel = null
        lastVolumeSet = null
        brightnessLevel = null
        lastBrightnessSet = null
    }

    private fun shareText(act: Activity, text: String, subject: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        runCatching {
            act.startActivity(Intent.createChooser(share, act.getString(R.string.share)))
        }
    }

    fun restoreBrightness() {
        val origin = brightnessOrigin ?: return
        brightnessOrigin = null
        val window = activity?.window ?: return
        val lp = window.attributes
        lp.screenBrightness = origin
        window.attributes = lp
    }

    fun applyImmersive(fullscreen: Boolean, videoWidth: Int = 0, videoHeight: Int = 0) {
        val act = activity ?: return
        if (fullscreen) {
            act.requestedOrientation = if (videoHeight > videoWidth && videoHeight > 0) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        } else {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        refreshSystemBars(fullscreen)
    }

    /** Refresh after a dialog/chooser returns focus, without rotating again. */
    fun refreshSystemBars(fullscreen: Boolean) {
        val window = activity?.window ?: return
        if (fullscreen) WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** Focus events, unlike player state, also change when a sheet or chooser closes. */
@Composable
internal fun PlayerWindowEffects(activity: Activity?, host: PlayerWindowHost, fullscreen: Boolean, width: Int, height: Int) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentFullscreen = rememberUpdatedState(fullscreen)
    DisposableEffect(activity, lifecycleOwner, host) {
        val decor = activity?.window?.decorView
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) decor?.post { host.refreshSystemBars(currentFullscreen.value) }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) decor?.post { host.refreshSystemBars(currentFullscreen.value) }
        }
        decor?.viewTreeObserver?.addOnWindowFocusChangeListener(focus)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            decor?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focus)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(fullscreen, width, height, host) { host.applyImmersive(fullscreen, width, height) }
}
