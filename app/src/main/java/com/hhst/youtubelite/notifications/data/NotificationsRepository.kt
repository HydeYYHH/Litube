package com.hhst.youtubelite.notifications.data

import com.hhst.youtubelite.notifications.domain.NotificationsPage

interface NotificationsRepository {
    suspend fun notifications(): NotificationsPage
}
