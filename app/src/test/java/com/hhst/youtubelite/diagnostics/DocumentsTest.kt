package com.hhst.youtubelite.diagnostics

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun savesArchiveWithoutChangingBytes() {
        val archive = temp.newFile("diagnostics.zip")
        ZipOutputStream(archive.outputStream()).use {
            for (name in listOf("manifest.json", "events.jsonl", "summary.txt")) {
                it.putNextEntry(ZipEntry(name)); it.write(name.toByteArray()); it.closeEntry()
            }
        }
        val destination = ByteArrayOutputStream()
        Documents.save(archive) { destination }
        assertArrayEquals(archive.readBytes(), destination.toByteArray())
    }

    @Test fun providerRefusalAndPartialWriteAreFailuresAndCloseTheStream() {
        val archive = temp.newFile().apply { writeText("zip data") }
        assertThrows(IOException::class.java) { Documents.save(archive) { null } }
        var closed = false
        val written = ByteArrayOutputStream()
        val denied = object : OutputStream() {
            override fun write(value: Int) {
                if (written.size() == 3) throw IOException("No space")
                written.write(value)
            }
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { Documents.save(archive) { denied } }
        assertTrue(closed)
        assertArrayEquals("zip".toByteArray(), written.toByteArray())
    }
}
