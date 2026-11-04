package com.liskovsoft.youtubeapi.videoinfo.V2

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectVodQueueTest {
    @Test fun directVodPreferenceKeepsAccountFallbackAndLiveOrder() {
        val queues = PlayerClientQueues(PlayerClient.VISIONOS)
        for (signIn in SignIn.entries) {
            val vod = ClientQueueKey(signIn, PlaybackForm.VOD)
            assertEquals(PlayerClient.VISIONOS, queues.order(vod).first())
            assertEquals(PlayerClientQueues.defaultOrder(vod).toSet(), queues.order(vod).toSet())
            val live = ClientQueueKey(signIn, PlaybackForm.LIVE)
            assertEquals(PlayerClientQueues.defaultOrder(live), queues.order(live))
        }
        val signedIn = ClientQueueKey(SignIn.SIGNED_IN, PlaybackForm.VOD)
        queues.commit(signedIn, listOf(PlayerClient.VISIONOS to ClientOutcome.SOFT,
            PlayerClient.WEB_SAFARI to ClientOutcome.PLAYED))
        assertEquals(PlayerClient.WEB_SAFARI, queues.order(signedIn).first())
        assertEquals(PlayerClient.WEB_SAFARI, PlayerClientQueues().order(signedIn).first())
    }
}
