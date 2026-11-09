package com.hhst.youtubelite.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hhst.youtubelite.data.media.MediaSearchSuggestions
import kotlinx.coroutines.delay

/** Null source (fixture builds) yields null so callers keep their deterministic fixture rows. */
class SearchRows(
    val history: List<String>,
    val suggestions: List<String>,
    val onRemove: (String) -> Unit,
    val onSubmit: (String) -> Unit,
)

@Composable
fun rememberSearchRows(source: MediaSearchSuggestions?, query: String): SearchRows? {
    source ?: return null
    val session by com.hhst.youtubelite.data.media.MediaSession.changes.collectAsStateWithLifecycle()
    var refreshTick by remember(source) { mutableIntStateOf(0) }
    var rows by remember(source, query, session, refreshTick) { mutableStateOf(source.localRows(query)) }
    LaunchedEffect(source, query, refreshTick, session) {
        if (query.isBlank()) return@LaunchedEffect
        delay(250)
        rows = source.rows(query)
    }
    return remember(rows, source) {
        SearchRows(rows.history, rows.suggestions,
            onRemove = { entry -> source.remove(entry); refreshTick++ },
            onSubmit = { text -> source.record(text); refreshTick++ })
    }
}
