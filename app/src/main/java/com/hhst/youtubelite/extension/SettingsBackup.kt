package com.hhst.youtubelite.extension

import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticOutcome
import com.hhst.youtubelite.downloader.core.DownloadPrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class DownloadBackupData(
    val wifiOnly: Boolean? = null,
    val maxConnections: Int? = null,
    val defaultQuality: String? = null,
)

@Serializable
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
    // Write defaults so exports contain the complete settings schema.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
        return json.encodeToString(SettingsBackupData.serializer(), data)
    }

    fun parse(text: String): SettingsBackupData? = runCatching {
        require(text.length <= 512 * 1024) { "settings_backup_too_large" }
        val root = Json.parseToJsonElement(text) as JsonObject
        require(root["schema"]?.strictString() == SCHEMA)
        require(root["version"]?.integerIn(VERSION..VERSION) == true)
        root["preferences"]?.takeUnless { it is JsonNull }?.let { preferences ->
            require(preferences is JsonObject)
            require(preferences.size <= PreferenceKeys.DEFAULTS.size + 32)
            require(preferences.values.all { it.strictBoolean() != null })
        }
        root["hapticStrength"]?.takeUnless { it is JsonNull }?.let { require(it.integerIn(0..100)) }
        root["download"]?.takeUnless { it is JsonNull }?.let { download ->
            require(download is JsonObject)
            download["wifiOnly"]?.takeUnless { it is JsonNull }?.let { require(it.strictBoolean() != null) }
            download["maxConnections"]?.takeUnless { it is JsonNull }?.let {
                require(it.integerIn(com.hhst.youtubelite.downloader.core.DownloadSettings.MIN_CONNECTIONS..
                    com.hhst.youtubelite.downloader.core.DownloadSettings.MAX_CONNECTIONS))
            }
            download["defaultQuality"]?.takeUnless { it is JsonNull }?.let {
                require(it.strictString()?.length in 1..32)
            }
        }
        json.decodeFromJsonElement(SettingsBackupData.serializer(), root)
    }.onFailure { AppLog.event(AppLog.Category.EXTENSION, "settings_import_parse_failed", failure = it) }
        .getOrNull()

    private fun JsonElement.strictString(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun JsonElement.strictBoolean(): Boolean? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content
            ?.takeIf { it == "true" || it == "false" }?.toBoolean()

    private fun JsonElement.integerIn(range: IntRange): Boolean =
        (this as? JsonPrimitive)?.let { it !is JsonNull && !it.isString && it.content.toIntOrNull() in range } == true

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
