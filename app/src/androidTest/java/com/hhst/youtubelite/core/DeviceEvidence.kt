package com.hhst.youtubelite.core

import android.os.Build

import java.io.File
import java.io.FileInputStream
import java.io.ByteArrayOutputStream
import androidx.test.platform.app.InstrumentationRegistry

internal object DeviceEvidence {
    private const val DIRECTORY = "device-evidence"

    fun deviceDir(): File = File("/data/local/tmp", DIRECTORY).apply { shell("mkdir -p $absolutePath") }

    fun capture(fileName: String) {
        val dest = File(deviceDir(), fileName).absolutePath
        shell("screencap -p $dest")
    }

    fun captureScene(baseName: String) {
        capture("$baseName-api${Build.VERSION.SDK_INT}.png")
    }

    fun dumpUi(fileName: String) {
        val dest = File(deviceDir(), fileName).absolutePath
        shell("uiautomator dump $dest")
    }

    fun shell(command: String): String {
        val out = ByteArrayOutputStream()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
            .use { pfd -> FileInputStream(pfd.fileDescriptor).copyTo(out) }
        return out.toString(Charsets.UTF_8.name())
    }

    fun writeJson(fileName: String, json: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(ctx.filesDir, DIRECTORY).apply { mkdirs() }
        File(directory, fileName).writeText(json)
    }

    fun dumpWindowsXml(): String {
        val path = File(deviceDir(), "uidump.xml").absolutePath
        shell("uiautomator dump --compressed $path")
        return shell("cat $path")
    }
}
