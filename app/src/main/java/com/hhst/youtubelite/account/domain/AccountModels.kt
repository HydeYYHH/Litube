package com.hhst.youtubelite.account.domain

/** Rows in the avatar menu. */
enum class AccountMenuAction(val label: String, val tag: String) {
    ViewChannel("View channel", "view"),
    SwitchAccount("Switch account", "switch"),
    Settings("Settings", "settings"),
    About("About Litube", "about");
}
