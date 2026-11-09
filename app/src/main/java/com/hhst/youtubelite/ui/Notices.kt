package com.hhst.youtubelite.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import com.hhst.youtubelite.R
import com.hhst.youtubelite.account.SignInFlow
import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.ui.theme.appDialogContext

fun Activity.renderNotice(notice: AppNotice) {
    when (notice) {
        AppNotice.SignIn -> SignInFlow.open(this)
        is AppNotice.LocalizedMessage -> showMessage(getString(notice.resource, *notice.arguments.toTypedArray()))
        is AppNotice.Message -> showMessage(noticeText(notice.text))
        is AppNotice.Failure -> showFailureReport(notice)
    }
}

private fun Activity.showFailureReport(notice: AppNotice.Failure) {
    showMessage(noticeText(notice.text, failure = true))
    AlertDialog.Builder(appDialogContext())
        .setMessage(notice.report.text)
        .setPositiveButton(R.string.copy_all) { target, _ ->
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                ClipData.newPlainText("report", notice.report.text))
            showMessage(getString(R.string.copy_all))
            target.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
