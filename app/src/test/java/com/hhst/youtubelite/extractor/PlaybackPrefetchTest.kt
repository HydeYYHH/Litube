package com.hhst.youtubelite.extractor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPrefetchTest {
    private val id = "abcdefghijk"
    private val demand = PlaybackDemand(0, null, false, setOf("avc", "vp09"))

    private fun cache() = FakeCache().apply {
        putMetadata(id, Metadata())
        putStream(id, Stream())
        putChapters(id, ChapterList())
    }

    @Test fun playbackClaimsThePreparedRequestWithTheSameDemand() = runTest {
        val extractor = Extractor(cache(), this)
        val preparation = extractor.prefetch(id, demand)
        val playback = extractor.extract(id, demand = demand)
        assertSame(preparation.extraction.stream, playback.stream)
        preparation.cancel()
        assertSame(playback.stream, extractor.extract(id, demand = demand).stream)
        playback.stream.await()
    }

    @Test fun cancellingAnUnclaimedPreparationAllowsAFreshRequest() = runTest {
        val extractor = Extractor(cache(), this)
        val preparation = extractor.prefetch(id, demand)
        preparation.cancel()
        preparation.cancel()
        val playback = extractor.extract(id, demand = demand)
        assertNotSame(preparation.extraction.stream, playback.stream)
        playback.stream.await()
    }

    @Test fun cancellingOnePreparationDoesNotCancelAnotherOwner() = runTest {
        val extractor = Extractor(cache(), this)
        val first = extractor.prefetch(id, demand)
        val second = extractor.prefetch(id, demand)
        assertSame(first.extraction.stream, second.extraction.stream)
        first.cancel()
        val playback = extractor.extract(id, demand = demand)
        assertSame(second.extraction.stream, playback.stream)
        second.cancel()
        playback.stream.await()
    }

    @Test fun differentTrackDemandsRemainSeparate() = runTest {
        val extractor = Extractor(cache(), this)
        val preparation = extractor.prefetch(id, demand)
        val playback = extractor.extract(id, demand = demand.copy(height = 1080))
        assertNotSame(preparation.extraction.stream, playback.stream)
        preparation.cancel()
        playback.stream.await()
    }

    @Test fun cancellingAPrefetchDoesNotDiscardTheSelectedPreparation() = runTest {
        val extractor = Extractor(cache(), this)
        extractor.preparePlayback(id, demand)
        val preparation = extractor.prefetch(id, demand)
        preparation.cancel()
        val playback = extractor.extract(id, demand = demand)
        assertSame(preparation.extraction.stream, playback.stream)
        playback.stream.await()
    }

    @Test fun invalidatingTheSelectedPreparationStartsANewPlaybackRequest() = runTest {
        val cache = cache()
        val extractor = Extractor(cache, this)
        extractor.preparePlayback(id, demand)
        val preparation = extractor.prefetch(id, demand)

        // Invalidation must fence the separately retained selection; run it before the preparation settles, as a download refresh would.
        extractor.invalidateStream(id)
        cache.putStream(id, Stream())
        val playback = extractor.extract(id, demand = demand)

        assertNotSame(preparation.extraction.stream, playback.stream)
        preparation.cancel()
        playback.stream.await()
    }

    @Test fun selectingAnotherCardDoesNotCancelAnOwnedPrefetch() = runTest {
        val secondId = "bcdefghijkl"
        val cache = cache().apply {
            putMetadata(secondId, Metadata())
            putStream(secondId, Stream())
            putChapters(secondId, ChapterList())
        }
        val extractor = Extractor(cache, this)
        extractor.preparePlayback(id, demand)
        val preparation = extractor.prefetch(id, demand)
        extractor.preparePlayback(secondId, demand)
        val playback = extractor.extract(id, demand = demand)
        assertSame(preparation.extraction.stream, playback.stream)
        preparation.cancel()
        playback.stream.await()
    }
}
