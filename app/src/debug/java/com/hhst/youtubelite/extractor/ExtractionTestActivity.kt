package com.hhst.youtubelite.extractor

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView
import android.view.WindowManager

/** Debug-only foreground host; avoids OEM background freezing during device acceptance. */
class ExtractionTestActivity : Activity() {
    lateinit var surface: SurfaceView
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surface = SurfaceView(this)
        setContentView(surface)
    }
}
