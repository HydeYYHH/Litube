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

    // -- swipe-down dismiss rule --

    private val d = 2f // density

    @Test
    fun shouldDismiss_travelPastThreshold() {
        // 96 dp * 2 = 192 px travel threshold.
        assertTrue(MiniPlayerLayout.shouldDismiss(200f, 0f, 0f, d))
        assertFalse(MiniPlayerLayout.shouldDismiss(150f, 0f, 0f, d))
    }

    @Test
    fun shouldDismiss_flingBeatsShortTravel() {
        // 700 dp/s * 2 = 1400 px/s fling threshold.
        assertTrue(MiniPlayerLayout.shouldDismiss(10f, 0f, 1600f, d))
        assertFalse(MiniPlayerLayout.shouldDismiss(10f, 0f, 1000f, d))
    }

    @Test
    fun shouldDismiss_upwardOrHorizontalNeverDismisses() {
        assertFalse(MiniPlayerLayout.shouldDismiss(-300f, 0f, 0f, d))
        // More horizontal than vertical: a sideways drag, not a dismiss swipe.
        assertFalse(MiniPlayerLayout.shouldDismiss(300f, 400f, 0f, d))
        // Fling rule still requires the predominant-downward shape.
        assertFalse(MiniPlayerLayout.shouldDismiss(10f, 400f, 1600f, d))
    }
}
