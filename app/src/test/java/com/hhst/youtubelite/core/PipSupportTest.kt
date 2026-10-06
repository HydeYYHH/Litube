package com.hhst.youtubelite.core

import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.MemoryPrefStore
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.ui.extension.ExtensionViewModel
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

    @Test fun unsupportedCatalogHidesPipAndCannotEnableItButKeepsOtherPlaybackSettings() {
        val prefs = ExtensionManager(MemoryPrefStore())
        prefs.setEnabled(PreferenceKeys.ENABLE_PIP, false)
        val model = ExtensionViewModel(prefs, pipSupported = false)
        val keys = model.uiState.value.sections.flatMap { it.children }.mapNotNull { it.key }
        assertFalse(PreferenceKeys.ENABLE_PIP in keys)
        assertTrue(PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER in keys)
        assertTrue(PreferenceKeys.ENABLE_BACKGROUND_PLAY in keys)
        model.setEnabled(PreferenceKeys.ENABLE_PIP, true)
        assertFalse(prefs.isEnabled(PreferenceKeys.ENABLE_PIP))
        model.resetToDefault()
        assertFalse(model.uiState.value.toggles.containsKey(PreferenceKeys.ENABLE_PIP))
    }
}
