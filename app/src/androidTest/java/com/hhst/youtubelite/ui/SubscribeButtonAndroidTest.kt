package com.hhst.youtubelite.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.SubscribeButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscribeButtonAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var clicks = 0
    private var rtl by mutableStateOf(false)
    private var fontScale by mutableStateOf(1f)
    private var pending by mutableStateOf(false)
    private var enabled by mutableStateOf(true)

    @Composable
    private fun ChannelRow() {
        CompositionLocalProvider(
            LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            LocalDensity provides Density(1f, fontScale),
        ) {
            Row(Modifier.width(320.dp).heightIn(min = 48.dp)) {
                Box(Modifier.size(32.dp).testTag("avatar"))
                AppText("@author", Modifier.padding(start = 8.dp).weight(1f), size = 14, line = 20, maxLines = 1)
                SubscribeButton(subscribed = false, onClick = { clicks++ }, modifier = Modifier.padding(start = 8.dp),
                    enabled = enabled, pending = pending, onDarkSurface = true, testTag = "subscribe")
            }
        }
    }

    private fun render() {
        compose.setContent { ChannelRow() }
        compose.waitForIdle()
    }

    private fun bounds(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun rowMirrorsUnderRtlAndStaysLtrOtherwise() {
        render()
        val ltrAvatar = bounds("avatar")
        val ltrPill = bounds("subscribe")
        assertTrue("LTR: the avatar leads the pill", ltrAvatar.center.x < ltrPill.center.x)

        rtl = true
        compose.waitForIdle()
        val rtlAvatar = bounds("avatar")
        val rtlPill = bounds("subscribe")
        assertTrue("RTL: the avatar mirrors to the right of the pill", rtlAvatar.center.x > rtlPill.center.x)
    }

    @Test
    fun pendingAndDisabledButtonsIgnoreTaps() {
        render()
        pending = true
        compose.waitForIdle()
        compose.onNodeWithTag("subscribe", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("A pending write must never be double-submitted", 0, clicks)
        pending = false
        enabled = false
        compose.waitForIdle()
        compose.onNodeWithTag("subscribe", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals("An unknown channel must not submit", 0, clicks)
        enabled = true
        compose.waitForIdle()
        compose.onNodeWithTag("subscribe", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test
    fun twoTimesFontAt320dpKeepsTheTouchTargetAndStaysInTheRow() {
        fontScale = 2f
        render()
        val pill = bounds("subscribe")
        assertTrue("Touch target stays at least 48dp tall", pill.height >= 48f)
        assertTrue("Pill stays inside the 320dp row", pill.right <= 320f && pill.left >= 0f)
        assertTrue("Pill never overlaps the avatar", pill.left >= bounds("avatar").right)
    }
}
