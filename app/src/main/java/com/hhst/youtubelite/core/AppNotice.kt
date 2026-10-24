package com.hhst.youtubelite.core

/**
 * User-facing outcome of an action/write, emitted by view models and rendered
 * by the host: [SignIn] opens the native sign-in flow, [Message] shows a toast.
 * One shared type replaces the former per-feature duplicates.
 */
sealed interface AppNotice {
    data object SignIn : AppNotice
    data class Message(val text: String) : AppNotice
}
