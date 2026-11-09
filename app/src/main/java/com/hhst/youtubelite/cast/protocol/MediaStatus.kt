// Adapted from NewTube (MIT), 5d0f3ff71ae5f48640b617212c97269a6bb0bbfc.
// Copyright (c) 2020-present yuliskov. See assets/licenses/newtube-MIT.txt.
package com.hhst.youtubelite.cast.protocol

import org.json.JSONObject

/**
 * Parsed snapshot of one Cast MEDIA_STATUS entry. The wire format uses **seconds as doubles**;
 * this class converts to **milliseconds at the parse boundary** and nothing above it ever sees
 * seconds.
 */
class MediaStatus private constructor(
    /** `mediaSessionId` - required on every transport command; -1 until the receiver assigns one. */
    val mediaSessionId: Int,
    /** "PLAYING", "PAUSED", "BUFFERING" or "IDLE"; null when the entry carried no state. */
    val playerState: String?,
    /** Playback position in ms; -1 when the entry carried no `currentTime`. */
    val positionMs: Long,
    /** Media duration in ms; -1 when unknown (missing `media.duration`, e.g. pre-buffer). */
    val durationMs: Long,
    /** Why the player is IDLE ("FINISHED", "CANCELLED", "ERROR", "INTERRUPTED"); null otherwise. */
    val idleReason: String?,
) {

    override fun toString(): String = buildString {
        append("MediaStatus{session=").append(mediaSessionId).append(", ").append(playerState)
        if (idleReason != null) append("/").append(idleReason)
        append(", ").append(positionMs).append("/").append(durationMs).append("ms}")
    }

    companion object {

        /**
         * Parse one entry of a MEDIA_STATUS payload (`{"type":"MEDIA_STATUS","status":[{...}]}`).
         * Null when the entry is not a JSON object - the status array is legitimately empty, e.g.
         * after the media session ended or on a rejected LOAD.
         */
        fun parse(status: JSONObject?): MediaStatus? {
            if (status == null) {
                return null
            }

            val mediaSessionId = status.optInt("mediaSessionId", -1)
            val playerState = emptyToNull(status.optString("playerState"))
            val idleReason = emptyToNull(status.optString("idleReason"))

            val positionMs = if (status.has("currentTime")) {
                secondsToMs(status.optDouble("currentTime", 0.0))
            } else {
                -1L
            }

            var durationMs = -1L
            val media = status.optJSONObject("media")
            if (media != null && media.has("duration")) {
                val duration = media.optDouble("duration", -1.0)
                if (duration >= 0) {
                    durationMs = secondsToMs(duration)
                }
            }

            return MediaStatus(mediaSessionId, playerState, positionMs, durationMs, idleReason)
        }

        /** The one seconds-to-ms conversion for the whole Cast v2 stack. */
        internal fun secondsToMs(seconds: Double): Long = Math.round(seconds * 1000.0)

        private fun emptyToNull(value: String?): String? =
            if (value.isNullOrEmpty()) null else value
    }
}
