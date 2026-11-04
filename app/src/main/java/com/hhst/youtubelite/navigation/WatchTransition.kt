package com.hhst.youtubelite.navigation

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.geometry.Rect
import java.util.WeakHashMap

internal object WatchTransition {
    const val ORIGIN = "watch_origin"
    const val INSTANT = "navigation_instant"
    private val artwork = WeakHashMap<View, LinkedHashMap<String, Rect>>()
    var backdrop: Bitmap? = null
        private set
    var miniBounds: Rect? = null

    fun artwork(view: View, id: String, bounds: Rect) {
        if (bounds.width <= 0 || bounds.height <= 0) return
        val entries = artwork.getOrPut(view.rootView) { LinkedHashMap() }
        entries[id] = bounds
        while (entries.size > 128) entries.remove(entries.keys.first())
    }

    fun prepare(view: View, id: String, intent: Intent) {
        artwork[view.rootView]?.get(id)?.let { putOrigin(intent, it) }
        if (view.width <= 0 || view.height <= 0) return
        val scale = minOf(1f, 1280f / maxOf(view.width, view.height))
        val image = Bitmap.createBitmap((view.width * scale).toInt().coerceAtLeast(1),
            (view.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
        try {
            view.draw(Canvas(image).apply { scale(scale, scale) })
            backdrop = image
        } catch (_: RuntimeException) {
            image.recycle()
            backdrop = null
        }
    }

    fun putOrigin(intent: Intent, rect: Rect) {
        intent.putExtra(ORIGIN, floatArrayOf(rect.left, rect.top, rect.right, rect.bottom))
    }

    fun origin(intent: Intent): Rect? = intent.getFloatArrayExtra(ORIGIN)?.takeIf { it.size == 4 }
        ?.takeIf { it.all(Float::isFinite) }
        ?.let { Rect(it[0], it[1], it[2], it[3]) }?.takeIf { it.width > 0 && it.height > 0 }
}
