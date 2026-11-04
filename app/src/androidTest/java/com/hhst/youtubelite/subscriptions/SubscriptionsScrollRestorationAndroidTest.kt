package com.hhst.youtubelite.subscriptions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.subscriptions.data.FixtureSubscriptionsRepository
import com.hhst.youtubelite.subscriptions.domain.*
import com.hhst.youtubelite.subscriptions.presentation.SubscriptionsScreen
import com.hhst.youtubelite.ui.design.AppTheme
import org.junit.Rule
import org.junit.Test

class SubscriptionsScrollRestorationAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restoringTheSameFeedKeepsItsVisibleCards() {
        val sample = FixtureSubscriptionsRepository.sampleVideos().first()
        val state = SubscriptionsUiState(signedIn = true, channels = SubscriptionsChannels.Ready(emptyList()),
            content = SubscriptionsContent.Feed(SubscriptionsPage(
                (0 until 24).map { sample.copy(id = "restored-$it") }, emptyList())))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                AppTheme {
                    Box(Modifier.requiredSize(390.dp, 844.dp)) { SubscriptionsScreen(state, {}) }
                }
            }
        }
        compose.onNodeWithTag("subscriptions-feed").performScrollToIndex(12)
        compose.onNodeWithTag("subs-item-restored-12").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("subs-item-restored-12").assertIsDisplayed()
    }
}
