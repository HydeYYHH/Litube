package com.hhst.youtubelite.downloader.io

import com.hhst.youtubelite.downloader.net.DownloadHttpClients
import java.io.File

/**
 * Dedicated download working directory. Not the player SimpleCache `player/` tree.
 */
class DownloadDirectories(private val root: File) {
    init {
        root.mkdirs()
    }

    fun workRoot(): File = root

    fun taskDir(taskId: String): File = File(root, sanitize(taskId)).also { it.mkdirs() }

    fun componentFile(taskId: String, componentId: String): File =
        File(taskDir(taskId), "${sanitize(componentId)}.part")

    fun muxFile(taskId: String, assetId: String, audioOnly: Boolean): File =
        File(taskDir(taskId), if (audioOnly) "${sanitize(assetId)}.mux.m4a" else "${sanitize(assetId)}.mux.mp4")

    fun sidecarFile(taskId: String, assetId: String, extension: String): File =
        File(taskDir(taskId), "${sanitize(assetId)}.${extension.trimStart('.')}")

    fun deleteTask(taskId: String) {
        taskDir(taskId).deleteRecursively()
    }

    companion object {
        fun underCache(cacheDir: File): DownloadDirectories =
            DownloadDirectories(File(cacheDir, DownloadHttpClients.WORK_DIR))

        private fun sanitize(id: String): String =
            id.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "id" }
    }
}
