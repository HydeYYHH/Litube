package com.hhst.youtubelite.extractor

import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class SessionCaptureAndroidTest {
    @Test fun captureAndValidityUseTheSameIdentityStamp() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""<script>ytcfg.set({"INNERTUBE_CONTEXT":{"client":{"visitorData":"fixture"}},"LOGGED_IN":false});</script>""".toResponseBody()).build()
        }.build()
        val sessions = YoutubeSessionProvider(InstrumentationRegistry.getInstrumentation().targetContext, client)
        val stamp = sessions.scopeHint()
        val session = sessions.captureBounded("fixture", true,
            System.nanoTime() + TimeUnit.SECONDS.toNanos(5), { true })
        assertEquals(stamp, sessions.scopeHint())
        assertTrue(sessions.isCurrent(session))
        assertEquals(session.key, sessions.captureCached()?.key)
    }
    @Test fun metadataCacheCannotCrossCapturedIdentities() {
        val sessions = org.koin.core.context.GlobalContext.get().get<YoutubeSessionProvider>()
        fun session(key: String) = YoutubeWebSession(key, WebAccount.ANONYMOUS, 0, null, null, null, null,
            "fixture", 0, null, "fixture", kotlinx.serialization.json.JsonObject(emptyMap())) { "" }
        assertNotEquals(sessions.accountScope(session("first")), sessions.accountScope(session("second")))
    }

}
