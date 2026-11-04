package com.hhst.youtubelite.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import com.hhst.youtubelite.R

/** Framework dialogs must follow the in-app appearance, including a manual override. */
internal fun Context.appDialogContext(darkTheme: Boolean? = AppAppearance.dark): Context {
    val dark = darkTheme ?: (
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    )
    return ContextThemeWrapper(this, if (dark) R.style.Theme_App_Dialog_Dark else R.style.Theme_App_Dialog_Light)
}
