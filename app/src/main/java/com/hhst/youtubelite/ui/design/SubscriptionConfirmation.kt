package com.hhst.youtubelite.ui.design

import com.hhst.youtubelite.ui.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.hhst.youtubelite.R

/** All subscription surfaces confirm removal before dispatching a server write. */
@Composable
internal fun rememberSubscriptionAction(
    subscribed: Boolean,
    enabled: Boolean,
    confirmationKey: Any? = null,
    onClick: () -> Unit,
): () -> Unit {
    var confirming by remember(confirmationKey, subscribed, enabled) { mutableStateOf(false) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { AppText(stringResource(R.string.unsubscribe_title), size = 20, line = 28) },
            text = { AppText(stringResource(R.string.unsubscribe_message)) },
            containerColor = DesignTokens.Panel,
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    if (enabled && subscribed) onClick()
                }, modifier = Modifier.testTag("unsubscribe-confirm")) {
                    AppText(stringResource(R.string.unsubscribe), color = DesignTokens.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }, modifier = Modifier.testTag("unsubscribe-cancel")) {
                    AppText(stringResource(R.string.cancel))
                }
            },
            modifier = Modifier.testTag("unsubscribe-dialog"),
        )
    }
    return {
        if (enabled) {
            if (subscribed) confirming = true else onClick()
        }
    }
}
