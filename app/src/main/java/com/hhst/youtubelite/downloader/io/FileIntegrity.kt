package com.hhst.youtubelite.downloader.io

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

object FileIntegrity {
    fun sha256(file: File, start: Long = 0L, length: Long = file.length() - start): String {
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start.coerceAtLeast(0L))
            val buf = ByteArray(8192)
            var left = length.coerceAtLeast(0L)
            while (left > 0L) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                digest.update(buf, 0, n)
                left -= n.toLong()
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    fun sidecar(file: File, startByte: Long): File = File(file.parentFile, "${file.name}.$startByte.sha256")

    fun writeChecksum(file: File, startByte: Long, checksum: String) {
        sidecar(file, startByte).writeText(checksum)
    }

    fun readChecksum(file: File, startByte: Long): String? {
        val side = sidecar(file, startByte)
        if (!side.isFile) return null
        return side.readText().trim().ifBlank { null }
    }

    fun verify(file: File, startByte: Long, receivedBytes: Long, expected: String?): Boolean {
        if (!file.isFile) return false
        if (file.length() < startByte + receivedBytes) return false
        val actual = sha256(file, startByte, receivedBytes)
        val recorded = expected ?: readChecksum(file, startByte) ?: return false
        return actual.equals(recorded, ignoreCase = true)
    }

    fun fsync(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.fd.sync()
        }
    }

    fun estimateNeeded(inputs: List<File>, includeMux: Boolean, includePublishCopy: Boolean): Long {
        val inputSum = inputs.sumOf { it.length().coerceAtLeast(0L) }
        var extra = 0L
        if (includeMux) extra += inputSum
        if (includePublishCopy) extra += if (includeMux) inputSum else inputSum
        return extra
    }

    fun isNoSpace(error: Throwable): Boolean {
        var cur: Throwable? = error
        while (cur != null) {
            val msg = cur.message.orEmpty()
            if (cur is IOException && (
                    msg.contains("ENOSPC", ignoreCase = true) ||
                        msg.contains("No space", ignoreCase = true) ||
                        msg.contains("not enough space", ignoreCase = true)
                    )
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }
}

fun interface FreeSpace {
    fun bytes(dir: File): Long
}

object DefaultFreeSpace : FreeSpace {
    override fun bytes(dir: File): Long = dir.usableSpace
}
