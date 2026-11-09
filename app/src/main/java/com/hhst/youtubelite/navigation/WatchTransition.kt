package com.hhst.youtubelite.navigation

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.SurfaceView
import android.view.PixelCopy
import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal object WatchTransition {
    const val ORIGIN = "watch_origin"
    const val INSTANT = "navigation_instant"
    private const val MINI_RESTORE = "watch_mini_restore"
    private const val BACKDROP = "watch_backdrop"
    private var backdropVersion = 0L
    private val artwork = WeakHashMap<View, LinkedHashMap<String, Rect>>()
    var backdrop: Bitmap? = null
        private set
    /** A direct/notification launch must never inherit another entrance's screenshot. */
    fun backdrop(intent: Intent): Bitmap? = backdrop.takeIf { hasPreparedBackdrop(intent) }

    fun hasPreparedBackdrop(intent: Intent): Boolean = intent.hasExtra(BACKDROP) &&
        intent.getLongExtra(BACKDROP, -1L) == backdropVersion
    var miniBounds: Rect? = null
    private var miniEntryBounds: Rect? = null

    fun beginMiniEntry(bounds: Rect) {
        miniBounds = null
        miniEntryBounds = bounds
    }

    fun takeMiniEntryBounds(): Rect? = miniEntryBounds.also { miniEntryBounds = null }
    private var browseActivity = WeakReference<Activity>(null)
    var capturingBackdrop = false
        private set

    data class VideoFrame(val videoId: String, val image: Bitmap, val source: WeakReference<SurfaceView>)
    var videoFrame by mutableStateOf<VideoFrame?>(null)
        private set

    /** The outgoing mini stays painted after its live SurfaceView is detached. */
    data class MiniRestoreFrame(val frame: VideoFrame, val bounds: Rect, val videoBounds: Rect,
        val source: WeakReference<Activity>)
    private var restoringMini by mutableStateOf<MiniRestoreFrame?>(null)

    fun miniRestoreFrame(activity: Activity): MiniRestoreFrame? = restoringMini
        ?.takeIf { it.source.get() === activity }

    /** The target retains this picture only until its own player window draws. */
    fun miniRestoreFrame(intent: Intent): MiniRestoreFrame? = restoringMini?.takeIf {
        intent.getBooleanExtra(MINI_RESTORE, false) && hasPreparedBackdrop(intent) &&
            it.frame.videoId == intent.getStringExtra(com.hhst.youtubelite.watch.WatchActivity.EXTRA_VIDEO_ID) &&
            it.bounds == origin(intent) && !it.frame.image.isRecycled
    }

    fun clearMiniRestore(activity: Activity) {
        if (restoringMini?.source?.get() !== activity) return
        restoringMini = null
        activity.window.decorView.invalidate()
    }

    fun browseResumed(activity: Activity) {
        browseActivity = WeakReference(activity)
    }

    /** Restore through the current browse page, preserving its place in the stack. */
    fun restore(context: android.content.Context, id: String) {
        restoringMini?.source?.get()?.let(::clearMiniRestore)
        val activity = browseActivity.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        val intent = com.hhst.youtubelite.watch.WatchActivity.intent(activity ?: context, id)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(MINI_RESTORE, true)
        if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (activity != null) prepare(activity.window.decorView, id, intent) else backdrop = null
        miniBounds?.let { putOrigin(intent, it) }
        if (activity != null) {
            val frame = videoFrame?.takeIf { it.videoId == id && !it.image.isRecycled }
            val surface = frame?.source?.get()?.takeIf {
                it.rootView === activity.window.decorView && it.holder.surface.isValid && it.width > 0 && it.height > 0
            }
            val bounds = miniBounds
            if (frame != null && surface != null && bounds != null) {
                val position = IntArray(2).also(surface::getLocationInWindow)
                restoringMini = MiniRestoreFrame(frame, bounds,
                    Rect(position[0].toFloat(), position[1].toFloat(),
                        (position[0] + surface.width).toFloat(), (position[1] + surface.height).toFloat()),
                    WeakReference(activity))
                activity.window.decorView.invalidate()
            }
        }
        try {
            (activity ?: context).startActivity(intent,
                android.app.ActivityOptions.makeCustomAnimation(activity ?: context, 0, 0).toBundle())
        } catch (error: RuntimeException) {
            activity?.let(::clearMiniRestore)
            throw error
        }
    }

    /** Cover the replacement surface until it has rendered, including paused playback. */
    fun captureVideo(id: String?, surface: SurfaceView?, onReady: () -> Unit) {
        videoFrame = null
        if (id == null || surface == null || surface.width <= 0 || surface.height <= 0 || !surface.holder.surface.isValid) {
            onReady()
            return
        }
        val scale = minOf(1f, 1280f / maxOf(surface.width, surface.height))
        val image = Bitmap.createBitmap((surface.width * scale).toInt().coerceAtLeast(1),
            (surface.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val handler = Handler(Looper.getMainLooper())
        var completed = false
        val timeout = Runnable { if (!completed) { completed = true; onReady() } }
        handler.postDelayed(timeout, 250)
        try {
            PixelCopy.request(surface, image, { result ->
                handler.removeCallbacks(timeout)
                if (completed) { image.recycle(); return@request }
                completed = true
                if (result == PixelCopy.SUCCESS) videoFrame = VideoFrame(id, image, WeakReference(surface))
                else image.recycle()
                onReady()
            }, handler)
        } catch (_: IllegalArgumentException) {
            handler.removeCallbacks(timeout)
            if (!completed) { completed = true; image.recycle(); onReady() }
        }
    }

    fun artwork(view: View, id: String, bounds: Rect) {
        if (bounds.width <= 0 || bounds.height <= 0) return
        val entries = artwork.getOrPut(view.rootView) { LinkedHashMap() }
        entries[id] = bounds
        while (entries.size > 128) entries.remove(entries.keys.first())
    }

    fun prepare(view: View, id: String, intent: Intent) {
        backdrop = null
        intent.putExtra(BACKDROP, ++backdropVersion)
        val bounds = artwork[view.rootView]?.get(id)
        bounds?.let { putOrigin(intent, it) }
        if (view.width <= 0 || view.height <= 0) return
        val scale = minOf(1f, 1280f / maxOf(view.width, view.height))
        val image = Bitmap.createBitmap((view.width * scale).toInt().coerceAtLeast(1),
            (view.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
        val previousCapture = capturingBackdrop
        try {
            capturingBackdrop = true
            view.draw(Canvas(image).apply { scale(scale, scale) })
            backdrop = image
        } catch (_: RuntimeException) {
            image.recycle()
            backdrop = null
        } finally {
            capturingBackdrop = previousCapture
        }
    }

    fun putOrigin(intent: Intent, rect: Rect) {
        intent.putExtra(ORIGIN, floatArrayOf(rect.left, rect.top, rect.right, rect.bottom))
    }

    fun origin(intent: Intent): Rect? = intent.getFloatArrayExtra(ORIGIN)?.takeIf { it.size == 4 }
        ?.takeIf { it.all(Float::isFinite) }
        ?.let { Rect(it[0], it[1], it[2], it[3]) }?.takeIf { it.width > 0 && it.height > 0 }
}
