package com.hhst.youtubelite.ui.extension

import androidx.lifecycle.ViewModelStore
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.MemoryPrefStore
import com.hhst.youtubelite.extension.PreferenceKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionViewModelTest {
    @Test fun settingsRefreshUntilCleared() {
        val manager = ExtensionManager(MemoryPrefStore())
        val model = ExtensionViewModel(manager)
        val store = ViewModelStore().apply { put("settings", model) }
        model.setEnabled(PreferenceKeys.ENABLE_PIP, false)
        model.setHapticStrength(80)
        assertEquals(false, model.uiState.value.toggles[PreferenceKeys.ENABLE_PIP])
        assertEquals(80, model.uiState.value.hapticStrength)
        model.resetToDefault()
        assertEquals(PreferenceKeys.DEFAULTS[PreferenceKeys.ENABLE_PIP], model.uiState.value.toggles[PreferenceKeys.ENABLE_PIP])
        assertEquals(30, model.uiState.value.hapticStrength)
        manager.importPreferences(mapOf(PreferenceKeys.ENABLE_PIP to false, PreferenceKeys.REMEMBER_QUALITY to false), 67)
        assertEquals(false, model.uiState.value.toggles[PreferenceKeys.ENABLE_PIP])
        assertEquals(false, model.uiState.value.toggles[PreferenceKeys.REMEMBER_QUALITY])
        assertEquals(67, model.uiState.value.hapticStrength)
        store.clear()
        manager.setHapticStrength(20)
        assertEquals(67, model.uiState.value.hapticStrength)
    }
}
