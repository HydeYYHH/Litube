package com.hhst.youtubelite.account

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.hhst.youtubelite.account.presentation.SignInViewModel
import com.hhst.youtubelite.data.media.PreferencesAuthStore
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SignInLifecycleAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun rotationKeepsOneCodeAndClosingCancelsThePoll() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var before: SignInViewModel? = null
        ActivityScenario.launch<SignInActivity>(Intent(context, SignInActivity::class.java)).use { host ->
            compose.onNodeWithTag("signin-code").assertIsDisplayed()
            host.onActivity { before = ViewModelProvider(it)[SignInViewModel::class.java] }
            host.recreate()
            compose.onNodeWithTag("signin-code").assertIsDisplayed()
            host.onActivity { assertSame(before, ViewModelProvider(it)[SignInViewModel::class.java]) }
        }
        assertFalse(before!!.viewModelScope.coroutineContext[Job]!!.isActive)
    }

    @Test fun credentialsAreSealedAndExcludedFromBackups() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = "auth:review_probe"
        val auth = PreferencesAuthStore(context)
        try {
            auth.write(key, "test-value")
            assertEquals("test-value", auth.read(key))
            val prefsFile = java.io.File(context.applicationInfo.dataDir, "shared_prefs/media-auth.xml")
            assertTrue(prefsFile.exists())
            assertFalse(prefsFile.readText().contains("test-value"))
            fun ruleText(name: String): String {
                val id = context.resources.getIdentifier(name, "xml", context.packageName)
                val parser = context.resources.getXml(id)
                val text = StringBuilder()
                while (parser.next() != android.content.res.XmlResourceParser.END_DOCUMENT) {
                    for (i in 0 until parser.attributeCount) text.append(parser.getAttributeValue(i)).append(' ')
                }
                return text.toString()
            }
            assertTrue(ruleText("backup_rules").contains("media-auth.xml"))
            assertTrue(ruleText("data_extraction_rules").contains("media-auth.xml"))
        } finally { auth.write(key, null) }
    }

    @Test fun submittingSearchHistoryPersistsWithoutAnOpenSearchPanel() = kotlinx.coroutines.runBlocking {
        val kv = com.hhst.youtubelite.core.PreferenceStore.of(
            ApplicationProvider.getApplicationContext<Context>(), "search-history-review")
        kv.clearAll()
        try {
            val rows = com.hhst.youtubelite.data.media.MediaSearchSuggestions(
                com.hhst.youtubelite.data.media.MediaClient(okhttp3.OkHttpClient()), kv)
            rows.record("blender open movie")
            assertEquals(listOf("blender open movie"), rows.rows("").history)
            rows.remove("blender open movie")
            assertTrue(rows.rows("").history.isEmpty())
        } finally { kv.clearAll() }
    }
}
