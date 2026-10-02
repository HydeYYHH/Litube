package com.hhst.youtubelite.extension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Contract checks for the settings-button inject script. */
class ExtensionInjectScriptTest {

    private val script: String by lazy { readExtensionScript() }

    @Test
    fun assetExistsAndReturnsReport() {
        assertTrue(script.isNotBlank())
        assertTrue(script.trimStart().startsWith("(function"))
        assertTrue(script.contains("return result") || script.contains("return report"))
    }

    @Test
    fun appliesMaterialIconViewBox() {
        // Without this viewBox the path draws off-canvas on YouTube's svg.
        assertTrue(script.contains(ExtensionInjector.ICON_VIEW_BOX))
        assertTrue(script.contains("setAttribute('viewBox'"))
        assertTrue(script.contains("setPath"))
    }

    @Test
    fun iconPathAndButtonIdPresent() {
        assertTrue(script.contains("pathSet"))
        assertTrue(script.contains("M384-144"))
        assertTrue(script.contains(ExtensionInjector.BUTTON_ID))
        assertTrue(script.contains("downloaderButton"))
    }

    @Test
    fun reportsNamedElementsWhenDomMissing() {
        // Failure taxonomy: missing settings root vs template clone failure.
        val required = listOf(
            "ytm-settings",
            "template_button",
            "settings list root missing",
        )
        for (element in required) {
            assertTrue("missing failure element `$element`", script.contains(element))
        }
    }

    @Test
    fun noOverlayFallbackWithoutYtmSettingsRoot() {
        // List entries exist only as clones of the ytm-settings template row.
        // Layouts without that root report missing_settings_root and retry —
        // no fixed overlay, no foreign node in the app shell.
        assertTrue(script.contains("missing_settings_root"))
        assertFalse(script.contains("liteSettingsFab"))
        assertFalse(script.contains("position:fixed"))
        assertFalse(script.contains("floating_fallback"))
        assertFalse(script.contains("removeFab"))
        // No inserting before an arbitrary app-shell child.
        assertFalse(script.contains("insertBefore(button, anchorRoot"))
    }

    @Test
    fun noMutationObserverFreezeLoop() {
        // Repair is event + retry-chain driven only; an observer-driven loop
        // can re-run mid-navigation and freeze the page. Assert the mechanism
        // rather than a comment word.
        assertFalse(script.contains("new MutationObserver"))
        assertFalse(script.contains("__liteExtObserver"))
        assertTrue(script.contains("scheduleRetries"))
        assertTrue(script.contains("RETRY_MS"))
    }

    @Test
    fun skipsNonSettingsPages() {
        assertTrue(script.contains("not_settings_page"))
        assertTrue(script.contains("skipped"))
        assertTrue(script.contains("select_site"))
    }

    @Test
    fun retriesWhenSettingsDomLate() {
        assertTrue(script.contains("scheduleRetries") || script.contains("RETRY_MS"))
        // SPA navigations re-trigger the retry chain without an observer.
        assertTrue(script.contains("yt-navigate-finish"))
        assertTrue(script.contains("yt-page-data-updated"))
    }

    @Test
    fun opensExtensionAndDownloadsViaBridge() {
        // Sibling settings entries route through one dynamic bridge dispatch.
        assertTrue(script.contains("bridge[action]()"))
        assertTrue(script.contains("'extension'"))
        assertTrue(script.contains("'download'"))
        assertTrue(script.contains("window.lite") || script.contains("window.Bridge"))
    }

    @Test
    fun doesNotBareThrowOnMissingDom() {
        val throws = script.lines().filter {
            it.contains("throw ") && !it.trimStart().startsWith("//")
        }
        assertTrue("unexpected throw: $throws", throws.isEmpty())
    }

    @Test
    fun injectorAssetPathMatchesFile() {
        assertTrue(
            File("src/main/assets", ExtensionInjector.ASSET).exists() ||
                File("app/src/main/assets", ExtensionInjector.ASSET).exists(),
        )
    }

    @Test
    fun reEvaluationIsIdempotent() {
        // Native re-injects on every SPA navigation: existing rows short-circuit
        // with reads only, and the retry chain is single-flight guarded.
        assertTrue(script.contains("getElementById(id)"))
        assertTrue(script.contains("__liteExtRetrying"))
        assertTrue(script.contains("__liteExtSpaBound"))
    }

    private fun readExtensionScript(): String {
        val candidates = listOf(
            File("src/main/assets/${ExtensionInjector.ASSET}"),
            File("app/src/main/assets/${ExtensionInjector.ASSET}"),
            File("../src/main/assets/${ExtensionInjector.ASSET}"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("extension.js not found; tried $candidates")
        return file.readText(Charsets.UTF_8)
    }
}
