package com.hhst.youtubelite.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

enum class BrowseDestination(val label: String) { Home("Home"), Shorts("Shorts"), You("You") }

@Composable
fun BrowseNavigationBar(tag: String, onSelect: (BrowseDestination) -> Unit) {
    val haptic = com.hhst.youtubelite.ui.design.rememberHapticAction(com.hhst.youtubelite.core.HapticsController.Event.TAB)
    Column(Modifier.fillMaxWidth().testTag("$tag-navigation")) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(DesignTokens.NavigationDivider))
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            BrowseDestination.entries.filterNot { LocalHideShorts.current && it == BrowseDestination.Shorts }.forEach { destination ->
                Column(Modifier.weight(1f).fillMaxHeight()
                    .clickable(role = Role.Tab) { haptic(); onSelect(destination) }
                    .testTag("$tag-nav-${destination.name}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    AppIcon(when (destination) {
                        BrowseDestination.Home -> AppIcon.HomeOutline
                        BrowseDestination.Shorts -> AppIcon.Shorts
                        BrowseDestination.You -> AppIcon.You
                    })
                    AppText(destination.label, size = 11, line = 14, maxLines = 1)
                }
            }
        }
    }
}
