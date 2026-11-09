package com.hhst.youtubelite.settings.domain

enum class SettingsPage(val argument: String) {
    Home("home"), General("general"), Playback("playback"), Notifications("notifications");

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.argument == value } ?: Home
    }
}
