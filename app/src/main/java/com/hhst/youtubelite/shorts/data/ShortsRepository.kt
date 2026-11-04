package com.hhst.youtubelite.shorts.data

import com.hhst.youtubelite.shorts.domain.ShortsPage

interface ShortsRepository {
    suspend fun shorts(): ShortsPage

    suspend fun shortsNext(cursor: String): ShortsPage = ShortsPage(emptyList())

    /** Full metadata for one item; feeds with complete rows (fixtures) return null. */
    suspend fun hydrate(itemId: String): com.hhst.youtubelite.shorts.domain.ShortDetail? = null
}
