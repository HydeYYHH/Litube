package com.hhst.youtubelite.player.datasource

import android.content.ContextWrapper
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerCacheDirectoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context() = object : ContextWrapper(null) {
        override fun getCacheDir() = File(temporary.root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(temporary.root, "no-backup").apply { mkdirs() }
    }

    @Test fun migratesPlaybackBytesIntoSystemClearableCache() {
        val context = context()
        val legacy = File(context.noBackupFilesDir, "player").apply { mkdirs() }
        File(legacy, "media").writeText("cached media")
        val result = PlayerDataSource.cacheDirectory(context)
        assertEquals(File(context.cacheDir, "player"), result)
        assertEquals("cached media", File(result, "media").readText())
        assertFalse(legacy.exists())
    }

    @Test fun keepsCurrentCacheAndUnrelatedFilesWhenLegacyCacheAlsoExists() {
        val context = context()
        val current = File(context.cacheDir, "player").apply { mkdirs() }
        File(current, "media").writeText("current")
        val legacy = File(context.noBackupFilesDir, "player").apply { mkdirs() }
        File(legacy, "media").writeText("old")
        val unrelated = File(context.noBackupFilesDir, "download").apply { writeText("keep") }
        PlayerDataSource.cacheDirectory(context)
        assertEquals("current", File(current, "media").readText())
        assertFalse(legacy.exists())
        assertEquals("keep", unrelated.readText())
    }
}
