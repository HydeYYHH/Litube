package com.hhst.youtubelite.core

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import com.hhst.youtubelite.extension.ExtensionManager

class HapticsController(private val context: Context, private val preferences: ExtensionManager) {
    enum class Event { TAB, HOLD, SELECTION, CONFIRM, DROP_TARGET, GESTURE_THRESHOLD }
    private var lastAt = 0L
    @Suppress("DEPRECATION")
    fun perform(event: Event) {
        val strength = preferences.hapticStrength()
        if (strength == 0) return
        runCatching {
            if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
            val now = SystemClock.elapsedRealtime()
            // Coalesce layered controls too (a selected row may call a player action).
            if (now - lastAt < 80) return
            lastAt = now
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            val duration = if (event == Event.HOLD || event == Event.DROP_TARGET) 18L else 12L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vibrator.hasAmplitudeControl()) {
                    VibrationEffect.createOneShot(duration, (strength * 255 / 100).coerceIn(1, 255))
                } else VibrationEffect.createOneShot(6L + strength * 10L / 100L, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            } else {
                vibrator.vibrate(6L + strength * 10L / 100L)
            }
        }
    }
}
