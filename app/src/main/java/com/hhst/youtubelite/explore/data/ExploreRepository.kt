package com.hhst.youtubelite.explore.data

import com.hhst.youtubelite.explore.domain.ExploreCategory
import com.hhst.youtubelite.explore.domain.ExplorePage
import com.hhst.youtubelite.explore.domain.ExploreSection

interface ExploreRepository {
    suspend fun explore(category: ExploreCategory): ExplorePage
    /** The next page of a category; [key] is the page cursor the last page returned. */
    suspend fun exploreNext(category: ExploreCategory, key: String): ExplorePage =
        throw java.io.IOException("No continuation for this repository")
    /** The next items of one shelf; [key] is that shelf's own cursor, never a page cursor. */
    suspend fun exploreShelf(category: ExploreCategory, key: String): ExploreSection =
        throw java.io.IOException("No continuation for this repository")
}
