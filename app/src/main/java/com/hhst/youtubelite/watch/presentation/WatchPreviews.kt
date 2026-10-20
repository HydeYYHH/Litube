package com.hhst.youtubelite.watch.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.hhst.youtubelite.watch.data.FixtureWatchRepository
import com.hhst.youtubelite.watch.domain.WatchContent
import com.hhst.youtubelite.watch.domain.WatchUiState

private fun ready() = WatchUiState(
    videoId = "bunny",
    content = WatchContent.Ready(FixtureWatchRepository.samplePage("bunny")),
)

/** Previews swap the real player for a black block so they stay local. */
private val placeholderSlot: @Composable (Modifier) -> Unit = { slot ->
    Box(slot.background(Color.Black))
}

@Preview(name = "Content · 390", widthDp = 390, heightDp = 844)
@Preview(name = "Content · 840", widthDp = 840, heightDp = 1000)
@Preview(name = "Content · 1280", widthDp = 1280, heightDp = 800)
@Composable
private fun ContentPreview() {
    WatchScreen(ready(), {}, {}, playerSlot = placeholderSlot)
}

@Preview(name = "Content · Large text", widthDp = 320, heightDp = 844, fontScale = 2f)
@Preview(name = "Content · Landscape", widthDp = 840, heightDp = 390)
@Composable
private fun ContentLargeTextPreview() {
    WatchScreen(ready(), {}, {}, playerSlot = placeholderSlot)
}

@Preview(name = "Loading · 390", widthDp = 390, heightDp = 844)
@Preview(name = "Loading · 1280", widthDp = 1280, heightDp = 800)
@Composable
private fun LoadingPreview() {
    WatchScreen(
        WatchUiState(videoId = "bunny", content = WatchContent.Loading),
        {}, {},
        playerSlot = placeholderSlot,
    )
}

@Preview(name = "Error · 390", widthDp = 390, heightDp = 844)
@Composable
private fun ErrorPreview() {
    WatchScreen(
        WatchUiState(videoId = "bunny", content = WatchContent.Failed),
        {}, {},
        playerSlot = placeholderSlot,
    )
}
