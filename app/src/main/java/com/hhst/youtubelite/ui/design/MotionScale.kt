package com.hhst.youtubelite.ui.design

import android.content.ContentResolver
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The system animator duration scale (the accessibility "remove animations"
 * switch sets it to 0). Decorative motion follows it: durations divide by the
 * scale, and a 0 scale applies the same end state immediately.
 */
val LocalMotionScale = staticCompositionLocalOf { 1f }

fun readMotionScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** Scales a duration by the system animator scale; a 0 scale collapses to 0ms. */
fun scaledMotion(baseMs: Int, scale: Float): Int =
    if (scale <= 0f) 0 else (baseMs / scale).toInt().coerceAtLeast(1)
