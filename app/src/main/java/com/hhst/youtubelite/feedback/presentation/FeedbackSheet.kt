package com.hhst.youtubelite.feedback.presentation

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

/** One reason row. The label is both the visible text and the typed request payload. */
data class FeedbackReason(val label: String, val tag: String)

@Composable
fun FeedbackSheet(
    title: String,
    reasons: List<FeedbackReason>,
    onDismiss: () -> Unit,
    onSelect: (FeedbackReason) -> Unit,
) {
    MenuBottomSheet(
        items = reasons.map { MenuSheetItem(null, it.label, it.tag) },
        onDismiss = onDismiss,
        onSelect = { index -> if (index in reasons.indices) onSelect(reasons[index]) },
        tag = "feedback-sheet",
        paneTitle = title,
        header = {
            AppText(
                title,
                Modifier.fillMaxWidth().padding(
                    start = MenuSheetLayout.RowStart.dp,
                    end = MenuSheetLayout.RowStart.dp,
                    bottom = 4.dp,
                ),
                size = 16, line = 22, weight = FontWeight.Medium,
            )
        },
    )
}
