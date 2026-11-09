package com.hhst.youtubelite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlinx.serialization.Serializable

class ContentSnapshotsTest {
    @get:Rule val tmp = TemporaryFolder()
    @Serializable
    private data class Row(val id: String, val title: String = "")

    private fun store(clock: () -> Long = System::currentTimeMillis): Pair<ContentSnapshots, File> {
        val dir = tmp.newFolder()
        return ContentSnapshots(dir, clock) to dir
    }

    @Test fun aSnapshotRoundTripsForItsOwnAccountAndQuery() {
        val (snapshots, _) = store()
        snapshots.write("home", "acct-1", "All", listOf(Row("v1", "First")), Row::class.java)
        assertEquals(listOf(Row("v1", "First")), snapshots.read("home", "acct-1", "All", Row::class.java))
    }

    @Test fun privateRowsStayScopedToTheCapturingAccount() {
        val (snapshots, _) = store()
        snapshots.write("home", "acct-1", "All", listOf(Row("private-1")), Row::class.java)
        assertNull(snapshots.read("home", "acct-2", "All", Row::class.java))
        assertEquals(listOf(Row("private-1")), snapshots.read("home", "acct-1", "All", Row::class.java))
    }

    @Test fun aSnapshotForAnotherQueryDoesNotAnswer() {
        val (snapshots, _) = store()
        snapshots.write("home", "acct-1", "Music", listOf(Row("m1")), Row::class.java)
        assertNull(snapshots.read("home", "acct-1", "All", Row::class.java))
    }

    @Test fun staleAndCorruptSnapshotsAreDiscarded() {
        var now = 0L
        val (snapshots, root) = store({ now })
        snapshots.write("home", "acct-1", "All", listOf(Row("old")), Row::class.java)
        now = 73 * 60 * 60 * 1000L
        assertNull(snapshots.read("home", "acct-1", "All", Row::class.java))
        snapshots.write("home", "acct-1", "All", listOf(Row("fresh")), Row::class.java)
        File(root, "home").listFiles()!!.single { it.name.endsWith(".json") }.writeText("{not json")
        assertNull(snapshots.read("home", "acct-1", "All", Row::class.java))
    }

    @Test fun rowsAreCappedAndNoTempFilesSurviveAWrite() {
        val (snapshots, root) = store()
        snapshots.write("home", "acct-1", "All", (1..300).map { Row("v$it") }, Row::class.java)
        val stored = snapshots.read("home", "acct-1", "All", Row::class.java)!!
        assertEquals(120, stored.size)
        assertEquals("v1", stored.first().id)
        assertTrue("no torn temp file may survive", root.walkTopDown().none { it.extension == "tmp" })
    }

    @Test fun theSnapshotSetStaysBoundedPerKind() {
        val (snapshots, root) = store()
        for (index in 0 until 60) snapshots.write("home", "acct-1", "query-$index", listOf(Row("v$index")), Row::class.java)
        val kept = File(root, "home").listFiles()!!.filter { it.extension == "json" }
        assertTrue("snapshot files must stay bounded, got ${kept.size}", kept.size <= 48)
    }

    @Test fun concurrentWritersOfOneKeyNeverLeaveATornSnapshot() {
        val (snapshots, root) = store()
        val writers = (1..8).map { index -> Thread {
            repeat(40) { round -> snapshots.write("home", "acct-1", "All", listOf(Row("w$index-$round")), Row::class.java) }
        } }
        writers.forEach(Thread::start)
        writers.forEach { it.join(30_000) }
        val read = snapshots.read("home", "acct-1", "All", Row::class.java)
        assertTrue("a concurrent same-key write must leave one readable snapshot", read != null && read.size == 1)
        assertTrue("no temp may survive concurrent writes", root.walkTopDown().none { it.extension == "tmp" })
    }

    @Test fun leftoverTempsDoNotCountTowardTheKindCap() {
        val (snapshots, root) = store()
        snapshots.write("home", "acct-1", "All", listOf(Row("v1")), Row::class.java)
        File(File(root, "home"), "acct-1-deadbeef.123.tmp").writeText("torn")
        for (index in 0 until 60) snapshots.write("home", "acct-1", "query-$index", listOf(Row("v$index")), Row::class.java)
        val kept = File(root, "home").listFiles()!!.filter { it.extension == "json" }
        assertTrue("temps must not evict live snapshots: ${kept.size}", kept.size <= 48)
        assertNull(snapshots.read("home", "acct-1", "nonexistent", Row::class.java))
    }

    @Test fun futureCapturesAndOversizedFilesAreDiscarded() {
        var now = 10_000L
        val (snapshots, root) = store { now }
        snapshots.write("home", "account", "All", listOf(Row("future")), Row::class.java)
        now = 9_000L
        assertNull(snapshots.read("home", "account", "All", Row::class.java))
        snapshots.write("home", "account", "All", listOf(Row("fresh")), Row::class.java)
        File(root, "home").listFiles()!!.single { it.extension == "json" }
            .writeText("x".repeat(2 * 1024 * 1024 + 1))
        assertNull(snapshots.read("home", "account", "All", Row::class.java))
    }

    @Test fun sanitizedAccountNamesCannotCollideAndEmptyResultsClearTheSnapshot() {
        val (snapshots, _) = store()
        snapshots.write("home", "account/a", "All", listOf(Row("private")), Row::class.java)
        assertNull(snapshots.read("home", "account?a", "All", Row::class.java))
        snapshots.clear(account = "account?a")
        assertEquals("private", snapshots.read("home", "account/a", "All", Row::class.java)!!.single().id)
        snapshots.write("home", "account/a", "All", emptyList<Row>(), Row::class.java)
        assertNull(snapshots.read("home", "account/a", "All", Row::class.java))
    }

    @Test fun anInterruptedAtomicWriteRetainsThePreviousCapture() {
        val (snapshots, root) = store()
        snapshots.write("home", "account", "All", listOf(Row("retained")), Row::class.java)
        val file = File(root, "home").listFiles()!!.single { it.extension == "json" }
        assertTrue(file.renameTo(File(file.path + ".bak")))
        File(file.path + ".new").writeText("{broken")
        assertEquals("retained", snapshots.read("home", "account", "All", Row::class.java)!!.single().id)
    }
}
