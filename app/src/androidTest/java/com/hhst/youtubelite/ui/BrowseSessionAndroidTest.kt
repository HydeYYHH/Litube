package com.hhst.youtubelite.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.channel.presentation.ChannelScreen
import com.hhst.youtubelite.playlist.domain.*
import com.hhst.youtubelite.playlist.presentation.PlaylistScreen
import com.hhst.youtubelite.post.domain.*
import com.hhst.youtubelite.post.presentation.PostDetailScreen
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import org.koin.core.context.GlobalContext
import org.junit.Before
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BrowseSessionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val preferences get() = GlobalContext.get().get<ExtensionManager>()
    private var originalHideShorts = false
    @Before fun savePreference() {
        originalHideShorts = preferences.isEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS)
        preferences.setEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS, false)
    }
    @After fun restorePreference() { preferences.setEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS, originalHideShorts) }

    @Test fun detailPagesKeepSubscriptionsAndReactToSignOutAndHiddenShorts() {
        var signedIn by mutableStateOf(true)
        var page by mutableStateOf("playlist")
        var selected = ""
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(390.dp, 844.dp)) {
                    when (page) {
                        "playlist" -> PlaylistScreen(PlaylistUiState("WL", signedIn = signedIn), {
                            if (it is PlaylistAction.Destination) selected = it.destination.name
                        }, {})
                        "channel" -> ChannelScreen(ChannelUiState(signedIn = signedIn), {
                            if (it is ChannelAction.SelectDestination) selected = it.destination.name
                        }, {})
                        else -> PostDetailScreen(PostDetailUiState(PostIdentity("studio", "post"), signedIn = signedIn), {
                            if (it is PostDetailAction.SelectDestination) selected = it.destination.name
                        }, {})
                    }
                }
            }
        }
        for (name in listOf("playlist", "channel", "post-detail")) {
            compose.runOnIdle { page = name; signedIn = true; preferences.setEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS, false); selected = "" }
            compose.onNodeWithTag("$name-nav-Subscriptions").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals("Subscriptions", selected) }
            compose.onNodeWithTag("$name-nav-Shorts").assertExists()
            compose.runOnIdle { preferences.setEnabled(PreferenceKeys.ENABLE_HIDE_SHORTS, true) }
            compose.onNodeWithTag("$name-nav-Shorts").assertDoesNotExist()
            compose.onNodeWithTag("$name-nav-Subscriptions").assertExists()
            compose.runOnIdle { signedIn = false }
            compose.onNodeWithTag("$name-nav-Subscriptions").assertDoesNotExist()
            compose.onNodeWithTag("$name-nav-Home").assertExists()
            compose.onNodeWithTag("$name-nav-You").assertExists()
        }
    }
}
