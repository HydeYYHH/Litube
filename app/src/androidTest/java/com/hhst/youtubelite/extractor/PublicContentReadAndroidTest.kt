package com.hhst.youtubelite.extractor

import androidx.test.platform.app.InstrumentationRegistry
import com.liskovsoft.youtubeapi.videoinfo.V2.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class PublicContentReadAndroidTest {
    @Before fun networkGate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
    }

    @Test fun modernPlaylistCarriesItsEntriesAndTitle() {
        val result = loadPlaylist("PL2JtvykrieUxaLXkeuXRHb-pJB9XpVWtd", RequestContext())
        assertTrue(result.error.toString(), result.ok)
        val page = result.value!!
        assertTrue(page.title?.isNotBlank() == true)
        assertTrue(page.videos.isNotEmpty())
        assertTrue(page.videos.all { it.title?.isNotBlank() == true })
    }

    @Test fun musicCarouselCarriesTitlesAndAuthors() {
        val result = loadHome(HomeSection.MUSIC, RequestContext())
        assertTrue(result.error.toString(), result.ok)
        val videos = result.value!!.items.filter { it.kind == "video" }
        assertTrue(videos.isNotEmpty())
        assertTrue(videos.all { it.title?.isNotBlank() == true && it.author?.isNotBlank() == true })
    }

    @Test fun seedlessShortsReturnsAnInitialPage() {
        val result = loadHome(HomeSection.SHORTS, RequestContext())
        assertTrue(result.error.toString(), result.ok)
        assertTrue(result.value!!.items.isNotEmpty())
    }
}
