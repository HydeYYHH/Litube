package com.hhst.youtubelite.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R

/**
 * The one subscribe control: Watch and Shorts share this pill's geometry,
 * typography, pending/disabled states and 48dp touch target; only the surface
 * colors differ. A pending or disabled button ignores taps so an in-flight
 * write can never be double-submitted.
 */
@Composable
fun SubscribeButton(
    subscribed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pending: Boolean = false,
    onDarkSurface: Boolean = false,
    testTag: String? = null,
    channelId: String? = null,
) {
    val haptic = rememberHapticAction()
    val colors = subscribeColors(subscribed, onDarkSurface)
    val interactive = enabled && !pending
    val subscriptionAction = rememberSubscriptionAction(subscribed, interactive, channelId, onClick)
    val tag = if (testTag != null) Modifier.testTag(testTag) else Modifier
    Box(
        modifier
            .then(tag)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(colors.background)
                .clickable(enabled = interactive, role = Role.Button) { haptic(); subscriptionAction() }
                .heightIn(min = 40.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            AppText(
                if (subscribed) stringResource(R.string.subscribed) else stringResource(R.string.subscribe),
                Modifier.alpha(if (pending) 0f else if (interactive) 1f else 0.55f),
                size = 14, line = 20, weight = FontWeight.Medium, color = colors.foreground, maxLines = 1,
            )
            if (pending) ActionProgress(color = colors.foreground)
        }
    }
}

private class SubscribeColors(val background: Color, val foreground: Color)

@Composable
private fun subscribeColors(subscribed: Boolean, onDarkSurface: Boolean): SubscribeColors = when {
    onDarkSurface && subscribed -> SubscribeColors(Color.White.copy(alpha = 0.25f), Color.White)
    onDarkSurface -> SubscribeColors(Color(0xFF0F0F0F), Color.White)
    subscribed -> SubscribeColors(DesignTokens.Ink.copy(alpha = 0.12f), DesignTokens.Ink)
    else -> SubscribeColors(DesignTokens.Ink, DesignTokens.Background)
}
