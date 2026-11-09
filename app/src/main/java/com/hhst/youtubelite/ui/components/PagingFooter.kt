package com.hhst.youtubelite.ui.components

import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.DesignTokens

@Composable
fun PagingFooter(loading: Boolean, failed: Boolean, onLoad: () -> Unit) {
    LaunchedEffect(loading, failed) { if (!loading && !failed) onLoad() }
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("paging-footer"), contentAlignment = Alignment.Center) {
        if (failed) AppText(stringResource(R.string.retry), Modifier.clickable(onClick = onLoad).padding(16.dp).testTag("paging-retry"), size = 14, line = 20)
        else androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), color = DesignTokens.Ink, strokeWidth = 2.dp)
    }
}
