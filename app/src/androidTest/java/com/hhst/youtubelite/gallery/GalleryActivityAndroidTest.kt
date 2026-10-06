package com.hhst.youtubelite.gallery

import android.app.Activity
import android.app.Instrumentation.ActivityMonitor
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.R
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GalleryActivityAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun galleryRetainsClickedPageAndSavesSharesActualImageFormat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "gallery").apply { mkdirs() }
        val urls = (1..3).map { "https://yt3.ggpht.com/upgrade-gallery-$it.webp" }
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val files = urls.map { url ->
            val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            File(directory, key).also { file -> file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        }
        bitmap.recycle()
        val saved = File(directory, "upgrade-save-result.jpg").apply { delete() }
        val resultUri = FileProvider.getUriForFile(context, "${context.packageName}.download.fileprovider", saved)
        val saveFilter = IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addDataType("image/jpeg"); addCategory(Intent.CATEGORY_OPENABLE) }
        val saveMonitor = ActivityMonitor(saveFilter, ActivityResult(Activity.RESULT_OK, Intent().setData(resultUri)), true)
        val shareMonitor = ActivityMonitor(IntentFilter(Intent.ACTION_CHOOSER), ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            ActivityScenario.launch<GalleryActivity>(Intent(context, GalleryActivity::class.java).apply {
                putStringArrayListExtra("images", ArrayList(urls)); putExtra("index", 1)
            }).use { scenario ->
                val imageDescription = context.getString(R.string.gallery_image, 2)
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(imageDescription).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("2 / 3").assertIsDisplayed()
                val actions = compose.onNodeWithContentDescription(imageDescription).fetchSemanticsNode().config[SemanticsActions.CustomActions]
                compose.runOnIdle { assertTrue(actions.first().action()); assertTrue(actions.last().action()) }
                scenario.recreate()
                compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(imageDescription).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("2 / 3").assertIsDisplayed()
                instrumentation.addMonitor(saveMonitor); instrumentation.addMonitor(shareMonitor)
                compose.onNodeWithContentDescription(context.getString(R.string.gallery_save)).performClick()
                compose.waitUntil(10_000) { saved.isFile && saved.length() == files[1].length() }
                assertTrue(instrumentation.checkMonitorHit(saveMonitor, 1))
                assertArrayEquals(files[1].readBytes(), saved.readBytes())
                val sharedBefore = directory.listFiles().orEmpty().filter { it.name.startsWith("share-") }.toSet()
                compose.onNodeWithContentDescription(context.getString(R.string.share)).performClick()
                compose.waitUntil(10_000) { shareMonitor.hits > 0 }
                val shared = directory.listFiles().orEmpty().first { it.name.startsWith("share-") && it !in sharedBefore }
                assertTrue(shared.extension == "jpg")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.download.fileprovider", shared)
                assertArrayEquals(files[1].readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                shared.delete()
            }
        } finally {
            instrumentation.removeMonitor(saveMonitor); instrumentation.removeMonitor(shareMonitor)
            files.forEach { it.delete() }; saved.delete()
        }
    }
}
