package com.hhst.youtubelite.account.presentation

import com.hhst.youtubelite.ui.design.appPaneTitle
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.R
import com.hhst.youtubelite.account.domain.AccountItem
import com.hhst.youtubelite.account.domain.AccountMenuAction
import com.hhst.youtubelite.library.domain.AccountProfile
import com.hhst.youtubelite.ui.design.AppIcon
import com.hhst.youtubelite.ui.design.AppText
import com.hhst.youtubelite.ui.design.DesignTokens
import com.hhst.youtubelite.ui.design.MenuSheetLayout
import com.hhst.youtubelite.ui.design.MenuSheetItem
import com.hhst.youtubelite.ui.design.appDescription

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
            com.hhst.youtubelite.ui.design.Artwork(account.avatar, modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            AppText(account.name, size = 16, line = 22, weight = FontWeight.Medium)
            AppText(account.handle, size = 14, line = 20, color = DesignTokens.Secondary)
        }
    }
}

@Composable
fun AccountSwitchSheet(
    accounts: List<AccountItem>,
    onSelect: (String) -> Unit,
    onAddAccount: () -> Unit,
    onDismiss: () -> Unit,
    deviceSelected: Boolean = false,
    onUseDevice: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(DesignTokens.MenuScrim).clickable { onDismiss() }
        .appDescription(R.string.a11y_close_menu))
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.padding(bottom = MenuSheetLayout.BottomInset.dp)
                .width(MenuSheetLayout.sheetWidth(maxWidth.value, maxHeight.value).dp)
                .heightIn(max = (maxHeight - MenuSheetLayout.BottomInset.dp - 16.dp).coerceAtLeast(0.dp))
                .clip(RoundedCornerShape(MenuSheetLayout.Corner.dp))
                .background(DesignTokens.Panel)
                .verticalScroll(rememberScrollState())
                .testTag("account-switch")
                .appPaneTitle(R.string.ui_switch_account),
        ) {
            AppText(stringResource(R.string.ui_switch_account), size = 16, line = 22, weight = FontWeight.Medium,
                modifier = Modifier.padding(start = MenuSheetLayout.RowStart.dp, top = 16.dp, bottom = 8.dp))
            Row(
                Modifier.fillMaxWidth().heightIn(min = MenuSheetLayout.RowHeight.dp)
                    .clickable(enabled = !deviceSelected, role = Role.Button, onClick = onUseDevice)
                    .testTag("account-switch-device")
                    .padding(start = MenuSheetLayout.RowStart.dp, end = MenuSheetLayout.RowStart.dp,
                        top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(DesignTokens.StateLayer),
                    contentAlignment = Alignment.Center) {
                    AppIcon(AppIcon.Device, color = DesignTokens.Ink, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    AppText(stringResource(R.string.ui_on_this_device), size = 14, line = 20, weight = FontWeight.Medium, maxLines = 1)
                    AppText(stringResource(R.string.ui_browse_anonymously_the_account_stays_signed_in), size = 12, line = 16,
                        color = DesignTokens.Secondary, maxLines = 1)
                }
                if (deviceSelected) {
                    AppIcon(AppIcon.Check, color = DesignTokens.Secondary, modifier = Modifier.size(20.dp))
                }
            }
            accounts.forEach { account ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = MenuSheetLayout.RowHeight.dp)
                        .clickable(enabled = !account.selected, role = Role.Button) { onSelect(account.id) }
                        .testTag("account-switch-${account.id}")
                        .padding(start = MenuSheetLayout.RowStart.dp, end = MenuSheetLayout.RowStart.dp,
                            top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(32.dp).clip(CircleShape)) {
                        com.hhst.youtubelite.ui.design.Artwork(account.photo, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        AppText(account.name, size = 14, line = 20, weight = FontWeight.Medium, maxLines = 1)
                        if (account.email.isNotBlank()) {
                            AppText(account.email, size = 12, line = 16, color = DesignTokens.Secondary, maxLines = 1)
                        }
                    }
                    if (account.selected) {
                        AppIcon(AppIcon.Check, color = DesignTokens.Secondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = MenuSheetLayout.RowHeight.dp)
                    .clickable(role = Role.Button, onClick = onAddAccount)
                    .testTag("account-switch-add")
                    .padding(start = MenuSheetLayout.RowStart.dp, end = MenuSheetLayout.RowStart.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(AppIcon.Add, color = DesignTokens.Ink, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(MenuSheetLayout.IconGap.dp))
                AppText(stringResource(R.string.ui_add_account), size = 14, line = 20)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
