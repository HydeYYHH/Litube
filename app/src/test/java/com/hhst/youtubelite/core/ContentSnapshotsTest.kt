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
}
