package com.hhst.youtubelite.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.about.AboutActivity
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DiagnosticSaveAndroidTest {
    @get:Rule val rule = createAndroidComposeRule<AboutActivity>()

    private fun pick(resultCode: Int, uri: Uri?, status: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = Instrumentation.ActivityMonitor(
            IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("application/zip")
            },
            Instrumentation.ActivityResult(resultCode, uri?.let { Intent().setData(it) }), true,
        )
        instrumentation.addMonitor(monitor)
        try {
            rule.onNodeWithText(rule.activity.getString(R.string.about_save_logs)).performScrollTo().assertIsEnabled()
                .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            rule.waitUntil(30_000) { monitor.hits > 0 }
            rule.waitUntil(30_000) {
                runCatching { rule.onNodeWithText(rule.activity.getString(status)).fetchSemanticsNode() }.isSuccess
            }
            rule.onNodeWithText(rule.activity.getString(status)).performScrollTo().assertIsDisplayed()
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun pickerCancellationKeepsTheScreenUsable() {
        pick(Activity.RESULT_CANCELED, null, R.string.about_save_cancelled)
        rule.onNodeWithText(rule.activity.getString(R.string.about_save_logs)).performScrollTo().assertIsDisplayed()
    }

    @Test fun selectedDestinationContainsTheExistingDiagnosticZip() {
        val output = File(rule.activity.cacheDir, "saved-diagnostic-test.zip")
        try {
            pick(Activity.RESULT_OK, Uri.fromFile(output), R.string.about_save_done)
            ZipFile(output).use { zip ->
                assertNotNull(zip.getEntry("manifest.json"))
                assertTrue(zip.size() > 1)
                zip.entries().toList().filterNot { it.isDirectory }.forEach { entry ->
                    assertEquals(entry.size, zip.getInputStream(entry).use { it.readBytes() }.size.toLong())
                }
            }
        } finally { output.delete() }
    }

    @Test fun deniedDocumentProviderReportsWriteFailure() {
        pick(Activity.RESULT_OK, Uri.parse("content://invalid.litube.test/denied"), R.string.about_save_failed)
    }
}
