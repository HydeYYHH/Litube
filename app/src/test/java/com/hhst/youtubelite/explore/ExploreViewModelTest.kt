package com.hhst.youtubelite.explore

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.explore.data.ExploreRepository
import com.hhst.youtubelite.explore.data.ExploreScenario
import com.hhst.youtubelite.explore.data.FixtureExploreRepository
import com.hhst.youtubelite.explore.domain.*
import com.hhst.youtubelite.shorts.data.FixtureShortsRepository
import com.hhst.youtubelite.shorts.domain.ShortsContent
import com.hhst.youtubelite.shorts.presentation.ShortsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.hhst.youtubelite.data.media.MediaAvatarDirectory
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.data.media.MemoryAuthStore
import com.hhst.youtubelite.subscriptions.data.FixtureSubscriptionsRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun aCompletedAvatarUpdatesARetainedCategory() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        MediaSession.initialize(MemoryAuthStore())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body("""{"channelMetadataRenderer":{"externalId":"UCabc","title":"Author","avatar":{"thumbnails":[{"url":"https://yt3/avatar.png"}]}}}""".toResponseBody()).build()
        }.build()
        val directory = MediaAvatarDirectory(MediaClient(http))
        try {
            runTest {
                val video = FixtureSubscriptionsRepository.sampleVideos().first().copy(channelId = "UCabc", avatar = null)
                val repository = object : ExploreRepository {
                    override suspend fun explore(category: ExploreCategory) = ExplorePage(category,
                        if (category == ExploreCategory.Music) listOf(video) else emptyList())
                }
                val vm = ExploreViewModel(repository, ExploreScenario.Content, avatars = directory)
                runCurrent()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                vm.onAction(ExploreAction.SelectCategory(ExploreCategory.Gaming))
                runCurrent()
                release.countDown()
                withContext(Dispatchers.Default) { withTimeout(5_000) { directory.avatar("UCabc") } }
                runCurrent()
                vm.onAction(ExploreAction.SelectCategory(ExploreCategory.Music))
                runCurrent()
                // The directory cache can complete before the UI subscriber is resumed.
                val completed = withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        vm.uiState.first { state ->
                            (state.content as? ExploreContent.Ready)?.page?.videos?.firstOrNull()?.avatar != null
                        }
                    }
                }
                val page = (completed.content as ExploreContent.Ready).page
                assertEquals("https://yt3/avatar.png", page.videos.first().avatar)
            }
        } finally {
            release.countDown()
            directory.dispose()
            MediaSession.initialize(MemoryAuthStore())
        }
    }

    @Test fun musicIsTheDefaultContainerAndCategoriesLoadTheirOwnPages() = runTest {
        val vm = ExploreViewModel(FixtureExploreRepository(), ExploreScenario.Content)
        advanceUntilIdle()
        val first = vm.uiState.value.content as ExploreContent.Ready
        assertEquals(ExploreCategory.Music, first.page.category)
        assertTrue(first.page.videos.all { it.id.startsWith("music-") })
        vm.onAction(ExploreAction.SelectCategory(ExploreCategory.Sports))
        advanceUntilIdle()
        val sports = vm.uiState.value.content as ExploreContent.Ready
        assertEquals(ExploreCategory.Sports, sports.page.category)
        assertTrue(sports.page.videos.all { it.id.startsWith("sports-") })
    }

    @Test fun videoAndChannelOpeningsStayTyped() = runTest {
        val vm = ExploreViewModel(FixtureExploreRepository(), ExploreScenario.Content)
        advanceUntilIdle()
        val requests = mutableListOf<ExploreNavigationRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationRequests.collect { requests += it } }
        vm.onAction(ExploreAction.OpenVideo("music-bunny"))
        vm.onAction(ExploreAction.OpenChannel("blender"))
        advanceUntilIdle()
        assertEquals(listOf(ExploreNavigationRequest.Watch("music-bunny"), ExploreNavigationRequest.Channel("blender")), requests)
    }

    @Test fun failureScenarioStaysFailedUntilRetry() = runTest {
        var fail = true
        val vm = ExploreViewModel(object : ExploreRepository {
            override suspend fun explore(category: ExploreCategory): ExplorePage {
                if (fail) throw java.io.IOException("offline")
                return ExplorePage(category, FixtureExploreRepository.sample(category))
            }
        }, ExploreScenario.Content)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is ExploreContent.Failed)
        fail = false
        vm.onAction(ExploreAction.Retry)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.content is ExploreContent.Ready)
    }

    private fun video(id: String) = com.hhst.youtubelite.home.domain.FeedItem.Video(
        id, "Title $id", "Author", "1K views", "art", "1:00", "ch")
    private fun videosOf(category: ExploreCategory, vararg ids: String) =
        ids.map { video("${category.name.lowercase()}-$it") }

    /** Scripted pages and shelves; every read is recorded by the cursor that asked for it. */
    private class PagedRepository(
        private val first: (ExploreCategory) -> ExplorePage,
    ) : ExploreRepository {
        val exploreCalls = mutableListOf<ExploreCategory>()
        val nextKeys = mutableListOf<String>()
        val shelfKeys = mutableListOf<String>()
        var nextPages = ArrayDeque<suspend (ExploreCategory) -> ExplorePage>()
        var failNextAt = mutableSetOf<Int>()
        var shelfResults = mutableMapOf<String, ExploreSection>()
        var failShelfKeys = mutableSetOf<String>()
        override suspend fun explore(category: ExploreCategory): ExplorePage {
            exploreCalls += category
            return first(category)
        }
        override suspend fun exploreNext(category: ExploreCategory, key: String): ExplorePage {
            nextKeys += key
            if (nextKeys.size in failNextAt) throw java.io.IOException("offline")
            val build = nextPages.removeFirstOrNull()
            return build?.invoke(category) ?: ExplorePage(category, emptyList(), emptyList(), null)
        }
        override suspend fun exploreShelf(category: ExploreCategory, key: String): ExploreSection {
            shelfKeys += key
            if (key in failShelfKeys) throw java.io.IOException("offline")
            return shelfResults[key] ?: ExploreSection(null, emptyList(), null)
        }
    }

    @Test fun theTailAppendsOnePagePerSignalAndEnds() = runTest {
        val repository = PagedRepository({ cat -> ExplorePage(cat, videosOf(cat, "a", "b"), emptyList(), "k1") })
        repository.nextPages += { cat -> ExplorePage(cat, videosOf(cat, "c"), emptyList(), null) }
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        vm.onAction(ExploreAction.LoadMore)
        advanceUntilIdle()
        val ready = vm.uiState.value.content as ExploreContent.Ready
        assertEquals(listOf("music-a", "music-b", "music-c"), ready.page.videos.map { it.id })
        assertNull(ready.page.nextKey)
        assertEquals(listOf("k1"), repository.nextKeys)
        vm.onAction(ExploreAction.LoadMore)
        advanceUntilIdle()
        assertEquals(1, repository.nextKeys.size)
    }

    @Test fun aFailedTailKeepsThePageAndRetriesOnlyTheAppend() = runTest {
        val repository = PagedRepository({ cat -> ExplorePage(cat, videosOf(cat, "a"), emptyList(), "k1") })
        repository.failNextAt = mutableSetOf(1)
        repository.nextPages += { cat -> ExplorePage(cat, videosOf(cat, "c"), emptyList(), null) }
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        vm.onAction(ExploreAction.LoadMore)
        advanceUntilIdle()
        var ready = vm.uiState.value.content as ExploreContent.Ready
        assertEquals(com.hhst.youtubelite.home.domain.AppendState.Failed, ready.append)
        assertEquals(listOf("music-a"), ready.page.videos.map { it.id })
        vm.onAction(ExploreAction.LoadMore)
        advanceUntilIdle()
        ready = vm.uiState.value.content as ExploreContent.Ready
        assertEquals(listOf("music-a", "music-c"), ready.page.videos.map { it.id })
        assertEquals(listOf("k1", "k1"), repository.nextKeys)
        assertEquals(1, repository.exploreCalls.size)
    }

    @Test fun aRepeatedCursorEndsTheCategory() = runTest {
        val repository = PagedRepository({ cat -> ExplorePage(cat, videosOf(cat, "a"), emptyList(), "k1") })
        repository.nextPages += { cat -> ExplorePage(cat, videosOf(cat, "c"), emptyList(), "k1") }
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        vm.onAction(ExploreAction.LoadMore)
        advanceUntilIdle()
        val ready = vm.uiState.value.content as ExploreContent.Ready
        assertNull(ready.page.nextKey)
        assertEquals(listOf("music-a", "music-c"), ready.page.videos.map { it.id })
        vm.onAction(ExploreAction.LoadMore)
        advanceUntilIdle()
        assertEquals(1, repository.nextKeys.size)
    }

    @Test fun shelfAppendsStayScopedToTheirShelf() = runTest {
        val repository = PagedRepository({ cat ->
            ExplorePage(cat, emptyList(), listOf(
                ExploreSection("S0", videosOf(cat, "a"), "cursor-0"),
                ExploreSection("S1", videosOf(cat, "b"), "cursor-1")), null)
        })
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        repository.shelfResults["cursor-0"] = ExploreSection(null, videosOf(ExploreCategory.Music, "a2"), null)
        vm.onAction(ExploreAction.LoadShelf(0))
        advanceUntilIdle()
        val ready = vm.uiState.value.content as ExploreContent.Ready
        assertEquals(listOf("music-a", "music-a2"), ready.page.sections[0].videos.map { it.id })
        assertNull(ready.page.sections[0].nextKey)
        assertEquals(listOf("music-b"), ready.page.sections[1].videos.map { it.id })
        assertEquals("cursor-1", ready.page.sections[1].nextKey)
        assertEquals(listOf("cursor-0"), repository.shelfKeys)
    }

    @Test fun aShelfFailureStaysOnTheShelfAndRetries() = runTest {
        val repository = PagedRepository({ cat ->
            ExplorePage(cat, emptyList(), listOf(
                ExploreSection("S0", videosOf(cat, "a"), "cursor-0"),
                ExploreSection("S1", videosOf(cat, "b"), "cursor-1")), null)
        })
        repository.failShelfKeys = mutableSetOf("cursor-1")
        repository.shelfResults["cursor-1"] = ExploreSection(null, videosOf(ExploreCategory.Music, "b2"), null)
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        vm.onAction(ExploreAction.LoadShelf(1))
        advanceUntilIdle()
        var ready = vm.uiState.value.content as ExploreContent.Ready
        assertEquals(com.hhst.youtubelite.home.domain.AppendState.Failed, ready.page.sections[1].append)
        assertEquals(com.hhst.youtubelite.home.domain.AppendState.Idle, ready.page.sections[0].append)
        repository.failShelfKeys.clear()
        vm.onAction(ExploreAction.LoadShelf(1))
        advanceUntilIdle()
        ready = vm.uiState.value.content as ExploreContent.Ready
        assertEquals(listOf("music-b", "music-b2"), ready.page.sections[1].videos.map { it.id })
        assertNull(ready.page.sections[1].nextKey)
    }

    @Test fun switchingCategoriesKeepsLoadedPagesAndReselectNeverReloads() = runTest {
        val repository = PagedRepository({ cat -> ExplorePage(cat, videosOf(cat, "a"), emptyList(), null) })
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        assertEquals(listOf(ExploreCategory.Music), repository.exploreCalls)
        vm.onAction(ExploreAction.SelectCategory(ExploreCategory.Sports))
        advanceUntilIdle()
        assertEquals(listOf(ExploreCategory.Music, ExploreCategory.Sports), repository.exploreCalls)
        vm.onAction(ExploreAction.SelectCategory(ExploreCategory.Music))
        advanceUntilIdle()
        assertEquals(2, repository.exploreCalls.size)
        val ready = vm.uiState.value.content as ExploreContent.Ready
        assertEquals(listOf("music-a"), ready.page.videos.map { it.id })
        vm.onAction(ExploreAction.SelectCategory(ExploreCategory.Music))
        advanceUntilIdle()
        assertEquals(2, repository.exploreCalls.size)
    }

    @Test fun anIdentityChangeDropsRetainedPages() = runTest {
        val repository = PagedRepository({ cat -> ExplorePage(cat, videosOf(cat, "a"), emptyList(), null) })
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        vm.onAction(ExploreAction.SelectCategory(ExploreCategory.Sports))
        advanceUntilIdle()
        vm.updateSession(99)
        advanceUntilIdle()
        assertEquals(listOf(ExploreCategory.Music, ExploreCategory.Sports, ExploreCategory.Sports), repository.exploreCalls)
        vm.onAction(ExploreAction.SelectCategory(ExploreCategory.Music))
        advanceUntilIdle()
        assertEquals(4, repository.exploreCalls.size)
    }

    @Test fun switchingAwayCancelsAnInFlightAppend() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repository = PagedRepository({ cat -> ExplorePage(cat, videosOf(cat, "a"), emptyList(), "k1") })
        repository.nextPages += { cat -> gate.await(); ExplorePage(cat, videosOf(cat, "c"), emptyList(), null) }
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        vm.onAction(ExploreAction.LoadMore)
        runCurrent()
        vm.onAction(ExploreAction.SelectCategory(ExploreCategory.Sports))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(ExploreCategory.Music, ExploreCategory.Sports), repository.exploreCalls)
        vm.onAction(ExploreAction.SelectCategory(ExploreCategory.Music))
        advanceUntilIdle()
        val ready = vm.uiState.value.content as ExploreContent.Ready
        assertEquals(listOf("music-a"), ready.page.videos.map { it.id })
        assertEquals("k1", ready.page.nextKey)
        assertEquals(com.hhst.youtubelite.home.domain.AppendState.Idle, ready.append)
    }
    @Test fun returningToCachedCategoryCancelsThePreviousLoad() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repository = object : ExploreRepository {
            override suspend fun explore(category: ExploreCategory): ExplorePage {
                if (category == ExploreCategory.Gaming) gate.await()
                return ExplorePage(category, videosOf(category, "first"))
            }
        }
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        vm.onAction(ExploreAction.SelectCategory(ExploreCategory.Gaming))
        runCurrent()
        vm.onAction(ExploreAction.SelectCategory(ExploreCategory.Music))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ExploreCategory.Music, (vm.uiState.value.content as ExploreContent.Ready).page.category)
    }

    @Test fun longerCursorCyclesEndTheFeed() = runTest {
        val repository = PagedRepository { cat -> ExplorePage(cat, videosOf(cat, "first"), nextKey = "a") }
        repository.nextPages.add { cat -> ExplorePage(cat, videosOf(cat, "second"), nextKey = "b") }
        repository.nextPages.add { cat -> ExplorePage(cat, videosOf(cat, "third"), nextKey = "a") }
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        repeat(3) { vm.onAction(ExploreAction.LoadMore); advanceUntilIdle() }
        assertEquals(listOf("a", "b"), repository.nextKeys)
        assertNull((vm.uiState.value.content as ExploreContent.Ready).page.nextKey)
    }

    @Test fun aLateShelfCannotOverwriteAnotherCategoryWithTheSameCursor() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repository = object : ExploreRepository {
            override suspend fun explore(category: ExploreCategory) = ExplorePage(category, emptyList(),
                listOf(ExploreSection("Shelf", videosOf(category, "first"), "shared")))
            override suspend fun exploreShelf(category: ExploreCategory, key: String): ExploreSection =
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    gate.await()
                    ExploreSection("Shelf", videosOf(category, "late"), null)
                }
        }
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        vm.onAction(ExploreAction.LoadShelf(0))
        runCurrent()
        vm.onAction(ExploreAction.SelectCategory(ExploreCategory.Gaming))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        val section = (vm.uiState.value.content as ExploreContent.Ready).page.sections.single()
        assertEquals(listOf("gaming-first"), section.videos.map { it.id })
        assertEquals("shared", section.nextKey)
    }

    @Test fun aRepeatedShelfMergesNewItemsInsteadOfDroppingThem() = runTest {
        val repository = PagedRepository { cat -> ExplorePage(cat, emptyList(),
            listOf(ExploreSection("Shelf", videosOf(cat, "first"))), "a") }
        repository.nextPages.add { cat -> ExplorePage(cat, emptyList(),
            listOf(ExploreSection("Shelf", videosOf(cat, "first", "second")))) }
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        vm.onAction(ExploreAction.LoadMore)
        advanceUntilIdle()
        val sections = (vm.uiState.value.content as ExploreContent.Ready).page.sections
        assertEquals(1, sections.size)
        assertEquals(listOf("music-first", "music-second"), sections.single().videos.map { it.id })
    }

    @Test fun emptyIntermediatePagesKeepTheirContinuations() = runTest {
        val repository = PagedRepository { cat -> ExplorePage(cat, videosOf(cat, "first"), nextKey = "a") }
        repository.nextPages.add { cat -> ExplorePage(cat, emptyList(), nextKey = "b") }
        repository.nextPages.add { cat -> ExplorePage(cat, emptyList(), nextKey = "c") }
        repository.nextPages.add { cat -> ExplorePage(cat, videosOf(cat, "last")) }
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        repeat(3) { vm.onAction(ExploreAction.LoadMore); advanceUntilIdle() }
        val page = (vm.uiState.value.content as ExploreContent.Ready).page
        assertEquals(listOf("a", "b", "c"), repository.nextKeys)
        assertEquals(listOf("music-first", "music-last"), page.videos.map { it.id })
        assertNull(page.nextKey)
    }

    @Test fun emptyIntermediateShelfPagesKeepTheirContinuations() = runTest {
        val repository = PagedRepository { cat -> ExplorePage(cat, emptyList(),
            listOf(ExploreSection("Shelf", videosOf(cat, "first"), "a"))) }
        repository.shelfResults["a"] = ExploreSection(null, emptyList(), "b")
        repository.shelfResults["b"] = ExploreSection(null, emptyList(), "c")
        repository.shelfResults["c"] = ExploreSection(null, videosOf(ExploreCategory.Music, "last"))
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        repeat(3) { vm.onAction(ExploreAction.LoadShelf(0)); advanceUntilIdle() }
        val section = (vm.uiState.value.content as ExploreContent.Ready).page.sections.single()
        assertEquals(listOf("a", "b", "c"), repository.shelfKeys)
        assertEquals(listOf("music-first", "music-last"), section.videos.map { it.id })
        assertNull(section.nextKey)
    }

    @Test fun longerShelfCursorCyclesEndWithoutRepeatingRequests() = runTest {
        val repository = PagedRepository { cat -> ExplorePage(cat, emptyList(),
            listOf(ExploreSection("Shelf", videosOf(cat, "first"), "a"))) }
        repository.shelfResults["a"] = ExploreSection(null, emptyList(), "b")
        repository.shelfResults["b"] = ExploreSection(null, emptyList(), "a")
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        repeat(3) { vm.onAction(ExploreAction.LoadShelf(0)); advanceUntilIdle() }
        assertEquals(listOf("a", "b"), repository.shelfKeys)
        assertNull((vm.uiState.value.content as ExploreContent.Ready).page.sections.single().nextKey)
    }

    @Test fun aFlatContinuationRemainsVisibleAfterSectionedContent() = runTest {
        val repository = PagedRepository { cat -> ExplorePage(cat, videosOf(cat, "first"),
            listOf(ExploreSection("Shelf", videosOf(cat, "first"))), "a") }
        repository.nextPages.add { cat -> ExplorePage(cat, videosOf(cat, "last")) }
        val vm = ExploreViewModel(repository, ExploreScenario.Content)
        advanceUntilIdle()
        vm.onAction(ExploreAction.LoadMore)
        advanceUntilIdle()
        assertEquals(listOf("music-first", "music-last"),
            (vm.uiState.value.content as ExploreContent.Ready).page.sections.flatMap { it.videos }.map { it.id })
    }
}
