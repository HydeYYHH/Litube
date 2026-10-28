package com.hhst.youtubelite.extension

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
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
    /** Backups from newer app versions are refused instead of half-applied. */
    const val VERSION = 1
    private val gson = Gson()

    fun export(manager: ExtensionManager, downloadPrefs: DownloadPrefs): String {
        val data = SettingsBackupData(
            schema = SCHEMA,
            version = VERSION,
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
        require(json.length <= 512 * 1024) { "settings_backup_too_large" }
        val root = JsonParser.parseString(json).asJsonObject
        require(root["schema"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString == SCHEMA } == true)
        require(root["version"]?.integerIn(VERSION..VERSION) == true)
        root["preferences"]?.takeUnless(JsonElement::isJsonNull)?.let { preferences ->
            require(preferences.isJsonObject)
            require(preferences.asJsonObject.size() <= PreferenceKeys.DEFAULTS.size + 32)
            require(preferences.asJsonObject.entrySet().all { (_, value) ->
                value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
            })
        }
        root["hapticStrength"]?.takeUnless(JsonElement::isJsonNull)?.let { require(it.integerIn(0..100)) }
        root["download"]?.takeUnless(JsonElement::isJsonNull)?.let { download ->
            require(download.isJsonObject)
            download.asJsonObject["wifiOnly"]?.takeUnless(JsonElement::isJsonNull)?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean)
            }
            download.asJsonObject["maxConnections"]?.takeUnless(JsonElement::isJsonNull)?.let {
                require(it.integerIn(com.hhst.youtubelite.downloader.core.DownloadSettings.MIN_CONNECTIONS..
                    com.hhst.youtubelite.downloader.core.DownloadSettings.MAX_CONNECTIONS))
            }
            download.asJsonObject["defaultQuality"]?.takeUnless(JsonElement::isJsonNull)?.let {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.length in 1..32)
            }
        }
        gson.fromJson(root, SettingsBackupData::class.java)
    }.onFailure { AppLog.event(AppLog.Category.EXTENSION, "settings_import_parse_failed", failure = it) }
        .getOrNull()

    private fun JsonElement.integerIn(range: IntRange): Boolean =
        isJsonPrimitive && asJsonPrimitive.isNumber && runCatching {
            asBigDecimal.intValueExact() in range
        }.getOrDefault(false)

    fun apply(data: SettingsBackupData, manager: ExtensionManager, downloadPrefs: DownloadPrefs) {
        val operation = AppLog.operation(AppLog.Category.EXTENSION, "settings_import")
        try {
            require(data.schema == SCHEMA && data.version == VERSION) { "invalid_settings_schema" }
            require(data.hapticStrength == null || data.hapticStrength in 0..100) { "invalid_haptic_strength" }
            data.download?.maxConnections?.let {
                require(it in com.hhst.youtubelite.downloader.core.DownloadSettings.MIN_CONNECTIONS..
                    com.hhst.youtubelite.downloader.core.DownloadSettings.MAX_CONNECTIONS) { "invalid_connection_count" }
            }
            if (data.preferences != null || data.hapticStrength != null)
                manager.importPreferences(data.preferences.orEmpty(), data.hapticStrength)
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
