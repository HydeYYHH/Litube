package com.hhst.youtubelite.watch.chat

import com.hhst.youtubelite.watch.domain.WatchComment
import com.hhst.youtubelite.watch.domain.WatchVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The comments affordance routes to live chat, chat replay, or comments. */
class LiveChatRoutingTest {

    private fun video(chatKey: String?) = WatchVideo(
        id = "v", title = "t", channel = "c", handle = "", channelId = "UCchannel",
        artwork = "", likes = "", views = "", age = "", commentCount = "",
        topComment = WatchComment("", "", ""), description = "", chatKey = chatKey,
    )

    private fun route(video: WatchVideo?, live: Boolean, open: Boolean = true,
                      search: Boolean = false, fullscreen: Boolean = false, pip: Boolean = false) =
        liveChatTarget(video, live, commentsOpen = open, searchOpen = search,
            fullscreen = fullscreen, pip = pip)

    @Test fun normalVideosStayOnComments() {
        assertNull(route(video(null), live = false))
        assertNull(route(video(""), live = false))
        assertNull(route(null, live = true))
    }

    @Test fun aLiveStreamOpensTheLiveContinuation() {
        assertEquals(LiveChatTarget("chat", replay = false), route(video("chat"), live = true))
    }

    @Test fun anEndedStreamOpensTheReplayInsteadOfComments() {
        // isLiveContent stays true after a stream ends; only the player knows it ended.
        assertEquals(LiveChatTarget("chat", replay = true), route(video("chat"), live = false))
    }

    @Test fun hiddenPanelsRouteNowhere() {
        for (target in listOf(
            route(video("chat"), live = true, open = false),
            route(video("chat"), live = true, search = true),
            route(video("chat"), live = true, fullscreen = true),
            route(video("chat"), live = true, pip = true),
        )) assertNull(target)
    }
}
