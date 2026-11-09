package com.hhst.youtubelite.ui.design

import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.core.VerificationRequired

/**
 * The `/sorry` traffic-challenge notice. [VerificationCard] replaces an error
 * state when nothing loaded yet; [VerificationBanner] floats over a working
 * feed so the user can keep browsing what already loaded.
 */
@Composable
fun VerificationCard(
    verification: VerificationRequired,
    onVerify: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AppText(stringResource(R.string.verification_title), size = 20, line = 28, weight = FontWeight.Bold)
        AppText(
            stringResource(R.string.ui_verification_explanation),
            Modifier.padding(top = 8.dp, bottom = 20.dp), color = DesignTokens.Secondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppText(stringResource(R.string.verification_banner_dismiss), Modifier.clip(RoundedCornerShape(999.dp))
                .clickable(role = Role.Button, onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 14.dp),
                weight = FontWeight.Medium, color = DesignTokens.Secondary)
            AppText(stringResource(R.string.verification_banner_action), Modifier.clip(RoundedCornerShape(999.dp)).background(DesignTokens.Surface)
                .clickable(role = Role.Button, onClick = onVerify)
                .padding(horizontal = 16.dp, vertical = 14.dp),
                weight = FontWeight.Medium)
        }
    }
}

@Composable
fun VerificationBanner(
    verification: VerificationRequired,
    onVerify: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DesignTokens.Ghost)
        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            AppText(stringResource(R.string.verification_title), size = 14, line = 20, weight = FontWeight.Medium)
            AppText(stringResource(R.string.ui_existing_content_stays_available), Modifier.padding(top = 2.dp), size = 12, line = 16, color = DesignTokens.Secondary)
        }
        AppText(stringResource(R.string.verification_banner_action), Modifier.clip(RoundedCornerShape(999.dp))
            .clickable(role = Role.Button, onClick = onVerify)
            .padding(horizontal = 12.dp, vertical = 14.dp), weight = FontWeight.Medium)
        AppText(stringResource(R.string.verification_banner_dismiss), Modifier.clip(RoundedCornerShape(999.dp))
            .clickable(role = Role.Button, onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 14.dp), weight = FontWeight.Medium, color = DesignTokens.Secondary)
    }
}
