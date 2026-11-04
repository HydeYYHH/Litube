package com.hhst.youtubelite.core

import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.MemoryPrefStore
import com.hhst.youtubelite.extension.PreferenceKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipSupportTest {
    @Test fun unsupportedSystemsAndDevicesDisablePipRegardlessOfPreference() {
        for (sdk in listOf(23, 24, 25)) {
            assertFalse(PipSupport.available(sdk, true))
        }
        for (sdk in listOf(26, 30, 31, 36)) {
            assertFalse(PipSupport.available(sdk, false))
            assertFalse(PipSupport.available(sdk, true, enabled = false))
            assertTrue(PipSupport.available(sdk, true))
        }
    }

    /** The preference stores freely; runtime call sites gate on PipSupport. */
    @Test fun playbackPreferencesRoundTripIndependentlyOfPipSupport() {
        val prefs = ExtensionManager(MemoryPrefStore())
        prefs.setEnabled(PreferenceKeys.ENABLE_PIP, false)
        prefs.setEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY, true)
        prefs.setEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER, false)
        assertFalse(prefs.isEnabled(PreferenceKeys.ENABLE_PIP))
        assertTrue(prefs.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY))
        assertFalse(prefs.isEnabled(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER))
        prefs.resetToDefault()
        assertEquals(PreferenceKeys.DEFAULTS[PreferenceKeys.ENABLE_PIP], prefs.isEnabled(PreferenceKeys.ENABLE_PIP))
        assertEquals(PreferenceKeys.DEFAULTS[PreferenceKeys.ENABLE_BACKGROUND_PLAY], prefs.isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY))
    }
}
