package com.hhst.youtubelite.browser

import org.junit.Assert.*
import org.junit.Test

class TransientUiTest {
    @Test fun onlyTheReportedModalRegionOwnsTouches() {
        val origin = PageOrigin(4, 7)
        val ui = TransientUi.fromPage(TransientKind.MEDIA_MENU, origin,
            """{"viewportWidth":360,"regions":[[10,100,350,240]]}""")
        assertEquals(origin, ui.origin)
        assertEquals(360.0, ui.viewportWidth, 0.0)
        assertTrue(ui.contains(180.0, 180.0))
        assertFalse(ui.contains(180.0, 60.0))
        assertFalse(ui.contains(350.0, 180.0))
        assertFalse(TransientUi.fromPage(TransientKind.NONE, origin,
            """{"viewportWidth":360,"regions":[[0,0,360,700]]}""").contains(180.0, 180.0))
    }

    @Test fun malformedGeometryNeverForwardsATouchIntoThePage() {
        for (json in listOf(null, "bad", """{"viewportWidth":0,"regions":[[0,0,20,20]]}""",
            """{"viewportWidth":360,"regions":[[20,20,0,0]]}""")) {
            assertFalse(TransientUi.fromPage(TransientKind.MEDIA_MENU, PageOrigin(1, 2), json).contains(10.0, 10.0))
        }
    }
}
