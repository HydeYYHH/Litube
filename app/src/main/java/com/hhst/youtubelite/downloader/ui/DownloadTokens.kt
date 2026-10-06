package com.hhst.youtubelite.downloader.ui

import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.ui.theme.SettingsTokens

/** Shared spacing, sizing and touch targets for download components. */
object DownloadTokens {
    const val PAGE_INSET_DP = 12
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

    val PageInset = SettingsTokens.PageInset
    val Icon = SettingsTokens.IconSize
    val MinTouch = SettingsTokens.RowHeight
    val FilterHeight = FILTER_HEIGHT_DP.dp
    val RowActionWidth = ROW_ACTION_WIDTH_DP.dp
    val SheetCorner = SHEET_CORNER_DP.dp
    val Capsule = CAPSULE_DP.dp
    val ThumbAspect = THUMB_ASPECT_W.toFloat() / THUMB_ASPECT_H.toFloat()
}
