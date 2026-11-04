package com.hhst.youtubelite.extractor

import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerClient
import com.liskovsoft.youtubeapi.videoinfo.V2.playerQuery
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaClientPostureTest {
    @Test fun fixedMediaIdentitiesExactlyMatchThePlayerRequest() {
        listOf(StreamClient.VISIONOS to PlayerClient.VISIONOS,
            StreamClient.WEB_SAFARI to PlayerClient.WEB_SAFARI,
            StreamClient.WEB_EMBED to PlayerClient.WEB_EMBED,
            StreamClient.TV to PlayerClient.TV).forEach { (media, player) ->
            assertEquals(media.name, playerQuery(player, "abcdefghijk", null).headers["User-Agent"], media.fixedUserAgent)
        }
    }
}
