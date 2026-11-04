package com.hhst.youtubelite.channel.presentation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.hhst.youtubelite.home.domain.AppendState
import kotlinx.coroutines.flow.collect

/** Observe scroll changes outside composition; loading and failure never auto-retry. */
@Composable
internal fun ObserveChannelPagination(
    state: LazyListState,
    canPage: Boolean,
    append: AppendState,
    onLoadMore: () -> Unit,
) {
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state, canPage, append) {
        if (!canPage || append != AppendState.Idle) return@LaunchedEffect
        snapshotFlow {
            val layout = state.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            layout.totalItemsCount > 0 && last >= layout.totalItemsCount - 4
        }.collect { nearEnd -> if (nearEnd) loadMore() }
    }
}
