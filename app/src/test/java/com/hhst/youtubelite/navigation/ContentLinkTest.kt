package com.hhst.youtubelite.navigation

import org.junit.Assert.*
import org.junit.Test

class ContentLinkTest {
    @Test fun routesContentWithoutLosingIdentity() {
        assertEquals(ContentLink.Video("aqz-KE-bpKQ", 32), ContentLink.parse("https://youtu.be/aqz-KE-bpKQ?t=32"))
        assertEquals(ContentLink.Playlist("PLtest"), ContentLink.parse("https://m.youtube.com/playlist?list=PLtest"))
        assertEquals(ContentLink.Channel("@Blender"), ContentLink.parse("https://www.youtube.com/@Blender/videos"))
        assertEquals(ContentLink.Post("Ugkx_some_post"), ContentLink.parse("https://www.youtube.com/post/Ugkx_some_post"))
        assertEquals(ContentLink.Search("open movie"), ContentLink.parse("https://m.youtube.com/results?search_query=open+movie"))
        assertEquals(ContentLink.Short("aqz-KE-bpKQ"), ContentLink.parse("https://youtube.com/shorts/aqz-KE-bpKQ"))
    }
    @Test fun rejectsLookalikesCredentialsPortsAndMalformedIds() {
        for (url in listOf("javascript:alert(1)", "https://youtube.com.evil.test/watch?v=aqz-KE-bpKQ",
            "https://user@youtube.com/watch?v=aqz-KE-bpKQ", "https://youtube.com:444/watch?v=aqz-KE-bpKQ",
            "https://ytimg.com/watch?v=aqz-KE-bpKQ", "https://youtube.com/watch?v=invalid",
            "https://youtube.com/results?search_query=%xx")) assertNull(url, ContentLink.parse(url))
    }
    @Test fun timestampUnitsArePreservedAndOverflowIsRejected() {
        assertEquals(3723L, (ContentLink.parse("https://youtu.be/aqz-KE-bpKQ?t=1h2m3s") as ContentLink.Video).seconds)
        assertEquals(90L, (ContentLink.parse("https://www.youtube.com/watch?v=aqz-KE-bpKQ&start=90") as ContentLink.Video).seconds)
        assertNull(ContentLink.timestamp("-1"))
        assertNull(ContentLink.timestamp("9223372036854775807h"))
        assertNull(ContentLink.timestamp("not-a-time"))
        assertEquals(0L, ContentLink.timestamp("0"))
    }

}
