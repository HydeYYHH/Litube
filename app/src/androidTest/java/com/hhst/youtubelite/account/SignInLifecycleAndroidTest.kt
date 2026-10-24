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
import com.hhst.youtubelite.data.media.MmkvAuthStore
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

    @Test fun credentialsMoveOutOfTheBackedUpPreferenceFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = com.tencent.mmkv.MMKV.defaultMMKV()
        val key = "auth:review_probe"
        legacy.encode(key, "test-value")
        val auth = MmkvAuthStore(context)
        try {
            assertEquals("test-value", auth.read(key))
            assertFalse(legacy.containsKey(key))
            assertTrue(java.io.File(context.noBackupFilesDir, "auth").listFiles().orEmpty()
                .any { it.isFile && it.length() > 0 && !it.name.endsWith(".crc") })
        } finally { auth.write(key, null); legacy.removeValueForKey(key) }
    }

    @Test fun submittingSearchHistoryPersistsWithoutAnOpenSearchPanel() = kotlinx.coroutines.runBlocking {
        val kv = com.tencent.mmkv.MMKV.mmkvWithID("search-history-review")
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
