package com.hhst.youtubelite.player.surface

import com.tencent.mmkv.MMKV

/**
 * Persists mini-player width and translation.
 */
class MiniPlayerStore(
    private val kv: MMKV,
) {
    data class State(
        val widthDp: Int,
        val translationXDp: Float,
        val translationYDp: Float,
    )

    fun load(): State = State(
        widthDp = kv.decodeInt(KEY_WIDTH_DP, 0),
        translationXDp = kv.decodeFloat(KEY_TX_DP, 0f),
        translationYDp = kv.decodeFloat(KEY_TY_DP, 0f),
    )

    fun save(widthDp: Int, translationXDp: Float, translationYDp: Float) {
        kv.encode(KEY_WIDTH_DP, widthDp)
        kv.encode(KEY_TX_DP, translationXDp)
        kv.encode(KEY_TY_DP, translationYDp)
    }

    private companion object {
        const val KEY_WIDTH_DP = "mini_player_width_dp"
        const val KEY_TX_DP = "mini_player_translation_x_dp"
        const val KEY_TY_DP = "mini_player_translation_y_dp"
    }
}
