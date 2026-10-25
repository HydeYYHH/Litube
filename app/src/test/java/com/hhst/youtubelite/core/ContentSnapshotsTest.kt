package com.hhst.youtubelite.core

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Type

/**
 * Snapshots are bounded, account-scoped and atomic: the tests pin the scoping (private
 * rows never cross accounts), the query match, the version/age discard, the row cap and
 * the absence of torn temp files.
 */
class ContentSnapshotsTest {
    @get:Rule val tmp = TemporaryFolder()
    private data class Row(val id: String, val title: String = "")
    private val rowsType: Type = TypeToken.getParameterized(List::class.java, Row::class.java).type

    private fun store(clock: () -> Long = System::currentTimeMillis): Pair<ContentSnapshots, File> {
        val dir = tmp.newFolder()
        return ContentSnapshots(dir, Gson(), clock) to dir
    }

    @Test fun aSnapshotRoundTripsForItsOwnAccountAndQuery() {
        val (snapshots, _) = store()
        snapshots.write("home", "acct-1", "All", listOf(Row("v1", "First")))
        assertEquals(listOf(Row("v1", "First")), snapshots.read<Row>("home", "acct-1", "All", rowsType))
    }

    @Test fun privateRowsStayScopedToTheCapturingAccount() {
        val (snapshots, _) = store()
        snapshots.write("home", "acct-1", "All", listOf(Row("private-1")))
        assertNull(snapshots.read<Row>("home", "acct-2", "All", rowsType))
        assertEquals(listOf(Row("private-1")), snapshots.read<Row>("home", "acct-1", "All", rowsType))
    }

    @Test fun aSnapshotForAnotherQueryDoesNotAnswer() {
        val (snapshots, _) = store()
        snapshots.write("home", "acct-1", "Music", listOf(Row("m1")))
        assertNull(snapshots.read<Row>("home", "acct-1", "All", rowsType))
    }

    @Test fun staleAndCorruptSnapshotsAreDiscarded() {
        var now = 0L
        val (snapshots, root) = store({ now })
        snapshots.write("home", "acct-1", "All", listOf(Row("old")))
        now = 73 * 60 * 60 * 1000L
        assertNull(snapshots.read<Row>("home", "acct-1", "All", rowsType))
        snapshots.write("home", "acct-1", "All", listOf(Row("fresh")))
        File(root, "home").listFiles()!!.single { it.name.endsWith(".json") }.writeText("{not json")
        assertNull(snapshots.read<Row>("home", "acct-1", "All", rowsType))
    }

    @Test fun rowsAreCappedAndNoTempFilesSurviveAWrite() {
        val (snapshots, root) = store()
        snapshots.write("home", "acct-1", "All", (1..300).map { Row("v$it") })
        val stored = snapshots.read<Row>("home", "acct-1", "All", rowsType)!!
        assertEquals(120, stored.size)
        assertEquals("v1", stored.first().id)
        assertTrue("no torn temp file may survive", root.walkTopDown().none { it.extension == "tmp" })
    }

    @Test fun theSnapshotSetStaysBoundedPerKind() {
        val (snapshots, root) = store()
        for (index in 0 until 60) snapshots.write("home", "acct-1", "query-$index", listOf(Row("v$index")))
        val kept = File(root, "home").listFiles()!!.filter { it.extension == "json" }
        assertTrue("snapshot files must stay bounded, got ${kept.size}", kept.size <= 48)
    }

    @Test fun concurrentWritersOfOneKeyNeverLeaveATornSnapshot() {
        val (snapshots, root) = store()
        val writers = (1..8).map { index -> Thread {
            repeat(40) { round -> snapshots.write("home", "acct-1", "All", listOf(Row("w$index-$round"))) }
        } }
        writers.forEach(Thread::start)
        writers.forEach { it.join(30_000) }
        val read = snapshots.read<Row>("home", "acct-1", "All", rowsType)
        assertTrue("a concurrent same-key write must leave one readable snapshot", read != null && read.size == 1)
        assertTrue("no temp may survive concurrent writes", root.walkTopDown().none { it.extension == "tmp" })
    }

    @Test fun leftoverTempsDoNotCountTowardTheKindCap() {
        val (snapshots, root) = store()
        snapshots.write("home", "acct-1", "All", listOf(Row("v1")))
        File(File(root, "home"), "acct-1-deadbeef.123.tmp").writeText("torn")
        for (index in 0 until 60) snapshots.write("home", "acct-1", "query-$index", listOf(Row("v$index")))
        val kept = File(root, "home").listFiles()!!.filter { it.extension == "json" }
        assertTrue("temps must not evict live snapshots: ${kept.size}", kept.size <= 48)
        assertNull(snapshots.read<Row>("home", "acct-1", "nonexistent", rowsType))
    }

    @Test fun futureCapturesAndOversizedFilesAreDiscarded() {
        var now = 10_000L
        val (snapshots, root) = store { now }
        snapshots.write("home", "account", "All", listOf(Row("future")))
        now = 9_000L
        assertNull(snapshots.read<Row>("home", "account", "All", rowsType))
        snapshots.write("home", "account", "All", listOf(Row("fresh")))
        File(root, "home").listFiles()!!.single { it.extension == "json" }
            .writeText("x".repeat(2 * 1024 * 1024 + 1))
        assertNull(snapshots.read<Row>("home", "account", "All", rowsType))
    }

    @Test fun sanitizedAccountNamesCannotCollideAndEmptyResultsClearTheSnapshot() {
        val (snapshots, _) = store()
        snapshots.write("home", "account/a", "All", listOf(Row("private")))
        assertNull(snapshots.read<Row>("home", "account?a", "All", rowsType))
        snapshots.clear(account = "account?a")
        assertEquals("private", snapshots.read<Row>("home", "account/a", "All", rowsType)!!.single().id)
        snapshots.write("home", "account/a", "All", emptyList())
        assertNull(snapshots.read<Row>("home", "account/a", "All", rowsType))
    }

    @Test fun anInterruptedAtomicWriteRetainsThePreviousCapture() {
        val (snapshots, root) = store()
        snapshots.write("home", "account", "All", listOf(Row("retained")))
        val file = File(root, "home").listFiles()!!.single { it.extension == "json" }
        assertTrue(file.renameTo(File(file.path + ".bak")))
        File(file.path + ".new").writeText("{broken")
        assertEquals("retained", snapshots.read<Row>("home", "account", "All", rowsType)!!.single().id)
    }
}
