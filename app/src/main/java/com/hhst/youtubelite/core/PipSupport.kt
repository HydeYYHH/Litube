package com.hhst.youtubelite.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

object PipSupport {
    fun available(sdk: Int, deviceSupportsPip: Boolean, enabled: Boolean = true): Boolean =
        sdk >= Build.VERSION_CODES.O && deviceSupportsPip && enabled

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return available(Build.VERSION.SDK_INT,
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
    }
}
