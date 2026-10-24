package com.hhst.youtubelite.ui

import android.app.Activity
import android.widget.Toast
import com.hhst.youtubelite.account.SignInFlow
import com.hhst.youtubelite.core.AppNotice

/** Every screen renders an [AppNotice] the same way: sign-in opens the native flow, messages toast. */
fun Activity.renderNotice(notice: AppNotice) {
    when (notice) {
        AppNotice.SignIn -> SignInFlow.open(this)
        is AppNotice.Message -> Toast.makeText(this, notice.text, Toast.LENGTH_LONG).show()
    }
}
