package com.hhst.youtubelite.extension

import com.google.gson.Gson
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticOutcome
import com.hhst.youtubelite.downloader.core.DownloadPrefs

data class DownloadBackupData(
    val wifiOnly: Boolean? = null,
    val maxConnections: Int? = null,
    val defaultQuality: String? = null,
)

data class SettingsBackupData(
    val schema: String? = null,
    val version: Int = 1,
    val preferences: Map<String, Boolean>? = null,
    val hapticStrength: Int? = null,
    val download: DownloadBackupData? = null,
)

object SettingsBackup {
    const val SCHEMA = "litube-settings"
    private val gson = Gson()

    fun export(manager: ExtensionManager, downloadPrefs: DownloadPrefs): String {
        val data = SettingsBackupData(
            schema = SCHEMA,
            version = 1,
            preferences = manager.allPreferences(),
            hapticStrength = manager.hapticStrength(),
            download = DownloadBackupData(
                wifiOnly = downloadPrefs.wifiOnly(),
                maxConnections = downloadPrefs.maxConnections(),
                defaultQuality = downloadPrefs.defaultQuality(),
            ),
        )
        return gson.toJson(data)
    }

    fun parse(json: String): SettingsBackupData? = runCatching {
        gson.fromJson(json, SettingsBackupData::class.java)
    }.onFailure { AppLog.event(AppLog.Category.EXTENSION, "settings_import_parse_failed", failure = it) }
        .getOrNull()?.takeIf { it.schema == SCHEMA }

    fun apply(data: SettingsBackupData, manager: ExtensionManager, downloadPrefs: DownloadPrefs) {
        val operation = AppLog.operation(AppLog.Category.EXTENSION, "settings_import")
        try {
            data.preferences?.let { manager.importPreferences(it, data.hapticStrength) }
            data.download?.let { download ->
                download.wifiOnly?.let { downloadPrefs.setWifiOnly(it) }
                download.maxConnections?.let { downloadPrefs.setMaxConnections(it) }
                download.defaultQuality?.let { downloadPrefs.setDefaultQuality(it) }
            }
            operation.finish(DiagnosticOutcome.SUCCESS, fields = mapOf(
                "known_preferences" to data.preferences?.keys?.count { it in PreferenceKeys.DEFAULTS }))
        } catch (failure: Throwable) {
            operation.finish(DiagnosticOutcome.FAILURE, "apply_failed", failure)
            throw failure
        }
    }
}
