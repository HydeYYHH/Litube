package com.hhst.youtubelite.player.sponsor

import com.hhst.youtubelite.extension.SettingsBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorBlockMinifiedAndroidTest {
    @Test fun settingsV1ReflectionRemainsCompatibleInTheMinifiedApk() {
        val backup = SettingsBackup.parse(
            """{"schema":"litube-settings","version":1,"preferences":{"enable_pip":false},"hapticStrength":42,"download":{"wifiOnly":true,"maxConnections":3,"defaultQuality":"720p"}}"""
        )!!
        assertEquals(1, backup.version)
        assertEquals(false, backup.preferences!!["enable_pip"])
        assertEquals(42, backup.hapticStrength)
        assertEquals(3, backup.download!!.maxConnections)
    }

    @Test fun reflectedResponseModelsSurviveR8AndStillSkip() {
        val json = """[
            {"videoID":"bbbbbbbbbbb","segments":[{"category":"sponsor","segment":[0,99]}]},
            {"videoID":"aaaaaaaaaaa","segments":[
                {"category":"sponsor","segment":[1.25,5.5]},
                {"category":"intro","segment":[10,12]},
                {"category":"poi_highlight","segment":[20,20]}
            ]}
        ]"""
        val segments = SponsorBlockManager.interpretHttp(200, json, "aaaaaaaaaaa")!!
        assertEquals(3, segments.size)
        assertEquals(SponsorBlockManager.Segment(1250, 5500, "sponsor"), segments[0])
        val controller = SponsorSkipController()
        assertEquals(5500L, controller.tick(2000, segments, countdownEnabled = false))
        assertEquals(12000L, controller.tick(11000, segments, countdownEnabled = false))
        assertNull(controller.tick(20000, segments, countdownEnabled = false))
        assertTrue(controller.chipIsHighlight)
        assertTrue(SponsorBlockManager.interpretHttp(404, null, "aaaaaaaaaaa")!!.isEmpty())
        assertNull(SponsorBlockManager.interpretHttp(503, null, "aaaaaaaaaaa"))
        assertThrows(SponsorBlockParseException::class.java) {
            SponsorBlockManager.interpretHttp(200, "invalid", "aaaaaaaaaaa")
        }
    }
}
