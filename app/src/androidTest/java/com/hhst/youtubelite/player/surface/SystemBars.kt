package com.hhst.youtubelite.player.surface

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Whether the status bar is hidden on [activity]'s decor view; call from the UI thread. */
internal fun statusBarsHidden(activity: Activity): Boolean = if (Build.VERSION.SDK_INT < 30) {
    @Suppress("DEPRECATION")
    activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0
} else {
    ViewCompat.getRootWindowInsets(activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.statusBars()) == false
}

/** Whether any system bar is visible; call from the UI thread (frame-sampling safe). */
// The legacy flag read is the authoritative signal pre-30; ViewInsetsControllerCompat has no query.
@Suppress("DEPRECATION")
internal fun systemBarsVisible(decorView: View): Boolean = if (Build.VERSION.SDK_INT < 30) {
    val flags = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    decorView.systemUiVisibility and flags != flags
} else {
    ViewCompat.getRootWindowInsets(decorView)?.let {
        it.isVisible(WindowInsetsCompat.Type.statusBars()) || it.isVisible(WindowInsetsCompat.Type.navigationBars())
    } == true
}
