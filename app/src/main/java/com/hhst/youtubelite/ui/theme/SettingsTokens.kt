package com.hhst.youtubelite.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared metrics for settings labels, action rows and native page chrome. */
object SettingsTokens {
    const val BodySize = 16
    const val BodyLine = 22
    const val DetailSize = 12
    const val DetailLine = 16
    const val TitleSize = 20
    const val TitleLine = 28
    val RowHeight = 48.dp
    val IconSize = 24.dp
    val IconSlot = 48.dp
    val LabelGap = 14.dp
    val PageInset = 12.dp
    val GroupGap = 8.dp
    val RowPadding = 8.dp
    val Indent = 12.dp
}

@Composable
fun settingsAppBarHeight() = with(LocalDensity.current) {
    maxOf(SettingsTokens.RowHeight, SettingsTokens.TitleLine.sp.toDp() + 20.dp)
}
