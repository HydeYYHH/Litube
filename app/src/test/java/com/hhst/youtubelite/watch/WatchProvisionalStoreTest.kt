package com.hhst.youtubelite.watch

import com.hhst.youtubelite.watch.domain.WatchProvisional
import com.hhst.youtubelite.watch.domain.WatchProvisionalStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchProvisionalStoreTest {
    @Test fun consumeRemovesTheEntrySoARepeatVisitStartsClean() {
        WatchProvisionalStore.put(WatchProvisional(videoId = "v1", title = "Card title"))
        assertEquals("Card title", WatchProvisionalStore.consume("v1")?.title)
        assertNull(WatchProvisionalStore.consume("v1"))
    }

    @Test fun rePutReplacesAndTheStoreStaysBounded() {
        repeat(40) { index ->
            WatchProvisionalStore.put(WatchProvisional(videoId = "v$index", title = "t$index"))
        }
        assertNull(WatchProvisionalStore.consume("v0"))
        assertEquals("t39", WatchProvisionalStore.consume("v39")?.title)
        WatchProvisionalStore.put(WatchProvisional(videoId = "v1", title = "latest"))
        assertEquals("latest", WatchProvisionalStore.consume("v1")?.title)
    }
}
