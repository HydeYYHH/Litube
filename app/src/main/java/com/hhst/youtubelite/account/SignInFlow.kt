package com.hhst.youtubelite.account

import android.content.Context
import android.content.Intent

object SignInFlow {
    const val SIGN_IN_URL = "https://m.youtube.com/signin?action_handle_signin=true&hl=en"

    fun open(context: Context) {
        context.startActivity(
            Intent(context, SignInActivity::class.java),
        )
    }
}
