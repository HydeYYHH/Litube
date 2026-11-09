package com.hhst.youtubelite.ui.components

import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.ui.design.labelResource
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.DesignTokens

enum class MainTab(val label: String) { Home("Home"), Shorts("Shorts"), Subscriptions("Subscriptions"), You("You") }

object MainTabs {
    fun tabs(signedIn: Boolean, hideShorts: Boolean = false) =
        (if (signedIn) MainTab.entries else listOf(MainTab.Home, MainTab.Shorts, MainTab.You))
            .filterNot { hideShorts && it == MainTab.Shorts }

    /**
     * Reuses an existing destination instance instead of stacking a new one on
     * every tab switch. Each destination retains its saved scroll state.
     */
    fun intent(context: Context, tab: MainTab, signedIn: Boolean): Intent? = when (tab) {
        MainTab.Home -> Intent(context, com.hhst.youtubelite.home.HomeActivity::class.java)
            .putExtra(com.hhst.youtubelite.home.HomeActivity.EXTRA_SIGNED_IN, signedIn)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        MainTab.Subscriptions -> com.hhst.youtubelite.subscriptions.SubscriptionsActivity.intent(context, signedIn)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        MainTab.You -> com.hhst.youtubelite.library.YouActivity.intent(context, signedIn)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        MainTab.Shorts -> com.hhst.youtubelite.shorts.ShortsActivity.intent(context)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }?.putExtra(com.hhst.youtubelite.ui.NativeActivity.EXTRA_TAB_NAVIGATION, true)
        ?.putExtra(com.hhst.youtubelite.ui.NativeActivity.EXTRA_TAB_BACKWARD, backwards(context, tab))

    private fun backwards(context: Context, target: MainTab): Boolean {
        var owner = context
        while (owner is android.content.ContextWrapper && owner !is android.app.Activity) owner = owner.baseContext
        val source = when (owner) {
            is com.hhst.youtubelite.home.HomeActivity -> MainTab.Home
            is com.hhst.youtubelite.shorts.ShortsActivity -> MainTab.Shorts
            is com.hhst.youtubelite.subscriptions.SubscriptionsActivity -> MainTab.Subscriptions
            is com.hhst.youtubelite.library.YouActivity -> MainTab.You
            else -> return false
        }
        return target.ordinal < source.ordinal
    }
}

@Composable
fun MainNavigationBar(tag: String, signedIn: Boolean, avatarAsset: String?, selected: MainTab = MainTab.You, onSelect: (MainTab) -> Unit) {
    val haptic = com.hhst.youtubelite.ui.design.rememberHapticAction(com.hhst.youtubelite.core.HapticsController.Event.TAB)
    Column(Modifier.fillMaxWidth().testTag("$tag-navigation")) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(DesignTokens.NavigationDivider))
        Row(Modifier.fillMaxWidth().height(DesignTokens.BottomBarHeight)) {
            MainTabs.tabs(signedIn, com.hhst.youtubelite.ui.design.LocalHideShorts.current).forEach { tab ->
                Column(Modifier.weight(1f).fillMaxHeight()
                    .clickable(role = Role.Tab) { haptic(); onSelect(tab) }
                    .testTag("$tag-nav-${tab.name}").semantics { this.selected = tab == selected },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    if (signedIn && tab == MainTab.You && avatarAsset != null) {
                        com.hhst.youtubelite.ui.design.Artwork(avatarAsset, modifier = Modifier.size(24.dp).clip(CircleShape),)
                    } else AppIcon(when (tab) {
                        MainTab.Home -> AppIcon.HomeOutline
                        MainTab.Shorts -> AppIcon.Shorts
                        MainTab.Subscriptions -> AppIcon.Subscriptions
                        MainTab.You -> AppIcon.You
                    })
                    AppText(stringResource(tab.labelResource), size = 11, line = 14, maxLines = 1)
                }
            }
        }
    }
}
