package com.hhst.youtubelite.subscriptions

import com.hhst.youtubelite.subscriptions.data.FixtureSubscriptionsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FixtureSubscriptionsRepositoryTest {
    @Test fun videoCardsHaveTheirSubscribedChannelsAvatars() {
        val channels = FixtureSubscriptionsRepository.channels().associateBy { it.channelId }
        val videos = FixtureSubscriptionsRepository.sampleVideos()

        assertFalse(videos.isEmpty())
        videos.forEach { video ->
            val channel = requireNotNull(channels[video.channelId])
            assertFalse("Missing avatar for ${video.id}", video.avatar.isNullOrBlank())
            assertEquals("Wrong channel avatar for ${video.id}", channel.avatar, video.avatar)
        }
    }
}
