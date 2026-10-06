package com.hhst.youtubelite.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Catalog shape and migrated keys. */
class ExtensionCatalogTest {


    @Test
    fun allToggleKeysAreKnownDefaults() {
        val keys = collectToggleKeys(Extension.catalog())
        assertTrue(keys.isNotEmpty())
        for (key in keys) {
            assertTrue(
                "catalog key `$key` missing from DEFAULTS",
                PreferenceKeys.DEFAULTS.containsKey(key),
            )
        }
    }

    @Test
    fun migratedMasterKeysPresent() {
        val keys = collectToggleKeys(Extension.catalog())
        val required = listOf(
            PreferenceKeys.ENABLE_DISPLAY_DISLIKES,
            PreferenceKeys.ENABLE_HIDE_SHORTS,
            PreferenceKeys.REMEMBER_QUALITY,
            PreferenceKeys.REMEMBER_PLAYBACK_SPEED,
            PreferenceKeys.REMEMBER_LAST_POSITION,
            PreferenceKeys.REMEMBER_RESIZE_MODE,
            PreferenceKeys.ENABLE_PIP,
            PreferenceKeys.ENABLE_IN_APP_MINI_PLAYER,
            PreferenceKeys.ENABLE_BACKGROUND_PLAY,
            PreferenceKeys.SKIP_SPONSORS,
            PreferenceKeys.SKIP_SELF_PROMO,
            PreferenceKeys.SKIP_POI_HIGHLIGHT,
            PreferenceKeys.GESTURE_TAP_WINDOWED,
            PreferenceKeys.GESTURE_FULLSCREEN_FULLSCREEN,
        )
        for (key in required) {
            assertTrue("missing key $key", keys.contains(key))
        }
    }

    @Test
    fun groupsHaveIds_togglesHaveKeys() {
        fun walk(nodes: List<Extension>) {
            for (node in nodes) {
                assertTrue(node.id.isNotBlank())
                if (node.isGroup) {
                    assertEquals(null, node.key)
                    walk(node.children)
                } else if (node.isNav) {
                    assertEquals(null, node.key)
                    assertFalse(node.isGroup)
                } else {
                    assertNotNull(node.key)
                    assertFalse(node.isGroup)
                }
            }
        }
        walk(Extension.catalog())
    }

    @Test
    fun downloadsNav_opensManagerAndSkipsToggleReset() {
        val nav = Extension.catalog().single { it.id == Extension.NAV_DOWNLOADS }
        assertTrue(nav.isNav)
        assertEquals(null, nav.key)
        val keys = collectToggleKeys(Extension.catalog())
        assertFalse(keys.contains(Extension.NAV_DOWNLOADS))
        assertFalse(PreferenceKeys.DEFAULTS.containsKey(Extension.NAV_DOWNLOADS))
    }

    private fun collectToggleKeys(nodes: List<Extension>): Set<String> {
        val out = mutableSetOf<String>()
        fun walk(list: List<Extension>) {
            for (node in list) {
                val key = node.key
                if (key != null && node.kind == ExtensionKind.TOGGLE) out.add(key) else walk(node.children)
            }
        }
        walk(nodes)
        return out
    }
}
