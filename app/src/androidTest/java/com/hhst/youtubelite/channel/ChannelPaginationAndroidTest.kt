package com.hhst.youtubelite.channel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.hhst.youtubelite.channel.presentation.ObserveChannelPagination
import com.hhst.youtubelite.home.domain.AppendState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChannelPaginationAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun scrollingTriggersTheTailWithoutRetryingLoadingOrFailedPages() {
        var append by mutableStateOf<AppendState>(AppendState.Idle)
        var canPage by mutableStateOf(true)
        var requests = 0
        compose.setContent {
            val listState = rememberLazyListState()
            ObserveChannelPagination(listState, canPage, append) {
                requests++
                append = AppendState.Loading
            }
            LazyColumn(Modifier.size(200.dp).testTag("pagination-list"), state = listState) {
                items(50) { Box(Modifier.height(48.dp)) }
            }
        }
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithTag("pagination-list").performScrollToIndex(46)
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithTag("pagination-list").performScrollToIndex(48)
        compose.runOnIdle { assertEquals(1, requests); append = AppendState.Failed }
        compose.onNodeWithTag("pagination-list").performScrollToIndex(0)
        compose.onNodeWithTag("pagination-list").performScrollToIndex(46)
        compose.runOnIdle { assertEquals(1, requests); append = AppendState.Idle }
        compose.runOnIdle { assertEquals(2, requests); canPage = false; append = AppendState.Idle }
        compose.runOnIdle { assertEquals(2, requests) }
    }
}
