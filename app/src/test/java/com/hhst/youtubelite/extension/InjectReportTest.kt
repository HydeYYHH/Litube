package com.hhst.youtubelite.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses inject callback JSON into [InjectReport]. */
class InjectReportTest {

    @Test
    fun parse_nullOrEmpty_isFailure() {
        val empty = InjectReport.parse(null)
        assertFalse(empty.ok)
        assertEquals("empty_callback", empty.reason)

        val blank = InjectReport.parse("   ")
        assertFalse(blank.ok)
    }

    @Test
    fun parse_successfulInject_isOk() {
        val raw = """{"ok":true,"skipped":false,"reason":"injected","failures":[]}"""
        val report = InjectReport.parse(raw)

        assertTrue(report.ok)
        assertEquals("injected", report.reason)
        assertEquals("ok reason=injected", report.summary())
    }

    @Test
    fun parse_failureCarriesElements() {
        val raw =
            """{"ok":false,"reason":"missing_settings_root","failures":[{"element":"ytm-settings","reason":"settings list root missing"}]}"""
        val report = InjectReport.parse(raw)

        assertFalse(report.ok)
        assertEquals("missing_settings_root", report.reason)
        assertTrue(report.hasFailures)
        assertTrue(report.failures.any { it.element == "ytm-settings" })
    }

    @Test
    fun parse_quotedJsonString_fromEvaluateJavascript() {
        val inner = """{"ok":true,"skipped":false,"reason":"injected","failures":[]}"""
        val raw = "\"${inner.replace("\"", "\\\"")}\""
        val report = InjectReport.parse(raw)

        assertTrue(report.ok)
        assertEquals("injected", report.reason)
    }

    @Test
    fun parse_skippedNotSettings_isOk() {
        val raw = """{"ok":true,"skipped":true,"reason":"not_settings_page","failures":[]}"""
        val report = InjectReport.parse(raw)

        assertTrue(report.ok)
        assertTrue(report.skipped)
        assertEquals("skipped: not_settings_page", report.summary())
    }

    @Test
    fun parse_malformedJson_isParseError() {
        val report = InjectReport.parse("{not-json")
        assertFalse(report.ok)
        assertEquals("parse_error", report.reason)
    }

    @Test
    fun summary_listsFailuresWhenNotOk() {
        val report = InjectReport(
            ok = false,
            failures = listOf(InjectFailure(element = "ytm-settings", reason = "missing")),
        )
        val text = report.summary()
        assertTrue(text.contains("ok=false"))
        assertTrue(text.contains("ytm-settings: missing"))
    }
}
