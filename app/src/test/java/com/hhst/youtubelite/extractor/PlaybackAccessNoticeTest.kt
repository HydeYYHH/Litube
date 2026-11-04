package com.hhst.youtubelite.extractor

import com.hhst.youtubelite.player.PlayerViewModel
import com.hhst.youtubelite.player.engine.PlaybackNotice
import com.hhst.youtubelite.player.engine.PlaybackSnapshot
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackAccessNoticeTest {
    private fun denied(kind: RequestFailure, vararg restrictions: PlayerAccessRestriction?) =
        PlaybackResolutionException(RequestError(kind, "playability_unplayable"),
            PlaybackResolutionDiagnostics("AUTHENTICATED", "fixture", 0,
                restrictions.map { PlayerLoadAttempt(PlayerClient.VISIONOS, "UNPLAYABLE", null, 1, it) }))

    @Test fun aConfirmedFinalPaymentDenialIsAnAccessNotice() {
        assertTrue(denied(RequestFailure.UNSUPPORTED, PlayerAccessRestriction.PAYMENT_REQUIRED, null).paymentRequired)
        assertFalse(denied(RequestFailure.UNSUPPORTED, null).paymentRequired)
    }

    @Test fun loginAndTechnicalFailuresCannotBeReclassifiedAsPayment() {
        assertFalse(denied(RequestFailure.UNSUPPORTED, PlayerAccessRestriction.PAYMENT_REQUIRED,
            PlayerAccessRestriction.LOGIN_REQUIRED).paymentRequired)
        assertFalse(denied(RequestFailure.UNSUPPORTED, PlayerAccessRestriction.LOGIN_REQUIRED).paymentRequired)
        for (kind in listOf(RequestFailure.VERIFICATION, RequestFailure.TRANSPORT, RequestFailure.HTTP, RequestFailure.AUTH)) {
            assertFalse(denied(kind, PlayerAccessRestriction.PAYMENT_REQUIRED).paymentRequired)
        }
    }

    @Test fun aNoticeSettlesTheRequestedVideoWithoutSettlingAnotherVideo() {
        val state = PlaybackSnapshot(videoId = "paidvideo01", notice = PlaybackNotice.PAYMENT_REQUIRED)
        assertTrue(PlayerViewModel.loadSettledFor(state, "paidvideo01"))
        assertFalse(PlayerViewModel.loadSettledFor(state, "othervid001"))
        assertNull(state.error)
        assertNull(state.failureReport)
    }

    @Test fun authenticatedPaymentDenialCanDisambiguateAnAnonymousAgeGate() {
        val attempts = listOf(
            PlayerLoadAttempt(PlayerClient.VISIONOS, "LOGIN_REQUIRED", null, 1, PlayerAccessRestriction.LOGIN_REQUIRED),
            PlayerLoadAttempt(PlayerClient.TV, "UNPLAYABLE", null, 1, PlayerAccessRestriction.PAYMENT_REQUIRED, authenticated = true))
        fun result(attempts: List<PlayerLoadAttempt>) = PlaybackResolutionException(
            RequestError(RequestFailure.UNSUPPORTED, "playability_unplayable"),
            PlaybackResolutionDiagnostics("AUTHENTICATED", "fixture", 0, attempts))
        assertTrue(result(attempts).paymentRequired)
        assertFalse(result(attempts.map { it.copy(authenticated = false) }).paymentRequired)
        assertFalse(result(attempts + PlayerLoadAttempt(PlayerClient.WEB, "OK", null, 1)).paymentRequired)
        assertFalse(result(attempts + PlayerLoadAttempt(PlayerClient.TV, null,
            RequestError(RequestFailure.HTTP, "http_403", status = 403), 1)).paymentRequired)
        assertFalse(result(attempts + attempts.first().copy(authenticated = true)).paymentRequired)
    }
}
