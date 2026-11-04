package com.hhst.youtubelite.shorts.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R

@Composable
internal fun ShortPlaybackControls(
    playing: Boolean,
    onToggle: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    loading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    onDetails: (() -> Unit)? = null,
    active: Boolean = true,
    paused: Boolean = !playing,
    ready: Boolean = true,
    notice: String? = null,
    content: @Composable () -> Unit,
) {
    val buffering = loading || !ready
    val toggle by rememberUpdatedState(onToggle)
    val holdStart by rememberUpdatedState(onHoldStart)
    val holdEnd by rememberUpdatedState(onHoldEnd)
    val canHold by rememberUpdatedState(active && !buffering && error == null && notice == null)
    val canToggle by rememberUpdatedState(active && ready && error == null && notice == null)
    var holding by remember { mutableStateOf(false) }
    var pauseVisible by remember { mutableStateOf(false) }
    LaunchedEffect(active, paused, buffering, error, notice) {
        pauseVisible = false
        if (active && paused && !buffering && error == null && notice == null) {
            kotlinx.coroutines.delay(120)
            pauseVisible = true
        }
    }
    val toggleLabel = stringResource(if (playing) R.string.action_pause else R.string.action_play)
    DisposableEffect(Unit) {
        onDispose { if (holding) { holding = false; holdEnd() } }
    }
    Box(Modifier.fillMaxSize()) {
        content()
        Box(Modifier.fillMaxSize().testTag("short-playback-controls")
            .semantics {
                role = Role.Button
                onClick(toggleLabel) {
                    if (canToggle) {
                        toggle()
                        true
                    } else false
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    try {
                        val tap = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var released = false
                            while (!released) {
                                val changes = awaitPointerEvent().changes
                                val change = changes.singleOrNull { it.id == down.id }
                                    ?: return@withTimeoutOrNull false
                                if (changes.size != 1 || change.isConsumed ||
                                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                ) return@withTimeoutOrNull false
                                released = !change.pressed
                                if (released) change.consume()
                            }
                            true
                        }
                        if (tap == true && canToggle) toggle()
                        if (tap == null && canHold) {
                            holding = true
                            holdStart()
                            do {
                                val changes = awaitPointerEvent().changes
                                val change = changes.singleOrNull { it.id == down.id } ?: break
                                if (changes.size != 1 || change.isConsumed) break
                                change.consume()
                            } while (change.pressed)
                        }
                    } finally {
                        if (holding) {
                            holding = false
                            holdEnd()
                        }
                    }
                }
            })
        if (holding && active) {
            Text(stringResource(R.string.speed_hold_2x), color = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp).testTag("short-speed-hold"))
        } else if (error != null && active) {
            com.hhst.youtubelite.player.surface.ErrorOverlay(
                message = error, onRetry = onRetry, onDetails = onDetails, modifier = Modifier.align(Alignment.Center))
        } else if (notice != null && active) {
            Text(notice, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp).testTag("short-access-notice"))
        } else if (buffering && active) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.Center).size(24.dp).testTag("short-buffering"))
        } else if (pauseVisible && active && paused && !buffering) {
            Icon(painterResource(R.drawable.ic_play), contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center).testTag("short-paused")
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    .padding(12.dp).size(40.dp))
        }
    }
}
