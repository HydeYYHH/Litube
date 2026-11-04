package com.hhst.youtubelite.search

import androidx.lifecycle.SavedStateHandle
import com.hhst.youtubelite.core.AppNotice
import com.hhst.youtubelite.search.domain.*
import com.hhst.youtubelite.search.presentation.SearchResultsViewModel
import com.hhst.youtubelite.watch.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchActionOwnershipTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun anExistingChallengeSurvivesANormalRetryButNotAnAccountSwitch() = runTest {
        var reads = 0
        val challenge = "https://www.google.com/sorry/index?q=old-account"
        val repo = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters): SearchPage {
                if (++reads == 1) throw com.hhst.youtubelite.data.media.MediaException(
                    com.liskovsoft.youtubeapi.videoinfo.V2.RequestError(
                        com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure.VERIFICATION,
                        "traffic_challenge", detail = challenge))
                return SearchPage(query, emptyList())
            }
        }
        val vm = SearchResultsViewModel(repo, SavedStateHandle(), "query", signedIn = true)
        vm.updateSession(true, 7)
        advanceUntilIdle()
        assertEquals(challenge, vm.uiState.value.verification?.challengeUrl)
        vm.onAction(SearchAction.Retry)
        advanceUntilIdle()
        assertEquals(challenge, vm.uiState.value.verification?.challengeUrl)
        vm.updateSession(true, 8)
        advanceUntilIdle()
        assertNull(vm.uiState.value.verification)
    }

    @Test fun anAlreadyResumedOldSubscriptionFailureCannotPromptTheNewAccountToSignIn() = runTest {
        lateinit var response: CancellableContinuation<Unit>
        val actions = object : VideoActions {
            override suspend fun rate(videoId: String, rating: VideoRating, previous: VideoRating) = Unit
            override suspend fun subscribe(channelId: String, subscribed: Boolean) {
                suspendCancellableCoroutine<Unit> { response = it }
            }
        }
        val repo = object : SearchRepository {
            override suspend fun search(query: String, filters: SearchFilters) = SearchPage(query, emptyList())
        }
        val vm = SearchResultsViewModel(repo, SavedStateHandle(), "query", signedIn = true, videoActions = actions)
        val notices = mutableListOf<AppNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.actionNotices.toList(notices) }
        vm.updateSession(true, 7)
        runCurrent()
        vm.onAction(SearchAction.Subscribe("channel"))
        runCurrent()
        response.resumeWith(Result.failure(SignInRequiredException()))
        vm.updateSession(true, 8)
        advanceUntilIdle()
        assertTrue(notices.isEmpty())
        assertTrue(vm.uiState.value.subscriptionsPending.isEmpty())
        assertTrue(vm.uiState.value.subscriptions.isEmpty())
    }
}
