package com.hhst.youtubelite.extension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Contract checks for the settings-page inject script. */
class ExtensionInjectScriptTest {

    private val script: String by lazy { readExtensionScript() }

    @Test
    fun assetIsTheExpressionTheInjectorParenthesizes() {
        // ExtensionInjector evaluates `var report = (<asset>);` — a trailing
        // semicolon would move the script out of expression position and the
        // whole evaluation would fail to parse.
        assertFalse(script.trimEnd().endsWith(";"))
    }

    @Test
    fun requiresCore() {
        // The script builds nothing itself: icons and labels come from the
        // shared runtime, so a missing runtime is a named failure, not a
        // partial injection.
        assertTrue(script.contains("window.Lite"))
        assertTrue(script.contains("lite core not loaded"))
    }

    @Test
    fun iconIsFailClosed() {
        // A clone without a usable svg returns null and aborts the pass;
        // the retry chain re-runs it, no forged markup.
        assertTrue(script.contains("if (!Lite.icon(button, def.icon)) return null;"))
        assertFalse(script.contains("createElementNS"))
        assertFalse(script.contains("innerHTML"))
    }

    @Test
    fun reportsNamedElementsWhenDomMissing() {
        val required = listOf(
            "ytm-settings",
            "template_button",
            "missing_settings_root",
            "settings list root missing",
        )
        for (element in required) {
            assertTrue("missing failure element `$element`", script.contains(element))
        }
    }

    @Test
    fun skipsNonSettingsPages() {
        assertTrue(script.contains("not_settings_page"))
        assertTrue(script.contains("skipped"))
        assertTrue(script.contains("select_site"))
    }

    @Test
    fun retriesBoundedWithoutObserver() {
        // Late settings DOM gets a short retry chain that clears the report's
        // earlier failure; SPA navigations re-run the script natively, so no
        // observer-driven loop is needed.
        assertTrue(script.contains("Lite.retry(() => {"))
        assertFalse(script.contains("new MutationObserver"))
    }

    @Test
    fun doesNotBareThrowOnMissingDom() {
        val throws = script.lines().filter {
            it.contains("throw ") && !it.trimStart().startsWith("//") &&
                !it.trimStart().startsWith("*")
        }
        assertTrue("unexpected throw: $throws", throws.isEmpty())
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
