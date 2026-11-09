package com.hhst.youtubelite.player.engine

import com.hhst.youtubelite.diagnostics.DiagnosticContext

/**
 * Recovery belongs to one explicit play/retry across its source generations; the
 * engine's main thread owns this state and performs all IO and player changes.
 */
internal class PlaybackAttempt(val videoId: String) {
    val diagnosticRoot = DiagnosticContext(videoId = videoId)

    var lightRefreshes = 0
        private set
    var fullRefreshes = 0
        private set
    var backupAttempted = false
        private set
    var transportManifestAttempted = false
        private set

    private val recoveryHistory = ArrayDeque<Map<String, Any?>>()
    val history: List<Map<String, Any?>> get() = recoveryHistory.toList()

    /** Call only after the engine has established this recovery path is eligible. */
    fun tryLightRefresh(): Boolean {
        if (lightRefreshes >= MAX_LIGHT_REFRESHES) return false
        lightRefreshes++
        return true
    }

    fun tryFullRefresh(): Boolean {
        if (fullRefreshes >= MAX_FULL_REFRESHES) return false
        fullRefreshes++
        return true
    }

    /** A failed lookup still consumes the one backup after a full refresh. */
    fun tryBackup(): Boolean {
        if (fullRefreshes == 0 || backupAttempted) return false
        backupAttempted = true
        return true
    }

    fun tryTransportManifest(): Boolean {
        if (transportManifestAttempted) return false
        transportManifestAttempted = true
        return true
    }

    fun recordRecovery(fields: Map<String, Any?>) {
        recoveryHistory.addLast(fields.toMap())
        while (recoveryHistory.size > MAX_HISTORY) recoveryHistory.removeFirst()
    }

    private companion object {
        const val MAX_LIGHT_REFRESHES = 1
        const val MAX_FULL_REFRESHES = 1
        const val MAX_HISTORY = 6
    }
}
