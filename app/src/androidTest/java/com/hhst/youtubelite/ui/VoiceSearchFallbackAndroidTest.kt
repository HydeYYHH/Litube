package com.hhst.youtubelite.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.home.data.HomeScenario
import org.junit.Assert.assertFalse
import org.junit.Test

class VoiceSearchFallbackAndroidTest {
    @Test fun voiceSearchNeverCrashesTheHostWhateverPathTheDeviceTakes() {
        val scenario = ActivityScenario.launch<HomeActivity>(
            Intent(ApplicationProvider.getApplicationContext(), HomeActivity::class.java)
                .putExtra(HomeActivity.EXTRA_SCENARIO, HomeScenario.Feed.argument))
        scenario.onActivity { it.openVoiceSearch() }
        // Give recognition a moment to start (external dialog or in-process listening); whichever path this device takes, the host stays alive.
        Thread.sleep(3_000)
        scenario.onActivity { assertFalse(it.isFinishing) }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent BACK").close()
        Thread.sleep(500)
        scenario.close()
    }
}
