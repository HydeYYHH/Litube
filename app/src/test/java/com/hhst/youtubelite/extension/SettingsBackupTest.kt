package com.hhst.youtubelite.extension

import com.hhst.youtubelite.downloader.core.DownloadPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsBackupTest {

    private lateinit var store: MemoryPrefStore
    private lateinit var manager: ExtensionManager
    private lateinit var downloadPrefs: RecordingDownloadPrefs

    private class RecordingDownloadPrefs : DownloadPrefs {
        var savedWifiOnly: Boolean? = null
        var savedMaxConnections: Int? = null
        var savedQuality: String? = null

        override fun setWifiOnly(value: Boolean) { savedWifiOnly = value }
        override fun setMaxConnections(value: Int) { savedMaxConnections = value }
        override fun setDefaultQuality(value: String) { savedQuality = value }
    }

    @Before
    fun setUp() {
        store = MemoryPrefStore()
        manager = ExtensionManager(store)
        downloadPrefs = RecordingDownloadPrefs()
    }

    @Test
    fun exportParseApply_roundTripsEveryValue() {
        manager.setEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS, true)
        manager.setEnabled(PreferenceKeys.SKIP_SPONSORS, false)
        manager.setHapticStrength(60)
        val json = SettingsBackup.export(manager, downloadPrefs)

        val target = ExtensionManager(MemoryPrefStore())
        val restored = SettingsBackup.parse(json)
        SettingsBackup.apply(restored!!, target, downloadPrefs)

        assertEquals(manager.allPreferences(), target.allPreferences())
        assertEquals(60, target.hapticStrength())
    }

    @Test
    fun apply_restoresDownloadPreferences() {
        val json = SettingsBackup.export(manager, downloadPrefs)
        val backup = SettingsBackup.parse(json)!!
        val target = ExtensionManager(MemoryPrefStore())
        SettingsBackup.apply(backup, target, downloadPrefs)
        assertEquals(false, downloadPrefs.savedWifiOnly)
        assertEquals(4, downloadPrefs.savedMaxConnections)
        assertEquals("1080p", downloadPrefs.savedQuality)
    }

    @Test
    fun importPreferences_ignoresUnknownKeys() {
        val backup = SettingsBackupData(
            schema = SettingsBackup.SCHEMA,
            preferences = mapOf("not_a_real_key" to true, PreferenceKeys.ENABLE_PIP to false),
        )
        SettingsBackup.apply(backup, manager, downloadPrefs)
        assertFalse(store.contains("preferences:not_a_real_key"))
        assertFalse(manager.isEnabled(PreferenceKeys.ENABLE_PIP))
    }

    @Test
    fun importPreferences_notifiesOnceWithWildcardAndBumpsVersionOnce() {
        val keys = mutableListOf<String>()
        manager.addOnChangedListener { keys += it }
        val version = manager.version()
        manager.importPreferences(
            mapOf(PreferenceKeys.ENABLE_PIP to false, PreferenceKeys.REMEMBER_QUALITY to false),
            45,
        )
        assertEquals(listOf("*"), keys)
        assertEquals(version + 1, manager.version())
    }

    @Test
    fun importPreferences_noChange_noVersionBump() {
        val version = manager.version()
        manager.importPreferences(emptyMap(), null)
        assertEquals(version, manager.version())
    }

    @Test
    fun importPreferences_nullHaptic_keepsCurrent() {
        manager.setHapticStrength(70)
        manager.importPreferences(mapOf(PreferenceKeys.ENABLE_PIP to false), null)
        assertEquals(70, manager.hapticStrength())
    }

    @Test
    fun parse_rejectsInvalidJson() {
        assertNull(SettingsBackup.parse("not json"))
        assertNull(SettingsBackup.parse(""))
        assertNull(SettingsBackup.parse("{\"schema\":\"other\"}"))
    }
}
