package com.hhst.youtubelite.extractor

import com.grack.nanojson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile
import org.schabi.newpipe.extractor.services.youtube.streams.ExtractionContext
import org.schabi.newpipe.extractor.services.youtube.streams.StreamDemand
import org.schabi.newpipe.extractor.services.youtube.streams.YoutubeSession

class AdaptiveClientOrderTest {
    private fun context(key: String = "session", catalog: Boolean = false) = ExtractionContext(
        YoutubeSession(key, YoutubeSession.Account.AUTHENTICATED, 0, null, null, null, null,
            "UA", 1, null, "test", JsonObject(), { "" }),
        null, null, null, false, catalog, Long.MAX_VALUE, { true }, { _, _, _, _, _ -> },
    )
    private val defaults = listOf(ClientProfile.WEB_SAFARI, ClientProfile.VISIONOS, ClientProfile.WEB)

    @Test fun usefulClientMovesFirstWithoutDroppingFallbacks() {
        val policy = AdaptiveClientOrder()
        val context = context()
        policy.succeeded(context, ClientProfile.VISIONOS)
        assertEquals(listOf(ClientProfile.VISIONOS, ClientProfile.WEB_SAFARI, ClientProfile.WEB), policy.order(context, defaults))
    }

    @Test fun feedbackExpiresAndNeverLeaksAcrossSessionOrDemand() {
        var now = 0L
        val policy = AdaptiveClientOrder { now }
        val context = context()
        policy.succeeded(context, ClientProfile.VISIONOS)
        assertEquals(defaults, policy.order(context("other"), defaults))
        assertEquals(defaults, policy.order(context(catalog = true), defaults))
        assertEquals(defaults, policy.order(context.withDemand(StreamDemand(720, null, false, emptySet())), defaults))
        now = 10 * 60_000L
        assertEquals(defaults, policy.order(context, defaults))
    }

    @Test fun feedbackCannotIntroduceAnIneligibleProfile() {
        val policy = AdaptiveClientOrder()
        val context = context()
        policy.succeeded(context, ClientProfile.WEB_CREATOR)
        assertEquals(defaults, policy.order(context, defaults))
    }
}
