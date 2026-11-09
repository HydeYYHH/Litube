package com.hhst.youtubelite.player.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerLayoutTest {

    @Test fun collapse_requiresHorizontalOverscrollBeyondEitherWall() {
        assertEquals(MiniPlayerLayout.CollapsedSide.RIGHT,
            MiniPlayerLayout.collapseSide(220f, 190f, 360, 62f, 5f, 32f))
        assertEquals(MiniPlayerLayout.CollapsedSide.LEFT,
            MiniPlayerLayout.collapseSide(-40f, 190f, 360, -52f, 5f, 32f))
        assertEquals(null, MiniPlayerLayout.collapseSide(158f, 190f, 360, 0f, 80f, 32f))
        assertEquals(null, MiniPlayerLayout.collapseSide(190f, 190f, 360, 32f, 0f, 32f))
        assertEquals(null, MiniPlayerLayout.collapseSide(-40f, 190f, 360, -52f, 100f, 32f))
        assertEquals(null, MiniPlayerLayout.collapseSide(0f, 190f, 0, 60f, 0f, 32f))
    }

    @Test fun collapsedPlacement_leavesOnlyPeekVisibleAndScalesWithWindowSize() {
        for (width in listOf(190f, 320f, 420f)) {
            val left = MiniPlayerLayout.collapsedTranslationX(MiniPlayerLayout.CollapsedSide.LEFT,
                158f, width, 800, 24f) + 158f
            val right = MiniPlayerLayout.collapsedTranslationX(MiniPlayerLayout.CollapsedSide.RIGHT,
                158f, width, 800, 24f) + 158f
            assertEquals(24f, left + width, 0f)
            assertEquals(24f, 800 - right, 0f)
        }
    }

    @Test
    fun computeSpec_compactScreenUsesMinWidthAndBottomDock() {
        val spec = MiniPlayerLayout.computeSpec(
            screenWidthDp = 360,
            bottomInsetDp = 0,
            widthOverrideDp = MiniPlayerLayout.NO_WIDTH_OVERRIDE_DP,
        )
        assertEquals(190, spec.widthDp)
        assertEquals(spec.widthDp * 9 / 16, spec.heightDp)
        assertEquals(12, spec.rightMarginDp)
        assertEquals(56 + spec.rightMarginDp, spec.bottomMarginDp)
    }

    @Test
    fun snapX_picksNearestInsetEdgeAndCorrectsOldFlushPlacement() {
        // A right-docked 190dp window in a 360dp parent starts at 158dp.
        assertEquals(-146f, MiniPlayerLayout.snapX(-158f, 158, 190, 360, 12f), 0f)
        assertEquals(0f, MiniPlayerLayout.snapX(12f, 158, 190, 360, 12f), 0f)
        assertEquals(-146f, MiniPlayerLayout.snapX(-100f, 158, 190, 360, 12f), 0f)
        assertEquals(0f, MiniPlayerLayout.snapX(-40f, 158, 190, 360, 12f), 0f)
    }

    @Test
    fun computeSpec_marginGrowsWithWindowWidthAndKeepsBottomInset() {
        for ((width, margin) in listOf(320 to 12, 420 to 14, 600 to 20, 840 to 24, 1280 to 24)) {
            val spec = MiniPlayerLayout.computeSpec(width, 80, MiniPlayerLayout.NO_WIDTH_OVERRIDE_DP)
            assertEquals(margin, spec.rightMarginDp)
            assertEquals(80 + margin, spec.bottomMarginDp)
        }
    }

    @Test
    fun computeSpec_resizeLeavesRoomForBothMarginsOnNarrowScreens() {
        for ((screenWidth, requestedWidth) in listOf(320 to 420, 200 to MiniPlayerLayout.NO_WIDTH_OVERRIDE_DP)) {
            val spec = MiniPlayerLayout.computeSpec(screenWidth, 0, requestedWidth)
            assertEquals(screenWidth - 2 * spec.rightMarginDp, spec.widthDp)
        }
    }

    @Test
    fun clampTranslation_keepsMarginsOnBothAxes() {
        assertEquals(-146f, MiniPlayerLayout.clampTranslation(-1000f, 158, 190, 360, 12f), 0f)
        assertEquals(0f, MiniPlayerLayout.clampTranslation(1000f, 158, 190, 360, 12f), 0f)
        // 24dp system top inset plus 12dp of breathing room.
        assertEquals(-564f, MiniPlayerLayout.clampTranslation(-1000f, 600, 106, 800, 36f, 12f), 0f)
        assertEquals(82f, MiniPlayerLayout.clampTranslation(1000f, 600, 106, 800, 36f, 12f), 0f)
        assertEquals(-20f, MiniPlayerLayout.clampTranslation(-20f, 600, 106, 800, 36f, 12f), 0f)
    }

    @Test
    fun snapX_tightWindowReducesMarginsSymmetrically() {
        assertEquals(5f, MiniPlayerLayout.snapX(-100f, 0, 190, 200, 12f), 0f)
        assertEquals(5f, MiniPlayerLayout.snapX(100f, 0, 190, 200, 12f), 0f)
        assertEquals(0f, MiniPlayerLayout.snapX(100f, 0, 190, 180, 12f), 0f)
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
