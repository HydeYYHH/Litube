package com.hhst.youtubelite.watch.presentation

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Entry and an interrupted exit share one clock; reversing never brightens the page. */
internal fun watchPageProgress(entry: Float, collapse: Float): Float =
    entry.coerceIn(0f, 1f) * (1f - collapse.coerceIn(0f, 1f))

/** The opaque picture leads. Labels appear near landing and leave early on collapse. */
internal fun watchPageAlpha(progress: Float): Float =
    ((progress - 0.8f) / 0.2f).coerceIn(0f, 1f)

/** Drawing alone changes: the player slot keeps its final layout throughout navigation. */
internal fun Modifier.watchPageContent(progress: () -> Float): Modifier =
    graphicsLayer { alpha = watchPageAlpha(progress()) }
