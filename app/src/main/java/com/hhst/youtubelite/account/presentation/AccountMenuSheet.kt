package com.hhst.youtubelite.account.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.account.domain.AccountMenuAction
import com.hhst.youtubelite.library.domain.AccountProfile
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.DesignTokens
import com.hhst.youtubelite.ui.design.MenuSheetLayout
import com.hhst.youtubelite.ui.design.MenuSheetItem

/** Menu rows below the signed-in account header. */
val accountMenuActions = AccountMenuAction.entries.filter { it != AccountMenuAction.ViewChannel }
val accountMenuItems = accountMenuActions.map { MenuSheetItem(null, it.label, tag = "account-action-${it.tag}") }

@Composable
fun AccountMenuHeader(account: AccountProfile, onOpenChannel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onOpenChannel)
            .testTag("account-menu-header")
            .padding(start = MenuSheetLayout.RowStart.dp, end = MenuSheetLayout.RowStart.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape)) {
            Image(
                painterResource(when (account.avatar) {
                    "dream" -> R.drawable.sample_dream
                    "sintel" -> R.drawable.sample_sintel
                    else -> R.drawable.sample_bunny
                }),
                contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            AppText(account.name, size = 16, line = 22, weight = FontWeight.Medium)
            AppText(account.handle, size = 14, line = 20, color = DesignTokens.Secondary)
        }
    }
}
