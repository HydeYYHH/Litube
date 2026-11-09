package com.hhst.youtubelite.feedback.presentation

import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.R
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.MenuBottomSheet
import com.hhst.youtubelite.ui.design.MenuSheetItem
import com.hhst.youtubelite.ui.design.MenuSheetLayout

data class FeedbackReason(val label: String, val tag: String, @get:androidx.annotation.StringRes val labelResource: Int? = null)

@Composable
fun FeedbackSheet(
    title: String,
    reasons: List<FeedbackReason>,
    onDismiss: () -> Unit,
    onSelect: (FeedbackReason) -> Unit,
) {
    MenuBottomSheet(
        items = reasons.map { MenuSheetItem(null, it.labelResource?.let { id -> stringResource(id) } ?: it.label, it.tag) },
        onDismiss = onDismiss,
        onSelect = { index -> if (index in reasons.indices) onSelect(reasons[index]) },
        tag = "feedback-sheet",
        paneTitle = title,

    )
}
