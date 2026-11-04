package com.hhst.youtubelite.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

private const val MESSAGE_TAG = "transient-message"

fun Activity.showMessage(text: CharSequence) {
    runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        val root = window.decorView as? ViewGroup ?: return@runOnUiThread
        dismissMessage()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val message = TextView(this).apply {
            tag = MESSAGE_TAG
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 3
            maxWidth = minOf(dp(560), resources.displayMetrics.widthPixels - dp(32))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(48, 48, 48))
                cornerRadius = dp(20).toFloat()
            }
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val insets = ViewCompat.getRootWindowInsets(root)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        root.addView(message, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = (insets?.bottom ?: 0) + dp(76) })
        val accessibility = getSystemService(AccessibilityManager::class.java)
        val timeout = if (Build.VERSION.SDK_INT >= 29) {
            accessibility?.getRecommendedTimeoutMillis(3_000, AccessibilityManager.FLAG_CONTENT_TEXT) ?: 3_000
        } else 3_000
        val remove = Runnable { (message.parent as? ViewGroup)?.removeView(message) }
        message.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: android.view.View) = Unit
            override fun onViewDetachedFromWindow(view: android.view.View) {
                view.removeCallbacks(remove)
                view.animate().cancel()
            }
        })
        message.postDelayed(remove, timeout.toLong())
    }
}

internal fun Activity.dismissMessage() {
    val root = window.decorView as? ViewGroup ?: return
    root.findViewWithTag<android.view.View>(MESSAGE_TAG)?.let { (it.parent as? ViewGroup)?.removeView(it) }
}
