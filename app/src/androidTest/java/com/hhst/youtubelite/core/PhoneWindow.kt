package com.hhst.youtubelite.core

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Pins the window to the phone geometry (1170x2532 @ 480 = 390x844 dp) sampled by the
 * phone-form host contracts, restoring the previous override afterwards; the shared
 * suite window (1600x1200) would exercise the wide/rail layouts instead.
 */
object PhoneWindow {
    private var size: String? = null
    private var density: String? = null

    fun enter() {
        size = DeviceEvidence.shell("wm size").substringAfterLast(":").trim()
        density = DeviceEvidence.shell("wm density").substringAfterLast(":").trim()
        DeviceEvidence.shell("wm size 1170x2532")
        DeviceEvidence.shell("wm density 480")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(400)
    }

    fun exit() {
        size?.let { DeviceEvidence.shell("wm size $it") }
        density?.let { DeviceEvidence.shell("wm density $it") }
        size = null
        density = null
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(400)
    }
}
