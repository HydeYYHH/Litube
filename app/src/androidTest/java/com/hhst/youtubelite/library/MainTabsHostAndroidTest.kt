package com.hhst.youtubelite.library

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.hhst.youtubelite.home.HomeActivity
import com.hhst.youtubelite.home.data.HomeScenario
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After

class MainTabsHostAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before fun enterPhoneWindow() = com.hhst.youtubelite.core.PhoneWindow.enter()

    @After fun exitPhoneWindow() {
        com.hhst.youtubelite.core.PhoneWindow.exit()
    }

    @Test fun homeRoundTripRetainsTheLibraryInstanceAndScrollPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<HomeActivity>(Intent(context, HomeActivity::class.java)
            .putExtra(HomeActivity.EXTRA_SCENARIO, HomeScenario.Feed.argument)).use {
            fun await(tag: String) = compose.waitUntil(15_000) {
                runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
            }
            try {
                await("home-screen")
                compose.onNodeWithTag("nav-You").performClick()
                await("you-library")
                lateinit var first: YouActivity
                instrumentation.runOnMainSync {
                    first = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<YouActivity>().single()
                }
                compose.onNodeWithTag("you-library").performTouchInput { swipeUp() }
                compose.waitForIdle()
                val position = compose.onNodeWithTag("you-library").fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue("The library must scroll before the round trip", position > 0f)
                compose.onNodeWithTag("main-nav-Home").performClick()
                await("home-screen")
                compose.onNodeWithTag("nav-Subscriptions").assertExists()
                compose.onNodeWithTag("nav-You").performClick()
                await("you-library")
                instrumentation.runOnMainSync {
                    val current = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<YouActivity>().single()
                    assertSame(first, current)
                }
                val restored = compose.onNodeWithTag("you-library").fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertEquals(position, restored, 0.1f)
            } finally {
                instrumentation.runOnMainSync {
                    Stage.entries.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                        .filterIsInstance<YouActivity>().distinct().forEach { it.finish() }
                }
            }
        }
    }
}
