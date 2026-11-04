package com.hhst.youtubelite.player.surface

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hhst.youtubelite.cast.CastDevice
import com.hhst.youtubelite.player.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Device discovery remains active for the lifetime of the chooser. */
class CastDialogAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun castDialogCoversUnavailableDevicesDiscoveryAndCastingStates() {
        compose.mainClock.autoAdvance = false
        var state by mutableStateOf(PlayerUiState(castAvailable = false))
        var showDialog by mutableStateOf(true)
        var deviceSelection: String? = null
        var stopCount = 0
        var refreshCount = 0
        val discovery = mutableListOf<Boolean>()
        compose.setContent {
            MaterialTheme {
                if (showDialog) CastDialog(
                    state = state,
                    onDevice = { deviceSelection = it },
                    onStop = { stopCount++ },
                    onShareLink = {},
                    onCloseLink = {},
                    onRefresh = { refreshCount++ },
                    onDiscovery = { discovery += it },
                    onDismiss = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("Searching…").assertIsDisplayed()
        compose.onNodeWithText("Cast unavailable").assertDoesNotExist()
        compose.onNodeWithText("Available devices").assertIsDisplayed()
        compose.onNodeWithText("Open on any device").assertIsDisplayed()
        assertEquals(listOf(true), discovery)
        assertTrue(refreshCount >= 1)

        compose.runOnIdle { state = PlayerUiState(castAvailable = true) }
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("Searching…").assertIsDisplayed()

        compose.runOnIdle {
            state = PlayerUiState(
                castAvailable = true,
                castDevices = listOf(
                    CastDevice("living-room", "Living room TV", selected = false),
                    CastDevice("bedroom", "Bedroom speaker", selected = true),
                ),
            )
        }
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("Living room TV").assertIsDisplayed()
        compose.onNodeWithText("Bedroom speaker").assertIsDisplayed()
        compose.onNodeWithText("Living room TV").performClick()
        assertEquals("living-room", deviceSelection)

        compose.runOnIdle {
            state = PlayerUiState(
                casting = true,
                castingDeviceName = "Living room TV",
                castAvailable = true,
                castDevices = listOf(CastDevice("living-room", "Living room TV", selected = true)),
            )
        }
        compose.mainClock.advanceTimeBy(50)
        compose.onNodeWithText("Casting to Living room TV").assertIsDisplayed()
        compose.onNodeWithText("Open on any device").assertDoesNotExist()
        compose.onNodeWithText("Stop casting").performClick()
        assertEquals(1, stopCount)

        // Disposing the dialog ends discovery for the session.
        compose.runOnIdle { showDialog = false }
        compose.mainClock.advanceTimeBy(50)
        assertEquals(listOf(true, false), discovery)
    }
}
