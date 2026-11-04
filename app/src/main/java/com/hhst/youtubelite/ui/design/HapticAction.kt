package com.hhst.youtubelite.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.hhst.youtubelite.core.HapticsController
import org.koin.core.context.GlobalContext

@Composable
internal fun rememberHapticAction(event: HapticsController.Event = HapticsController.Event.SELECTION): () -> Unit {
    val controller = remember { GlobalContext.getOrNull()?.getOrNull<HapticsController>() }
    return remember(controller, event) { { controller?.perform(event); Unit } }
}
