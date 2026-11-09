package com.hhst.youtubelite.core

import com.hhst.youtubelite.diagnostics.ActionFailureReport

/**
 * User-facing outcome of an action, rendered by the host: [SignIn] opens the
 * native sign-in flow, [Message] a toast, [Failure] a toast with a copyable report.
 */
sealed interface AppNotice {
    data object SignIn : AppNotice
    data class LocalizedMessage(@get:androidx.annotation.StringRes val resource: Int, val arguments: List<Any> = emptyList()) : AppNotice
    data class Message(val text: String) : AppNotice
    data class Failure(val text: String, val report: ActionFailureReport) : AppNotice
}
