package com.hhst.youtubelite.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.ChannelContent
import com.hhst.youtubelite.channel.domain.ChannelUiState
import com.hhst.youtubelite.channel.presentation.ChannelScreen
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.ui.components.MainNavigationBar
import com.hhst.youtubelite.ui.design.AppTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class ContentPreferencesAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val preferences get() = GlobalContext.get().get<ExtensionManager>()
    private var original = false

    @Before fun save() {
        original = preferences.isEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS)
        preferences.setEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS, false)
    }

    @After fun restore() {
        preferences.setEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS, original)
    }

    @Test fun navigationRespondsToThePreferenceWithoutRecreatingItsHost() {
        compose.setContent { AppTheme { MainNavigationBar("test", true, null, onSelect = {}) } }
        compose.onNodeWithTag("test-nav-Shorts").assertExists()
        compose.runOnIdle { preferences.setEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS, true) }
        compose.onNodeWithTag("test-nav-Shorts").assertDoesNotExist()
        compose.onNodeWithTag("test-nav-Subscriptions").assertExists()
        compose.runOnIdle { preferences.setEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS, false) }
        compose.onNodeWithTag("test-nav-Shorts").assertExists()
    }

    @Test fun channelTabsRespondToTheSamePreference() {
        compose.setContent {
            ChannelScreen(ChannelUiState(content = ChannelContent.Ready(FixtureChannelRepository.samplePage())), {}, {})
        }
        compose.onNodeWithTag("channel-tab-Shorts").assertExists()
        compose.runOnIdle { preferences.setEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS, true) }
        compose.onNodeWithTag("channel-tab-Shorts").assertDoesNotExist()
        compose.onNodeWithTag("channel-tab-Videos").assertExists()
    }

    @Test fun homeRespondsToThePreferenceWithoutReloadingItsFeed() {
        val page = (com.hhst.youtubelite.home.data.HomeScenario.Feed.initialContent() as
            com.hhst.youtubelite.home.domain.HomeContent.Feed).page
        val shelf = page.items.filterIsInstance<com.hhst.youtubelite.home.domain.FeedItem.Shorts>().first()
        val state = com.hhst.youtubelite.home.domain.HomeUiState(content =
            com.hhst.youtubelite.home.domain.HomeContent.Feed(page.copy(items = listOf(shelf))))
        compose.setContent { com.hhst.youtubelite.home.presentation.HomeScreen(state, {}) }
        compose.onNodeWithTag("shorts-shelf").assertExists()
        compose.runOnIdle { preferences.setEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS, true) }
        compose.onNodeWithTag("shorts-shelf").assertDoesNotExist()
        compose.runOnIdle { preferences.setEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS, false) }
        compose.onNodeWithTag("shorts-shelf").assertExists()
    }
}
