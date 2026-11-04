package com.hhst.youtubelite.player.surface

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hhst.youtubelite.cast.CastFailureReport
import com.hhst.youtubelite.cast.CastDevice
import com.hhst.youtubelite.cast.DlnaRequestException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CastFailureDialogAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun reportRetainsProtocolFailureDetailsWithoutCredentials() {
        val endpoint = "http://user:password@192.168.1.5:1234/control?token=private-query".toHttpUrl()
        val error = DlnaRequestException("SetAVTransportURI", endpoint, 500, "714",
            "Illegal MIME-Type https://example.test/video?signature=private-signature\nAuthorization: Bearer private-access\nCookie: private-cookie")
        val report = CastFailureReport.create("dlna", CastDevice("secret-route", "Living room", true), "abcdefghijk", "start", error)
        for (secret in listOf("password", "private-query", "private-signature", "private-access", "private-cookie", "secret-route")) {
            assertFalse("Report contains $secret", report.text.contains(secret))
        }
        assertTrue(report.text.contains("Device: Living room"))
        assertTrue(report.text.contains("Stage: SetAVTransportURI"))
        assertTrue(report.text.contains("HTTP: 500; UPnP: 714"))
    }

    @Test fun reportCanBeCopiedAndClosedWithoutRetryingTheRenderer() {
        val text = "Litube cast report\nDevice: Living room\nStage: SetAVTransportURI\nHTTP: 500; UPnP: 714"
        var closed = 0
        compose.setContent { MaterialTheme { CastFailureDialog(CastFailureReport("test", text)) { closed++ } } }
        compose.onNodeWithTag("cast-failure-dialog").assertIsDisplayed()
        compose.onNodeWithTag("cast-failure-copy").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            assertEquals(text, clipboard.primaryClip?.getItemAt(0)?.text.toString())
            assertEquals(0, closed)
        }
        compose.onNodeWithText("Close").performClick()
        assertEquals(1, closed)
    }
}
