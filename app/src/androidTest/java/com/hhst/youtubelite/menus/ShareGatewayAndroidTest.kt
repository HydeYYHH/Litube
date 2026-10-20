package com.hhst.youtubelite.menus

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import com.hhst.youtubelite.share.ShareGateway
import org.junit.Assert.*
import org.junit.Test

@SdkSuppress(minSdkVersion = 35, maxSdkVersion = 35)
class ShareGatewayAndroidTest {
    @Test fun chooserCarriesOnlyTheVideoLinkAndDoesNotCopy() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var launched: Intent? = null
                val gateway = ShareGateway(activity) { launched = it }
                assertTrue(gateway.share("harbour"))
                assertEquals(Intent.ACTION_CHOOSER, launched!!.action)
                val payload = launched!!.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
                assertEquals(Intent.ACTION_SEND, payload.action)
                assertEquals("text/plain", payload.type)
                assertEquals("https://youtu.be/TLkA0RELQ1g", payload.getStringExtra(Intent.EXTRA_TEXT))
                assertNull(gateway.lastCopiedUrl)
            }
        }
    }

    @Test fun missingChooserCopiesTheActualLinkAndUnknownIdsDoNothing() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var attempts = 0
                val gateway = ShareGateway(activity) { attempts++; throw ActivityNotFoundException() }
                assertTrue(gateway.share("bunny"))
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals("https://youtu.be/aqz-KE-bpKQ", clipboard.primaryClip!!.getItemAt(0).text.toString())
                assertEquals("https://youtu.be/aqz-KE-bpKQ", gateway.lastCopiedUrl)
                assertFalse(gateway.share("unknown"))
                assertEquals(1, attempts)
            }
        }
    }

    @Test fun rejectedChooserDoesNotSilentlyWriteTheClipboard() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val gateway = ShareGateway(activity) { throw SecurityException() }
                assertFalse(gateway.share("bunny"))
                assertNull(gateway.lastCopiedUrl)
            }
        }
    }
}
