package com.hhst.youtubelite.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hhst.youtubelite.R
import com.hhst.youtubelite.core.WriteFeedback

/** Indeterminate progress includes Material accessibility semantics. */
@Composable
fun ActionProgress(modifier: Modifier = Modifier, color: Color = DesignTokens.Ink) {
    CircularProgressIndicator(modifier.size(18.dp).testTag("write-progress"), color = color, strokeWidth = 2.dp)
}

/** Non-modal status survives menu dismissal and stays visible until every tracked write completes. */
@Composable
fun WriteProgressOverlay(feedback: WriteFeedback) {
    val pending by feedback.pending.collectAsStateWithLifecycle()
    if (pending.isNotEmpty()) Popup(alignment = Alignment.BottomCenter,
        properties = PopupProperties(focusable = false)) {
        Row(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(bottom = 80.dp)
            .background(DesignTokens.Surface, RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp).testTag("background-write-progress")
            .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionProgress()
            AppText(stringResource(R.string.action_in_progress), size = 14, line = 20)
        }
    }
}
