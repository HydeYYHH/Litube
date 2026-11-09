package com.hhst.youtubelite.shorts.presentation

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.hhst.youtubelite.comments.presentation.CommentsViewModel

class RecentCommentsViewModel(private val savedState: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private class Panel(val state: SavedStateHandle, var model: CommentsViewModel? = null)
    private val retained = LinkedHashMap<String, Panel>()
    private var sessionGeneration: Long? = savedState[KEY_GENERATION]

    init {
        savedState.remove<Bundle>(KEY_PANELS)?.let { saved ->
            saved.getStringArrayList(KEY_IDS).orEmpty().withIndex().toList().takeLast(CAPACITY).forEach { (index, id) ->
                if (id.isNotBlank() && id.length <= MAX_ID_LENGTH) {
                    val values = saved.getBundle("panel:$index")
                    retained[id] = Panel(SavedStateHandle(values?.keySet()?.associateWith { key ->
                        @Suppress("DEPRECATION")
                        values.get(key)
                    }.orEmpty()))
                }
            }
        }
        savedState.setSavedStateProvider(KEY_PANELS, ::savePanels)
    }

    val models: Map<String, CommentsViewModel>
        get() = retained.mapNotNull { (id, panel) -> panel.model?.let { id to it } }.toMap()

    fun getOrCreate(id: String, create: (SavedStateHandle) -> CommentsViewModel): CommentsViewModel {
        val panel = retained.remove(id) ?: Panel(SavedStateHandle())
        retained[id] = panel
        while (retained.size > CAPACITY) {
            val eldest = retained.entries.first()
            retained.remove(eldest.key)
            eldest.value.model?.dispose()
        }
        val model = panel.model ?: create(panel.state).also { panel.model = it }
        model.ensureLoaded()
        return model
    }

    fun updateSession(generation: Long) {
        val previous = sessionGeneration
        sessionGeneration = generation
        savedState[KEY_GENERATION] = generation
        if (previous != null && previous != generation) {
            retained.values.forEach { panel ->
                if (panel.model == null) panel.state.keys().forEach { panel.state.remove<Any>(it) }
                else panel.model?.refreshSession()
            }
        }
    }

    internal fun savePanels(): Bundle = Bundle().apply {
        val entries = retained.entries.toList()
        putStringArrayList(KEY_IDS, ArrayList(entries.map { it.key }))
        entries.forEachIndexed { index, (_, panel) ->
            putBundle("panel:$index", Bundle().apply {
                panel.state.keys().forEach { key ->
                    when (val value = panel.state.get<Any?>(key)) {
                        is String -> putString(key, value.take(MAX_TEXT_LENGTH))
                        is Int -> putInt(key, value.coerceIn(0, MAX_RESTORE_COUNT))
                        is Boolean -> putBoolean(key, value)
                        is ArrayList<*> -> putStringArrayList(key, ArrayList(value.filterIsInstance<String>()
                            .take(MAX_EXPANDED).map { it.take(MAX_ID_LENGTH) }))
                    }
                }
            })
        }
    }

    override fun onCleared() {
        retained.values.forEach { it.model?.dispose() }
        retained.clear()
        savedState.clearSavedStateProvider(KEY_PANELS)
    }

    private companion object {
        const val CAPACITY = 4
        const val KEY_PANELS = "commentPanels"
        const val KEY_IDS = "ids"
        const val KEY_GENERATION = "commentSessionGeneration"
        const val MAX_TEXT_LENGTH = 4096
        const val MAX_ID_LENGTH = 256
        const val MAX_EXPANDED = 64
        const val MAX_RESTORE_COUNT = 500
    }
}
