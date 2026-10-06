package com.hhst.youtubelite.player.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerLayoutTest {

    @Test
    fun computeSpec_compactScreenUsesMinWidthAndBottomDock() {
        val spec = MiniPlayerLayout.computeSpec(
            screenWidthDp = 360,
            bottomInsetDp = 0,
            widthOverrideDp = MiniPlayerLayout.NO_WIDTH_OVERRIDE_DP,
        )
        assertEquals(190, spec.widthDp)
        assertEquals(spec.widthDp * 9 / 16, spec.heightDp)
        assertEquals(MiniPlayerLayout.OUTER_MARGIN_DP, spec.rightMarginDp)
        assertTrue(spec.bottomMarginDp >= 56 + MiniPlayerLayout.OUTER_MARGIN_DP)
    }

    @Test
    fun snapX_picksNearestEdge() {
        assertEquals(0f, MiniPlayerLayout.snapX(10f, 0, 100, 400), 0f)
        assertEquals(300f, MiniPlayerLayout.snapX(200f, 0, 100, 400), 0f)
    }

    @Test
    fun computeGapByRatio_scalesWithWidth() {
        val atRef = MiniPlayerLayout.computeGapByRatio(190, 190)
        assertEquals(18, atRef)
        val wider = MiniPlayerLayout.computeGapByRatio(320, 190)
        assertTrue(wider > atRef)
        assertEquals(0, MiniPlayerLayout.computeGapByRatio(40, 190))
    }

    @Test
    fun controlGap_atMinWidth() {
        assertEquals(18, MiniPlayerLayout.controlGap(190, 360))
    }

    @Test
    fun popupWidthPx_wrapsThenCapsAtEightyPercent() {
        assertEquals(40, MiniPlayerLayout.popupWidthPx(40, 1000))
        assertEquals(800, MiniPlayerLayout.popupWidthPx(900, 1000))
        assertEquals(0, MiniPlayerLayout.popupWidthPx(0, 1000))
    }

    @Test fun closeTarget_requiresWindowCenterInsideBothAxes() {
        assertTrue(MiniPlayerLayout.hitsCloseTarget(200f, 750f, 152f, 714f, 248f, 786f))
        assertFalse(MiniPlayerLayout.hitsCloseTarget(150f, 750f, 152f, 714f, 248f, 786f))
        assertFalse(MiniPlayerLayout.hitsCloseTarget(200f, 800f, 152f, 714f, 248f, 786f))
        assertFalse(MiniPlayerLayout.hitsCloseTarget(200f, 500f, 152f, 714f, 248f, 786f))
    }
    @Test fun closeTarget_rejectsInvalidOrUnmeasuredTarget() {
        assertFalse(MiniPlayerLayout.hitsCloseTarget(0f, 0f, 0f, 0f, 0f, 0f))
    }
}
