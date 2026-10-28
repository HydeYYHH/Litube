package com.hhst.youtubelite.ui.design

import android.content.ContentResolver
import android.provider.Settings
import androidx.compose.runtime.compositionLocalOf

/** Decorative motion can be disabled independently of framework duration scaling. */
val LocalMotionScale = compositionLocalOf { 1f }

fun readMotionScale(resolver: ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** Compose applies the system duration scale when advancing animation frames. */
fun motionDuration(baseMs: Int, scale: Float): Int = if (scale <= 0f) 0 else baseMs
