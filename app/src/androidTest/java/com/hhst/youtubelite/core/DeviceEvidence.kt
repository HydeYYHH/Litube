package com.hhst.youtubelite.core

import android.os.Build

import java.io.File
import java.io.FileInputStream
import androidx.test.platform.app.InstrumentationRegistry

internal object DeviceEvidence {
    const val DATE = "2026-09-17"

    fun deviceDir(): File {
        val dir = File("/data/local/tmp/device-$DATE")
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("mkdir -p ${dir.absolutePath}")
            .use { pfd -> FileInputStream(pfd.fileDescriptor).copyTo(java.io.ByteArrayOutputStream()) }
        return dir
    }

    fun capture(fileName: String) {
        val dest = File(deviceDir(), fileName).absolutePath
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p $dest")
            .use { pfd -> FileInputStream(pfd.fileDescriptor).copyTo(java.io.ByteArrayOutputStream()) }
    }

    fun captureScene(baseName: String) {
        capture("$baseName-api${Build.VERSION.SDK_INT}.png")
    }

    fun dumpUi(fileName: String) {
        val dest = "/data/local/tmp/$fileName"
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("uiautomator dump $dest")
            .use { pfd -> FileInputStream(pfd.fileDescriptor).copyTo(java.io.ByteArrayOutputStream()) }
    }

    fun shell(command: String): String {
        val out = java.io.ByteArrayOutputStream()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { pfd -> FileInputStream(pfd.fileDescriptor).copyTo(out) }
        return out.toString(Charsets.UTF_8)
    }

    fun writeJson(fileName: String, json: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.filesDir, fileName).writeText(json)
        File(ctx.cacheDir, fileName).writeText(json)
        val dest = File(deviceDir(), fileName)
        runCatching { dest.writeText(json) }
        val b64 = android.util.Base64.encodeToString(json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        shell("base64 -d > ${dest.absolutePath} <<'LITUBEB64'\n$b64\nLITUBEB64")
    }

    fun dumpWindowsXml(): String {
        shell("uiautomator dump --compressed /data/local/tmp/device-$DATE/uidump.xml")
        return shell("cat /data/local/tmp/device-$DATE/uidump.xml")
    }
}
