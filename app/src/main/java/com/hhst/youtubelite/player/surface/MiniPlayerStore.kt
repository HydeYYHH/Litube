package com.hhst.youtubelite.player.surface

import com.hhst.youtubelite.core.PreferenceStore

class MiniPlayerStore(
    private val kv: PreferenceStore,
) {
    @kotlinx.serialization.Serializable
    data class State(
        val widthDp: Int,
        val translationXDp: Float,
        val translationYDp: Float,
    )

    fun load(): State = State(
        widthDp = kv.getInt(KEY_WIDTH_DP, 0),
        translationXDp = kv.getFloat(KEY_TX_DP, 0f),
        translationYDp = kv.getFloat(KEY_TY_DP, 0f),
    )

    fun save(widthDp: Int, translationXDp: Float, translationYDp: Float) {
        kv.putInt(KEY_WIDTH_DP, widthDp)
        kv.putFloat(KEY_TX_DP, translationXDp)
        kv.putFloat(KEY_TY_DP, translationYDp)
    }

    private companion object {
        const val KEY_WIDTH_DP = "mini_player_width_dp"
        const val KEY_TX_DP = "mini_player_translation_x_dp"
        const val KEY_TY_DP = "mini_player_translation_y_dp"
    }
}
