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

    @Test fun actualMediaRefusalOverridesAnExtractionWinnerWithoutDroppingAnyClient() {
        val policy = AdaptiveClientOrder { 0L }
        val context = context()
        policy.succeeded(context, ClientProfile.WEB_SAFARI)
        policy.mediaForbidden("video", "account-route", ClientProfile.WEB_SAFARI)
        val order = policy.forVideo("video", "account-route").order(context, defaults)
        assertEquals(listOf(ClientProfile.VISIONOS, ClientProfile.WEB, ClientProfile.WEB_SAFARI), order)
        assertEquals(defaults.toSet(), order.toSet())
    }

    @Test fun refusalSurvivesTheSameVideosRefreshButNeverLeaksToAnotherVideoOrIdentity() {
        val policy = AdaptiveClientOrder { 0L }
        policy.mediaForbidden("video", "account-route", ClientProfile.WEB_SAFARI)
        val refreshed = context("new-extraction-generation")
        assertEquals(ClientProfile.VISIONOS, policy.forVideo("video", "account-route").order(refreshed, defaults).first())
        assertEquals(defaults, policy.forVideo("other-video", "account-route").order(refreshed, defaults))
        assertEquals(defaults, policy.forVideo("video", "other-account-or-route").order(refreshed, defaults))
    }

    @Test fun refusalExpiresAndCannotIntroduceAnIneligibleClient() {
        var now = 0L
        val policy = AdaptiveClientOrder { now }
        policy.mediaForbidden("video", "identity", ClientProfile.WEB_SAFARI)
        policy.mediaForbidden("video", "identity", ClientProfile.WEB_CREATOR)
        val scoped = policy.forVideo("video", "identity")
        assertEquals(ClientProfile.VISIONOS, scoped.order(context(), defaults).first())
        now = 30_000
        assertEquals(defaults, scoped.order(context(), defaults))
    }

    @Test fun boundedRefusalMemoryEvictsOldestEvidence() {
        val policy = AdaptiveClientOrder { 0L }
        repeat(65) { policy.mediaForbidden("video-$it", "identity", ClientProfile.WEB_SAFARI) }
        assertEquals(defaults, policy.forVideo("video-0", "identity").order(context(), defaults))
        assertEquals(ClientProfile.VISIONOS, policy.forVideo("video-64", "identity").order(context(), defaults).first())
    }
}
