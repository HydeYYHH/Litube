package com.hhst.youtubelite.channel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.channel.data.FixtureChannelRepository
import com.hhst.youtubelite.channel.domain.*
import com.hhst.youtubelite.channel.presentation.ChannelScreen
import com.hhst.youtubelite.playlist.domain.PlaylistSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry

class ChannelMusicContentAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun albumsOnlyChannelShowsItsShelfAndOpensTheAlbum() {
        val album = PlaylistSummary("OLAKalbum", "Know My Name", "Artist", "UCartist", 1, "", "", null)
        val page = ChannelPage(FixtureChannelRepository.profile().copy(name = "Artist - Topic", banner = null), emptyList(),
            playlists = listOf(album), homeShelves = listOf(ChannelHomeShelf("Albums & Singles", playlists = listOf(album))))
        val actions = mutableListOf<ChannelAction>()
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
            Box(Modifier.requiredSize(390.dp, 844.dp)) {
                ChannelScreen(ChannelUiState(content = ChannelContent.Ready(page)), actions::add, {})
            }
        } }
        compose.onNodeWithText("Albums & Singles").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Know My Name").performScrollTo().performClick()
        assertEquals(ChannelAction.OpenPlaylist("OLAKalbum"), actions.last())
        compose.onNodeWithTag("channel-empty").assertDoesNotExist()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "music-channel-content.png")
            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
