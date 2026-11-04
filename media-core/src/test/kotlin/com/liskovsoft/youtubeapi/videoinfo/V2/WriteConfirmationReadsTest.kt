package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.*
import org.junit.Test

class WriteConfirmationReadsTest {
    private val context = RequestContext("owner", "access", generation = 7)
    private fun choices(vararg options: String) = """{"contents":[{"addToPlaylistRenderer":{"playlists":[${options.joinToString(",")}]}}]}"""
    private fun choice(id: String, state: String?) = """{"playlistAddToOptionRenderer":{"playlistId":"$id"${state?.let { ",\"containsSelectedVideos\":\"$it\"" }.orEmpty()}}}"""
    private fun control(endpoint: String, disabled: Boolean = false) = """{"buttonRenderer":{"isDisabled":$disabled,"serviceEndpoint":{
        "commandMetadata":{"webCommandMetadata":{"apiUrl":"/youtubei/v1/history/$endpoint"}}}}}"""

    @Test fun membershipDistinguishesExplicitAbsenceFromAnUnknownServerFlag() {
        val parsed = playlistChoices(choices(choice("PLall", "ALL"), choice("PLnone", "NONE"),
            choice("PLmissing", null), choice("PLsome", "SOME"), choice("PLfuture", "FUTURE")))
        assertEquals(listOf(true, false, null, null, null), parsed.map { it.containsState })
        assertEquals(listOf(true, false, false, false, false), parsed.map { it.contains })
        assertNull(parsed.firstOrNull { it.id == "not-returned" }?.containsState)
    }

    @Test fun aFreshMembershipReadUsesTheRequestedSingleVideoAndCapturedCredentials() {
        val calls = mutableListOf<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls += request
                return HttpReply(200, choices(choice("PLone", if (calls.size == 1) "NONE" else "ALL")))
            }
        }
        assertEquals(false, loadPlaylistChoices("abcdefghijk", context, http).value!!.single().containsState)
        assertEquals(true, loadPlaylistChoices("abcdefghijk", context, http).value!!.single().containsState)
        assertEquals(2, calls.size)
        for (request in calls) {
            assertTrue(request.url.contains("/playlist/get_add_to_playlist"))
            assertEquals("Bearer access", request.headers["Authorization"])
            assertEquals(listOf("abcdefghijk"), jsonObject(request.body)!!.arr("videoIds").map { (it as Json.Str).value })
        }
    }

    @Test fun historyManagementReadsOnlyExplicitAvailableCommands() {
        assertEquals(false, watchHistoryManagement("""{"contents":[${control("pause_watch_history") }]}""").paused)
        assertEquals(true, watchHistoryManagement("""{"contents":[${control("resume_watch_history") }]}""").paused)
        assertNull(watchHistoryManagement("""{"contents":[${control("pause_watch_history", true)}]}""").paused)
        assertNull(watchHistoryManagement("""{"contents":[${control("pause_watch_history")},${control("resume_watch_history")}]}""").paused)
    }

    @Test fun emptyHistoryLabelsAndUnrecognizedCommandsNeverProveThePausedState() {
        for (body in listOf("{}", """{"contents":[],"title":"Watch history is paused"}""",
            """{"buttonRenderer":{"text":{"simpleText":"Resume watch history"}}}""",
            """{"pauseWatchHistoryEndpoint":null,"resumeWatchHistoryEndpoint":false}""",
            """{"commandMetadata":{"webCommandMetadata":{"apiUrl":"https://unrelated.example/youtubei/v1/history/resume_watch_history"}}}""")) {
            assertNull(watchHistoryManagement(body).paused)
        }
    }

    @Test fun aFreshHistoryManagementReadUsesTheCapturedAccountAndDoesNotCache() {
        val calls = mutableListOf<HttpRequest>()
        val http = object : HttpClient {
            override fun execute(request: HttpRequest): HttpReply {
                calls += request
                return HttpReply(200, """{"contents":[${control(if (calls.size == 1) "pause_watch_history" else "resume_watch_history")}]}""")
            }
        }
        assertEquals(false, loadWatchHistoryManagement(context, http).value!!.paused)
        assertEquals(true, loadWatchHistoryManagement(context, http).value!!.paused)
        assertEquals(2, calls.size)
        for (request in calls) {
            assertEquals("FEhistory", jsonObject(request.body)!!.text("browseId"))
            assertEquals("Bearer access", request.headers["Authorization"])
        }
    }

    @Test fun managementFailuresAndAnonymousIdentityStayFailures() {
        val http = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(429, "{}") }
        assertEquals(RequestFailure.AUTH, loadWatchHistoryManagement(RequestContext(), http).error!!.kind)
        assertEquals(429, loadWatchHistoryManagement(context, http).error!!.status)
        val unknown = object : HttpClient { override fun execute(request: HttpRequest) = HttpReply(200, "{}") }
        assertTrue(loadWatchHistoryManagement(context, unknown).ok)
        assertNull(loadWatchHistoryManagement(context, unknown).value!!.paused)
    }
}
