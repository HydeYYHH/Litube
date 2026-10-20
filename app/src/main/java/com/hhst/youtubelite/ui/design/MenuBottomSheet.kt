package com.hhst.youtubelite.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One action in a bottom sheet. */
data class MenuSheetItem(val icon: AppIcon, val label: String, val tag: String? = null)

/** Layout metrics for action sheets. */
object MenuSheetLayout {
    const val WidthWideShort = 400f
    const val WidthInset = 16f
    const val BottomInset = 24f
    const val Corner = 12f
    const val RowHeight = 48f
    const val RowStart = 22f
    const val RowVertical = 2f
    const val IconGap = 18f
    const val HandleStrip = 13f
    const val HandleWidth = 40f
    const val HandleHeight = 4f

    /** Wide-short windows cap the sheet at 400; every other window keeps a 16 px inset. */
    fun sheetWidth(width: Float, height: Float): Float =
        if (width >= 416f && width > height && (width * 9f >= height * 13f || width >= 931f)) WidthWideShort
        else (width - WidthInset).coerceAtLeast(0f)
}

@Composable
fun MenuBottomSheet(
    items: List<MenuSheetItem>,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "video-menu",
    paneTitle: String = "Video actions",
    sheetWidth: Dp? = null,
    bottomPadding: Dp = MenuSheetLayout.BottomInset.dp,
) {
    var drag by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current.density
    Box(
        modifier.fillMaxSize().background(DesignTokens.MenuScrim).clickable { onDismiss() }
            .semantics { contentDescription = "Close menu" },
    )
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(bottom = bottomPadding)
                .width(sheetWidth ?: MenuSheetLayout.sheetWidth(maxWidth.value, maxHeight.value).dp)
                .offset(y = (drag / density).dp).clip(RoundedCornerShape(MenuSheetLayout.Corner.dp))
                .background(Color.White)
                .testTag(tag).semantics {
                    this.paneTitle = paneTitle
                    customActions = listOf(CustomAccessibilityAction("Close menu") { onDismiss(); true })
                },
        ) {
            Box(
                Modifier.fillMaxWidth().height(MenuSheetLayout.HandleStrip.dp).testTag("menu-handle")
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, amount ->
                                change.consume(); drag = (drag + amount).coerceAtLeast(0f)
                            },
                            onDragCancel = { drag = 0f },
                            onDragEnd = { if (drag > 48 * density) onDismiss(); drag = 0f },
                        )
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier.padding(bottom = 1.dp).size(MenuSheetLayout.HandleWidth.dp, MenuSheetLayout.HandleHeight.dp)
                        .background(DesignTokens.Ink.copy(alpha = 0.15f), CircleShape),
                )
            }
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("menu-actions")) {
                items.forEachIndexed { index, item ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = MenuSheetLayout.RowHeight.dp)
                            .then(if (item.tag != null) Modifier.testTag(item.tag) else Modifier)
                            .clickable(role = Role.Button) { onSelect(index) }
                            .padding(horizontal = MenuSheetLayout.RowStart.dp, vertical = MenuSheetLayout.RowVertical.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(item.icon)
                        Spacer(Modifier.width(MenuSheetLayout.IconGap.dp))
                        AppText(item.label, size = 16, line = 22)
                    }
                }
            }
        }
    }
}
