package com.hhst.youtubelite.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoShareTest {
    @Test fun fixtureIdsMapToTheirPublicWatchUrls() {
        assertEquals("https://youtu.be/aqz-KE-bpKQ", VideoShare.urlFor("bunny"))
        assertEquals("https://youtu.be/TLkA0RELQ1g", VideoShare.urlFor("dream"))
        assertEquals("https://youtu.be/eRsGyueVLvQ", VideoShare.urlFor("sintel"))
        assertEquals("https://youtu.be/TLkA0RELQ1g", VideoShare.urlFor("harbour"))
        assertEquals("https://youtu.be/TLkA0RELQ1g", VideoShare.urlFor("short-2"))
    }

    @Test fun canonicalIdsAreAcceptedVerbatim() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ", VideoShare.urlFor("dQw4w9WgXcQ"))
        assertEquals("https://youtu.be/aqz-KE-bpKQ", VideoShare.urlFor("aqz-KE-bpKQ"))
    }

    @Test fun localOrMalformedIdsNeverFabricateALink() {
        assertNull(VideoShare.urlFor(""))
        assertNull(VideoShare.urlFor("unknown"))
        assertNull(VideoShare.urlFor("short"))
        assertNull(VideoShare.urlFor("  bunny  "))
        assertNull(VideoShare.urlFor("https://youtu.be/aqz-KE-bpKQ"))
    }

    @Test fun fixtureShareTargetsFollowTheirArtwork() {
        com.hhst.youtubelite.search.data.FixtureSearchRepository.sampleResults().forEach {
            assertEquals(VideoShare.urlFor(it.artwork), VideoShare.urlFor(it.id))
        }
        com.hhst.youtubelite.home.data.FixtureHomeRepository.samplePage().items
            .filterIsInstance<com.hhst.youtubelite.home.domain.FeedItem.Video>().forEach {
                assertEquals(VideoShare.urlFor(it.artwork), VideoShare.urlFor(it.id))
            }
    }

    @Test fun newFixtureAliasesResolveOnlyKnownMedia() {
        assertEquals(VideoShare.urlFor("bunny"), VideoShare.urlFor("music-bunny"))
        assertEquals(VideoShare.urlFor("dream"), VideoShare.urlFor("gaming-dream"))
        assertEquals(VideoShare.urlFor("sintel"), VideoShare.urlFor("live-2"))
        assertNull(VideoShare.urlFor("music-unknown"))
        assertNull(VideoShare.urlFor("madeup-bunny"))
    }
}
