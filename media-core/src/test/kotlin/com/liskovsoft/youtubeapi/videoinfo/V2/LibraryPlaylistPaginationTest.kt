package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class LibraryPlaylistPaginationTest {
    private val context = RequestContext("owner", "token")

    private fun load(body: String, continuation: String? = null): BrowsePage {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(200, body)
        }
        val result = loadLibrary(LibrarySection.PLAYLISTS, context, continuation, http)
        assertTrue(result.error?.code, result.ok)
        return result.value!!
    }

    @Test fun aReloadCursorAtTheEndOfThePlaylistListIsNotAnotherPage() {
        val page = load("""{"continuationContents":{"gridContinuation":{
            "items":[{"gridPlaylistRenderer":{"playlistId":"PLlast","title":{"simpleText":"Last"}}}],
            "continuations":[{"reloadContinuationData":{"continuation":"reload-list"}}]
        }}}""", "last-page")
        assertEquals(listOf("PLlast"), page.items.map { it.id })
        assertNull(page.nextKey)
    }

    @Test fun playlistCardPreviewCursorsDoNotPaginateTheCollection() {
        for (renderer in listOf("playlistRenderer", "gridPlaylistRenderer", "tileRenderer", "lockupViewModel")) {
            val page = load("""{"contents":{"gridRenderer":{"items":[{"$renderer":{
                "playlistId":"PLlast","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
                "continuations":[{"nextContinuationData":{"continuation":"card-preview"}}]
            }}]}}}""")
            assertEquals(listOf("PLlast"), page.items.map { it.id })
            assertNull("A $renderer cursor belongs to the card", page.nextKey)
        }
    }

    @Test fun aRefreshCursorCannotOverrideTheActualNextPage() {
        val page = load("""{"contents":{"gridRenderer":{
            "items":[{"gridPlaylistRenderer":{"playlistId":"PLfirst"}}],
            "continuations":[{"reloadContinuationData":{"continuation":"refresh"}},
                {"nextContinuationData":{"continuation":"page-2"}}]
        }}}""")
        assertEquals("page-2", page.nextKey)
    }

    @Test fun realContinuationItemsAndEmptyFinalResponsesRemainSupported() {
        val page = load("""{"onResponseReceivedActions":[{"appendContinuationItemsAction":{
            "continuationItems":[{"gridPlaylistRenderer":{"playlistId":"PLsecond"}},
                {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"page-3"}}}}]
        }}]}""", "page-2")
        assertEquals(listOf("PLsecond"), page.items.map { it.id })
        assertEquals("page-3", page.nextKey)
        val last = load("""{"onResponseReceivedActions":[{"appendContinuationItemsAction":{
            "continuationItems":[]}}]}""", "page-3")
        assertTrue(last.items.isEmpty())
        assertNull(last.nextKey)
    }

    @Test fun invalidationAndBlankCursorsDoNotCreateAnotherPage() {
        for (continuation in listOf(
            """{"invalidationContinuationData":{"continuation":"invalidate"}}""",
            """{"timedContinuationData":{"continuation":"poll"}}""",
            """{"nextContinuationData":{"continuation":" "}}""",
        )) {
            assertNull(load("""{"contents":{"gridRenderer":{"items":[],"continuations":[$continuation]}}}""").nextKey)
        }
    }

    @Test fun serverFailuresAreStillReportedForRetry() {
        val http = object : HttpClient {
            override fun execute(request: HttpRequest) = HttpReply(503, "{}")
        }
        assertFalse(loadLibrary(LibrarySection.PLAYLISTS, context, "page-2", http).ok)
    }

    @Test fun commentReloadTokensRemainAvailableOutsideBrowsePagination() {
        val root = jsonObject("""{"continuations":[{"reloadContinuationData":{"continuation":"comments"}}]}""")!!
        assertEquals("comments", pageKey(root))
    }
}
