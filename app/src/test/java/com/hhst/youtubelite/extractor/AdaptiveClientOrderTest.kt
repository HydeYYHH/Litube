package com.hhst.youtubelite.extractor

import org.junit.Assert.*
import org.junit.Test

class AdaptiveClientOrderTest {
    @Test fun refusalIsScopedToItsVideoAndIdentity() {
        val policy = AdaptiveClientOrder { 0L }
        policy.mediaForbidden("video", "identity", StreamClient.WEB_SAFARI)
        assertEquals(setOf(StreamClient.WEB_SAFARI), policy.refusedFor("video", "identity"))
        assertEquals(emptySet<StreamClient>(), policy.refusedFor("other-video", "identity"))
        assertEquals(emptySet<StreamClient>(), policy.refusedFor("video", "other-identity"))
    }

    @Test fun refusalsAccumulateAndExpireTogether() {
        var now = 0L
        val policy = AdaptiveClientOrder { now }
        policy.mediaForbidden("video", "identity", StreamClient.WEB_SAFARI)
        policy.mediaForbidden("video", "identity", StreamClient.WEB)
        assertEquals(setOf(StreamClient.WEB_SAFARI, StreamClient.WEB), policy.refusedFor("video", "identity"))
        now = 30_000
        assertEquals(emptySet<StreamClient>(), policy.refusedFor("video", "identity"))
    }

    @Test fun boundedRefusalMemoryEvictsOldestEvidence() {
        val policy = AdaptiveClientOrder { 0L }
        repeat(65) { policy.mediaForbidden("video-$it", "identity", StreamClient.WEB_SAFARI) }
        assertEquals(emptySet<StreamClient>(), policy.refusedFor("video-0", "identity"))
        assertEquals(setOf(StreamClient.WEB_SAFARI), policy.refusedFor("video-64", "identity"))
    }
}
