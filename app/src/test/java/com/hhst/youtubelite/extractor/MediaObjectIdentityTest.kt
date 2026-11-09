package com.hhst.youtubelite.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MediaObjectIdentityTest {
    @Test fun equalSizedVideosAndRevisionsNeverShareBytes() {
        val first = "https://edge.googlevideo.com/videoplayback?id=one&lmt=1&itag=137"
        val second = first.replace("id=one", "id=two")
        assertNotEquals(resourceIdentity(first, 137, 1000), resourceIdentity(second, 137, 1000))
        assertNotEquals(resourceIdentity(first, 137, 1000), resourceIdentity(first.replace("lmt=1", "lmt=2"), 137, 1000))
    }

    @Test fun rotatingCredentialsKeepTheSameProvenObject() {
        val first = "https://a.googlevideo.com/videoplayback?id=one&lmt=1&itag=137&sig=old"
        val rotated = first.replace("a.googlevideo", "b.googlevideo").replace("sig=old", "sig=new")
        assertEquals(resourceIdentity(first, 137, 1000), resourceIdentity(rotated, 137, 1000))
    }

    @Test fun missingObjectIdsUseExactUrlsEvenWhenTheSizeIsUnknown() {
        assertNotEquals(resourceIdentity("https://media.test/one", 137, null),
            resourceIdentity("https://media.test/two", 137, null))
    }
}
