package com.hhst.youtubelite.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRowsMergeTest {
    @Test fun blankQueryShowsOnlyHistory() {
        val rows = mergeSearchRows(listOf("a", "b"), emptyList(), "  ")
        assertEquals(SearchRowSet(listOf("a", "b"), emptyList()), rows)
    }

    @Test fun matchingHistoryLeadsAndServerRowsFollowWithoutDuplicates() {
        val rows = mergeSearchRows(
            listOf("open movie", "open source", "other"),
            listOf("open movie 2026", "Open Movie", "open movie trailer"),
            "open",
        )
        assertEquals(listOf("open movie", "open source"), rows.history)
        assertEquals(listOf("open movie 2026", "open movie trailer"), rows.suggestions)
    }

    @Test fun historyMatchesCapAtThreeAndTotalCapsAtTen() {
        val history = (1..6).map { "query $it" }
        val remote = (1..10).map { "query suggestion $it" }
        val rows = mergeSearchRows(history, remote, "query")
        assertEquals(3, rows.history.size)
        assertEquals(7, rows.suggestions.size)
        assertEquals(10, rows.history.size + rows.suggestions.size)
    }

    @Test fun noMatchingHistoryPassesServerRowsThrough() {
        val rows = mergeSearchRows(listOf("unrelated"), listOf("x one", "x two"), "x")
        assertEquals(emptyList<String>(), rows.history)
        assertEquals(listOf("x one", "x two"), rows.suggestions)
    }
}
