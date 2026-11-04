package com.hhst.youtubelite.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.hhst.youtubelite.core.HapticsController
import org.koin.core.context.GlobalContext

@Composable
internal fun rememberHapticAction(event: HapticsController.Event = HapticsController.Event.SELECTION): () -> Unit {
    val controller = remember { GlobalContext.getOrNull()?.getOrNull<HapticsController>() }
    return remember(controller, event) { { controller?.perform(event); Unit } }
}

/** Native long presses and text handles obey the same strength/off setting as app gestures. */
@Composable
internal fun AppHapticFeedback(content: @Composable () -> Unit) {
    val controller = remember { GlobalContext.getOrNull()?.getOrNull<HapticsController>() }
    val platform = LocalHapticFeedback.current
    val feedback = remember(controller, platform) {
        if (controller == null) platform else object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                controller.perform(if (hapticFeedbackType == HapticFeedbackType.LongPress)
                    HapticsController.Event.HOLD else HapticsController.Event.SELECTION)
            }
        }
    }
    CompositionLocalProvider(LocalHapticFeedback provides feedback, content = content)
}
