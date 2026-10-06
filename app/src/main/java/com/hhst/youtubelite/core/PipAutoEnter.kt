package com.hhst.youtubelite.core

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/**
 * Suppress / restore API 31+ auto-PiP when a download trampoline (or any
 * overlay activity) must take the foreground. API 26–30 uses a short
 * leave-hint window instead of [PictureInPictureParams.setAutoEnterEnabled].
 */
object PipAutoEnter {
    private const val LEGACY_SUPPRESS_MS = 1_500L

    private val suppressCount = AtomicInteger(0)
    private val host = AtomicReferenceHolder()

    @Volatile
    var legacySuppressUntil: Long = 0L
        private set

    fun isSuppressed(): Boolean = suppressCount.get() > 0

    fun register(activity: Activity) {
        host.set(activity)
    }

    fun unregister(activity: Activity) {
        host.clearIf(activity)
    }

    fun suppress(): Handle {
        suppressCount.incrementAndGet()
        noteLegacyLaunch()
        // Push auto-enter off without forgetting lastEligible so restore can
        // re-enable when ENABLE_PIP / player eligibility still wants it.
        host.get()?.let { pushParams(it, autoEnter = false) }
        return Handle { restore() }
    }

    fun restore() {
        if (suppressCount.decrementAndGet() < 0) suppressCount.set(0)
        host.get()?.let { activity ->
            pushParams(activity, autoEnter = !isSuppressed() && lastEligible)
        }
    }

    @Volatile
    private var lastEligible: Boolean = false

    fun noteLegacyLaunch() {
        legacySuppressUntil = SystemClock.elapsedRealtime() + LEGACY_SUPPRESS_MS
    }

    fun shouldAutoEnter(eligible: Boolean, sdk: Int): Boolean {
        lastEligible = eligible
        if (sdk < Build.VERSION_CODES.S) return false
        return eligible && !isSuppressed()
    }

    @Volatile
    var lastPushError: String? = null
        private set

    @Volatile
    var lastRequestedAutoEnter: Boolean = false
        private set

    fun apply(activity: Activity, autoEnter: Boolean, aspect: Rational? = null) {
        // While an overlay holds suppress, MainActivity.syncPipParams still
        // calls apply(false). Keep lastEligible as the unsuppressed desire.
        if (!isSuppressed()) {
            lastEligible = autoEnter
        }
        pushParams(activity, autoEnter, aspect)
    }

    private fun pushParams(activity: Activity, autoEnter: Boolean, aspect: Rational? = null) {
        if (!PipSupport.isSupported(activity)) {
            lastRequestedAutoEnter = false
            lastPushError = null
            return
        }
        val builder = PictureInPictureParams.Builder()
        if (aspect != null) builder.setAspectRatio(aspect)
        val enable = autoEnter && !isSuppressed()
        lastRequestedAutoEnter = enable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(enable)
        }
        val result = runCatching { activity.setPictureInPictureParams(builder.build()) }
        lastPushError = result.exceptionOrNull()?.let { it.javaClass.simpleName + ": " + it.message }
    }

    /** Reads the host's live PictureInPictureParams.isAutoEnterEnabled (API 31+). */
    fun isAutoEnterEnabled(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            val params = Activity::class.java.getMethod("getPictureInPictureParams").invoke(activity)
                ?: return false
            params.javaClass.getMethod("isAutoEnterEnabled").invoke(params) as Boolean
        }.getOrDefault(false)
    }

    fun interface Handle {
        fun restore()
    }

    private class AtomicReferenceHolder {
        @Volatile
        private var ref: WeakReference<Activity>? = null

        fun set(activity: Activity) {
            ref = WeakReference(activity)
        }

        fun get(): Activity? = ref?.get()

        fun clearIf(activity: Activity) {
            if (ref?.get() === activity) ref = null
        }
    }
}
