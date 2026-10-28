package com.hhst.youtubelite.ui.design

import androidx.compose.animation.core.Animatable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import com.hhst.youtubelite.R

/** One action in a bottom sheet. */
data class MenuSheetItem(val icon: AppIcon?, val label: String, val tag: String? = null, val contentStart: Dp? = null)

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

/** Action sheet with a fading scrim and a draggable handle. */
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
    rowStart: Dp = MenuSheetLayout.RowStart.dp,
    iconGap: Dp = MenuSheetLayout.IconGap.dp,
    header: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var drag by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var closing by remember { mutableStateOf(false) }
    val haptics = org.koin.compose.koinInject<com.hhst.youtubelite.core.HapticsController>()
    val motion = LocalMotionScale.current

    LaunchedEffect(Unit) {
        if (motion > 0f) progress.animateTo(1f, tween(motionDuration(MenuMotion.enterMs, motion), easing = FastOutSlowInEasing))
        else progress.snapTo(1f)
    }
    fun finish() {
        if (closing) return
        closing = true
        settleJob?.cancel()
        scope.launch {
            if (motion > 0f) progress.animateTo(0f, tween(motionDuration(MenuMotion.exitMs, motion), easing = FastOutSlowInEasing))
            else progress.snapTo(0f)
            onDismiss()
        }
    }
    fun settle() {
        settleJob?.cancel()
        settleJob = scope.launch {
            if (motion > 0f) animate(drag, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ -> drag = value }
            else drag = 0f
        }
    }
    BackHandler { finish() }
    Box(
        modifier.fillMaxSize().graphicsLayer { alpha = progress.value }
            .background(DesignTokens.MenuScrim).clickable { finish() }
            .appDescription(R.string.a11y_close_menu),
    )
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.heightIn(max = maxHeight)
                .padding(bottom = bottomPadding)
                .width(sheetWidth ?: MenuSheetLayout.sheetWidth(maxWidth.value, maxHeight.value).dp)
                .graphicsLayer {
                    translationY = (1f - progress.value) * (size.height + bottomPadding.toPx()) + drag
                }.clip(RoundedCornerShape(MenuSheetLayout.Corner.dp))
                .background(DesignTokens.Background)
                .testTag(tag).semantics {
                    this.paneTitle = paneTitle
                    customActions = listOf(CustomAccessibilityAction("Close menu") { finish(); true })
                },
        ) {
            Box(
                Modifier.fillMaxWidth().height(MenuSheetLayout.HandleStrip.dp).testTag("menu-handle")
                    .pointerInput(closing) {
                        detectVerticalDragGestures(
                            onDragStart = { settleJob?.cancel() },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                if (!closing) drag = (drag + amount).coerceAtLeast(0f)
                            },
                            onDragCancel = {
                                if (!closing) settle()
                            },
                            onDragEnd = {
                                if (drag > MenuMotion.dismissDragPx * density) {
                                    haptics.perform(com.hhst.youtubelite.core.HapticsController.Event.SELECTION)
                                    finish()
                                } else if (!closing) settle()
                            },
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
                header?.invoke()
                items.forEachIndexed { index, item ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = MenuSheetLayout.RowHeight.dp)
                            .then(if (item.tag != null) Modifier.testTag(item.tag) else Modifier)
                            .clickable(enabled = !closing, role = Role.Button) { onSelect(index) }
                            .padding(horizontal = item.contentStart ?: rowStart, vertical = MenuSheetLayout.RowVertical.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.icon != null) {
                            AppIcon(item.icon)
                            Spacer(Modifier.width(iconGap))
                        }
                        AppText(item.label, size = 16, line = 22)
                    }
                }
            }
        }
    }
}

/** Sheet transition durations and drag threshold. */
object MenuMotion {
    const val enterMs = 200
    const val exitMs = 160
    const val dismissDragPx = 48f
}
