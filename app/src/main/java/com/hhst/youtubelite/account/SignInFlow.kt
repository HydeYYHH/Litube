package com.hhst.youtubelite.account

import android.content.Context
import android.content.Intent
import com.hhst.youtubelite.MainActivity

/**
 * The only entry into the existing Google sign-in boundary. The native screens
 * never draw credential fields; they hand the login URL to the browser host,
 * which already owns the allowlisted WebView navigation.
 */
object SignInFlow {
    const val SIGN_IN_URL = "https://m.youtube.com/signin?action_handle_signin=true&hl=en"

    fun open(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_URL, SIGN_IN_URL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }
}
