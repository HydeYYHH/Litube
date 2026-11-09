package com.hhst.youtubelite.shorts.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hhst.youtubelite.player.surface.PlayerModalSheet

@Composable
internal fun ShortsDescriptionSheet(
    title: String,
    author: String?,
    description: String,
    onDismiss: () -> Unit,
) {
    PlayerModalSheet(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Text(
                title,
                fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            author?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    Modifier.padding(top = 4.dp),
                    fontSize = 13.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                description,
                Modifier.padding(top = 12.dp).testTag("shorts-description"),
                fontSize = 14.sp, lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
