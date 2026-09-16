package com.hhst.youtubelite.player.surface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleStyleTest {

    @Test
    fun encodeDecode_roundTrips() {
        val style = SubtitleStyle(
            textScale = 1.37f,
            vertical = 0.2f,
            horizontal = -0.4f,
            background = SubtitleStyle.Background.OPAQUE,
            bold = true,
            colorArgb = SubtitleStyle.COLOR_YELLOW,
        )
        val decoded = SubtitleStyle.decode(style.encode())
        assertEquals(style.textScale, decoded.textScale, 0.005f)
        assertEquals(style.vertical, decoded.vertical, 0.005f)
        assertEquals(style.horizontal, decoded.horizontal, 0.005f)
        assertEquals(style.background, decoded.background)
        assertEquals(style.bold, decoded.bold)
        assertEquals(style.colorArgb, decoded.colorArgb)
    }

    @Test
    fun decode_defaultStyle_roundTrips() {
        val style = SubtitleStyle()
        assertEquals(style, SubtitleStyle.decode(style.encode()))
    }

    @Test
    fun decode_nullOrBlank_fallsBackToDefault() {
        assertEquals(SubtitleStyle(), SubtitleStyle.decode(null))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode(""))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("   "))
    }

    @Test
    fun decode_corruptInput_fallsBackToDefault() {
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("garbage"))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("1.00;TOP;SEMI;true"))
        assertEquals(SubtitleStyle(), SubtitleStyle.decode("v2;1;0;0;SEMI"))
    }

    @Test
    fun decode_legacyFiveField_migratesPositionAndColor() {
        val topYellow = SubtitleStyle.decode("1.50;TOP;OPAQUE;true;YELLOW")
        assertEquals(1.5f, topYellow.textScale, 0.001f)
        assertEquals(0f, topYellow.vertical, 0.001f)
        assertEquals(0f, topYellow.horizontal, 0.001f)
        assertEquals(SubtitleStyle.Background.OPAQUE, topYellow.background)
        assertTrue(topYellow.bold)
        assertEquals(SubtitleStyle.COLOR_YELLOW, topYellow.colorArgb)

        val bottomWhite = SubtitleStyle.decode("1.00;BOTTOM;SEMI;false;WHITE")
        assertEquals(1f, bottomWhite.vertical, 0.001f)
        assertEquals(SubtitleStyle.COLOR_WHITE, bottomWhite.colorArgb)
    }

    @Test
    fun decode_legacyUnknownEnums_fallBackPerField() {
        val decoded = SubtitleStyle.decode("1.25;SIDWAYS;GLOW;maybe;CYAN")
        assertEquals(1.25f, decoded.textScale)
        assertEquals(SubtitleStyle().vertical, decoded.vertical, 0.001f)
        assertEquals(SubtitleStyle().background, decoded.background)
        assertEquals(SubtitleStyle().bold, decoded.bold)
        assertEquals(SubtitleStyle().colorArgb, decoded.colorArgb)
    }

    @Test
    fun decode_outOfRangeScale_isClamped() {
        assertEquals(
            SubtitleStyle.MAX_SCALE,
            SubtitleStyle.decode("9.99;TOP;SEMI;false;WHITE").textScale,
        )
        assertEquals(
            SubtitleStyle.MIN_SCALE,
            SubtitleStyle.decode("0.01;TOP;SEMI;false;WHITE").textScale,
        )
    }

    @Test
    fun clamped_pinsScalePositionAndOffset() {
        val clamped = SubtitleStyle(
            textScale = 9f,
            vertical = 2f,
            horizontal = -3f,
        ).clamped()
        assertEquals(SubtitleStyle.MAX_SCALE, clamped.textScale)
        assertEquals(1f, clamped.vertical)
        assertEquals(-1f, clamped.horizontal)
    }

    @Test
    fun verticalBias_mapsTopCenterBottom() {
        assertEquals(-1f, SubtitleStyle(vertical = 0f).verticalBias())
        assertEquals(0f, SubtitleStyle(vertical = 0.5f).verticalBias())
        assertEquals(1f, SubtitleStyle(vertical = 1f).verticalBias())
    }

    @Test
    fun parseColorHex_acceptsHashRgbAndArgb() {
        assertEquals(SubtitleStyle.COLOR_WHITE, SubtitleStyle.parseColorHex("#FFF"))
        assertEquals(SubtitleStyle.COLOR_WHITE, SubtitleStyle.parseColorHex("#FFFFFF"))
        assertEquals(SubtitleStyle.COLOR_WHITE, SubtitleStyle.parseColorHex("FFFFFFFF"))
        assertEquals(SubtitleStyle.COLOR_YELLOW, SubtitleStyle.parseColorHex("#FFFF00"))
        assertEquals(SubtitleStyle.COLOR_YELLOW, SubtitleStyle.parseColorHex("#FFFFFF00"))
        assertEquals(null, SubtitleStyle.parseColorHex("gg"))
        assertEquals(null, SubtitleStyle.parseColorHex("#12"))
    }

    @Test
    fun formatColorHex_dropsOpaqueAlpha() {
        assertEquals("#FFFFFF", SubtitleStyle.formatColorHex(SubtitleStyle.COLOR_WHITE))
        assertEquals("#FFFF00", SubtitleStyle.formatColorHex(SubtitleStyle.COLOR_YELLOW))
    }

    @Test
    fun encode_usesDotDecimalRegardlessOfDefaultLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val encoded = SubtitleStyle(textScale = 1.5f).encode()
            assertTrue(encoded.contains("1.50"))
            assertTrue(!encoded.contains("1,50"))
            assertEquals(1.5f, SubtitleStyle.decode(encoded).textScale, 0.001f)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
