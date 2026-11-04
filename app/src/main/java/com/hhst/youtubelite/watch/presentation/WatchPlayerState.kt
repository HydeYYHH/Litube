package com.hhst.youtubelite.watch.presentation

import com.hhst.youtubelite.player.PlayerUiState

/** Window handoffs retain geometry while playback and first-frame truth stay live. */
internal fun PlayerUiState.withWatchPresentation(presentation: PlayerUiState?): PlayerUiState =
    if (presentation == null || presentation.videoId != videoId) this
    else copy(visible = presentation.visible, mini = presentation.mini, fullscreen = presentation.fullscreen)
