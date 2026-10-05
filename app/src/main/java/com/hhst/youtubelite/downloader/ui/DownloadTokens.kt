package com.hhst.youtubelite.downloader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Download-module tokens mapped onto the YouTube Android help's scene set
 * (2026-09-16 reference board). Use these — do not invent a parallel scale.
 */
object DownloadTokens {
    const val PAGE_INSET_DP = 16
    const val ICON_DP = 24
    const val MIN_TOUCH_DP = 48
    const val FILTER_HEIGHT_DP = 40
    const val ROW_ACTION_WIDTH_DP = 40
    const val SHEET_CORNER_DP = 28
    const val TITLE_MAX_LINES = 2
    const val THUMB_ASPECT_W = 16
    const val THUMB_ASPECT_H = 9
    const val CAPSULE_DP = 20
    const val DIVIDER_ALPHA = 0.4f

    val PageInset = PAGE_INSET_DP.dp
    val Icon = ICON_DP.dp
    val MinTouch = MIN_TOUCH_DP.dp
    val FilterHeight = FILTER_HEIGHT_DP.dp
    val RowActionWidth = ROW_ACTION_WIDTH_DP.dp
    val SheetCorner = SHEET_CORNER_DP.dp
    val Capsule = CAPSULE_DP.dp
    val ThumbAspect = THUMB_ASPECT_W.toFloat() / THUMB_ASPECT_H.toFloat()
}
