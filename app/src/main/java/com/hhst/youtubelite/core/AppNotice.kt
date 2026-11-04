package com.hhst.youtubelite.core

import com.hhst.youtubelite.diagnostics.ActionFailureReport

/**
 * User-facing outcome of an action/write, emitted by view models and rendered
 * by the host: [SignIn] opens the native sign-in flow, [Message] shows a toast,
 * [Failure] shows a toast with a copyable sanitized report.
 */
sealed interface AppNotice {
    data object SignIn : AppNotice
    data class Message(val text: String) : AppNotice
    data class Failure(val text: String, val report: ActionFailureReport) : AppNotice
}
