package com.hhst.youtubelite.playlist.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.hhst.youtubelite.playlist.data.FixturePlaylistRepository
import com.hhst.youtubelite.playlist.domain.*

private fun previewState() = PlaylistUiState(FixturePlaylistRepository.MOVIES, PlaylistContent.Ready(
    PlaylistPage(FixturePlaylistRepository.summaries().first(), "Watch the open movies made with Blender!", FixturePlaylistRepository.entries())))

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun PlaylistPhonePreview() { PlaylistScreen(previewState(), {}, {}) }

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun PlaylistLandscapePreview() { PlaylistScreen(previewState(), {}, {}) }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun PlaylistMenuPreview() { PlaylistScreen(previewState().copy(overlay = PlaylistOverlay.Menu), {}, {}) }

@Preview(widthDp = 390, heightDp = 844)
@Composable
private fun PlaylistLoadingPreview() { PlaylistScreen(previewState().copy(content = PlaylistContent.Loading), {}, {}) }
