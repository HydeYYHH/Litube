package com.hhst.youtubelite.player.surface

import com.hhst.youtubelite.player.QueueItem
import org.junit.Assert.*
import org.junit.Test

class QueueReorderTest {
    private fun items(vararg ids: String) = ids.map { QueueItem(videoId = it) }

    @Test fun downwardMoveUsesTheNextSurvivingNeighbor() {
        assertEquals(0 to 2, queueMoveAgainst(items("a", "b", "c", "d"), items("b", "c", "a", "d"), "a"))
    }

    @Test fun externalRemovalDoesNotShiftTheDropPastItsNeighbor() {
        assertEquals(0 to 1, queueMoveAgainst(items("a", "c", "d"), items("b", "c", "a", "d"), "a"))
    }

    @Test fun externalInsertionsKeepTheirRelativeOrder() {
        val source = items("a", "x", "b", "c", "d")
        val move = queueMoveAgainst(source, items("b", "c", "a", "d"), "a")!!
        val result = source.toMutableList().apply { add(move.second, removeAt(move.first)) }
        assertEquals(listOf("x", "b", "c", "a", "d"), result.map { it.videoId })
    }

    @Test fun tailDropUsesThePreviousSurvivingNeighbor() {
        assertEquals(0 to 2, queueMoveAgainst(items("a", "b", "c"), items("b", "c", "a"), "a"))
    }

    @Test fun removedDraggedItemCannotBeRecreated() {
        assertNull(queueMoveAgainst(items("b", "c"), items("b", "a", "c"), "a"))
    }
}
