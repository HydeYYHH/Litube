package com.hhst.youtubelite.ui.design

import androidx.compose.ui.res.stringResource
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
import com.hhst.youtubelite.ui.components.watchSheetHost
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
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

data class MenuSheetItem(val icon: AppIcon?, val label: String, val tag: String? = null, val contentStart: Dp? = null, val iconResource: Int? = null)

object MenuSheetLayout {
    const val WidthWideShort = 400f
    const val BottomInset = 24f
    const val Corner = 12f
    const val RowHeight = 48f
    const val RowStart = 22f
    const val RowVertical = 2f
    const val IconGap = 18f
    const val HandleStrip = 13f
    const val HandleWidth = 40f
    const val HandleHeight = 4f

    /** Compact sheets span the window; landscape sheets keep a readable width. */
    fun sheetWidth(width: Float, height: Float): Float =
        if (width >= 416f && width > height && (width * 9f >= height * 13f || width >= 931f)) WidthWideShort
        else width.coerceAtLeast(0f)
}

@Composable
fun MenuBottomSheet(
    items: List<MenuSheetItem>,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "video-menu",
    paneTitle: String = stringResource(R.string.ui_video_actions),
    sheetWidth: Dp? = null,
    bottomPadding: Dp = MenuSheetLayout.BottomInset.dp,
    rowStart: Dp = MenuSheetLayout.RowStart.dp,
    iconGap: Dp = MenuSheetLayout.IconGap.dp,
    header: (@Composable () -> Unit)? = null,
    dismissEnabled: Boolean = true,
    actionsEnabled: Boolean = true,
    /** Player panels fill the space below playback; contextual action menus can opt out. */
    fillWatchHeight: Boolean = true,
) {
    val density = LocalDensity.current.density
    val closeMenuLabel = stringResource(R.string.a11y_close_menu)
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var drag by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var finger by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val threshold = remember { GestureThreshold() }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var closing by remember { mutableStateOf(false) }
    val haptics = org.koin.compose.koinInject<com.hhst.youtubelite.core.HapticsController>()
    val motion = LocalMotionScale.current

    LaunchedEffect(Unit) {
        if (motion > 0f) progress.animateTo(1f, tween(motionDuration(MenuMotion.enterMs, motion), easing = FastOutSlowInEasing))
        else progress.snapTo(1f)
    }
    fun finish() {
        if (closing || !dismissEnabled) return
        closing = true
        settleJob?.cancel()
        scope.launch {
            if (motion > 0f) progress.animateTo(0f, tween(motionDuration((MenuMotion.exitMs * progress.value).toInt(), motion), easing = androidx.compose.animation.core.FastOutLinearInEasing))
            else progress.snapTo(0f)
            onDismiss()
        }
    }
    fun settle() {
        settleJob?.cancel()
        settleJob = scope.launch {
            if (motion > 0f) animate(drag, 0f, animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)) { value, _ -> drag = value }
            else drag = 0f
        }
    }
    BackHandler { finish() }
    Box(
        modifier.fillMaxSize().graphicsLayer { alpha = progress.value }
            .background(DesignTokens.MenuScrim).clickable(enabled = dismissEnabled) { finish() }
            .appDescription(R.string.a11y_close_menu),
    )
    val watchHost = LocalContext.current.watchSheetHost()
    var containerBottom by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val localDensity = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { containerBottom = it.boundsInWindow().bottom },
        contentAlignment = Alignment.BottomCenter) {
        val playerBottom = watchHost?.sheetPlayerBottomPx ?: 0
        val watchHeight = if (playerBottom > 0 && containerBottom > playerBottom)
            with(localDensity) { (containerBottom - playerBottom).toDp() } else null
        Column(
            Modifier.heightIn(max = watchHeight?.coerceAtMost(maxHeight) ?: maxHeight)
                .then(if (fillWatchHeight && watchHeight != null) Modifier.height(watchHeight) else Modifier)
                .padding(bottom = bottomPadding)
                .width(sheetWidth ?: MenuSheetLayout.sheetWidth(maxWidth.value, maxHeight.value).dp)
                .graphicsLayer {
                    translationY = (1f - progress.value) * (size.height + bottomPadding.toPx()) + drag
                }.clip(RoundedCornerShape(MenuSheetLayout.Corner.dp))
                .background(DesignTokens.Panel)
                .testTag(tag).semantics {
                    this.paneTitle = paneTitle
                    customActions = listOf(CustomAccessibilityAction(closeMenuLabel) { if (dismissEnabled) { finish(); true } else false })
                },
        ) {
            Box(
                Modifier.fillMaxWidth().height(MenuSheetLayout.HandleStrip.dp).testTag("menu-handle")
                    .pointerInput(closing, dismissEnabled) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                settleJob?.cancel()
                                finger = GestureResistance.fingerDistance(drag, density)
                                threshold.reset()
                            },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                if (!closing && dismissEnabled) {
                                    finger = (finger + amount).coerceAtLeast(0f)
                                    drag = GestureResistance.distance(finger, density)
                                    if (threshold.crossed(finger, MenuMotion.dismissDragPx * density))
                                        haptics.perform(com.hhst.youtubelite.core.HapticsController.Event.GESTURE_THRESHOLD)
                                }
                            },
                            onDragCancel = {
                                if (!closing) settle()
                            },
                            onDragEnd = {
                                if (finger >= MenuMotion.dismissDragPx * density) {
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
                            .clickable(enabled = !closing && actionsEnabled, role = Role.Button) { haptics.perform(com.hhst.youtubelite.core.HapticsController.Event.SELECTION); onSelect(index) }
                            .padding(horizontal = item.contentStart ?: rowStart, vertical = MenuSheetLayout.RowVertical.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.icon != null || item.iconResource != null) {
                            if (item.icon != null) AppIcon(item.icon)
                            else androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(requireNotNull(item.iconResource)),
                                contentDescription = null, modifier = Modifier.size(24.dp), tint = DesignTokens.Ink)
                            Spacer(Modifier.width(iconGap))
                        }
                        AppText(item.label, size = 16, line = 22)
                    }
                }
            }
        }
    }
}

object MenuMotion {
    const val enterMs = 180
    const val exitMs = 120
    const val dismissDragPx = 80f
}
