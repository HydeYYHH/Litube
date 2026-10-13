package com.hhst.youtubelite.diagnostics

import java.io.File
import java.io.IOException
import java.io.OutputStream

object Documents {
    /** Streams an existing archive to the selected destination. */
    fun save(archive: File, openDestination: () -> OutputStream?) {
        archive.inputStream().use { input ->
            val output = openDestination() ?: throw IOException("Document provider returned no stream")
            output.use { input.copyTo(it) }
        }
    }
}
