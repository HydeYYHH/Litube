package com.hhst.youtubelite.player.service

import com.hhst.youtubelite.diagnostics.AppLog

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build

/** Runtime grant for the playback notification on API 33+. */
object NotificationPermission {
    fun requestIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        AppLog.event(AppLog.Category.APP, "notification_permission_requested")
        activity.requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            0,
        )
    }
}
