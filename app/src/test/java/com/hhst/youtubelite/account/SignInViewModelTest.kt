package com.hhst.youtubelite.account.presentation

import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.account.data.SignInService
import com.hhst.youtubelite.account.domain.SignInAction
import com.hhst.youtubelite.account.domain.SignInUiState
import com.hhst.youtubelite.data.media.MediaException
import com.liskovsoft.youtubeapi.videoinfo.V2.AccessToken
import com.liskovsoft.youtubeapi.videoinfo.V2.Account
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestError
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import com.liskovsoft.youtubeapi.videoinfo.V2.SignInCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private class FakeSignInService : SignInService {
        val code = SignInCode("device", "ABCD-EFGH", "https://www.youtube.com/activate",
            Long.MAX_VALUE, 1000)
        var codeError: Exception? = null
        var pollError: Exception? = null
        var completeError: Exception? = null
        var pollBlocks = false
        var pollCalls = 0
        var completed: AccessToken? = null

        override suspend fun loadCode(): SignInCode {
            codeError?.let { throw it }
            return code
        }

        override suspend fun poll(code: SignInCode): AccessToken {
            pollCalls++
            pollError?.let { throw it }
            if (pollBlocks) awaitCancellation()
            return AccessToken("access", "refresh", Long.MAX_VALUE)
        }

        override suspend fun complete(token: AccessToken): List<Account> {
            completeError?.let { throw it }
            completed = token
            return emptyList()
        }
    }

    @Test fun codePollAndCommitReachSuccess() = runTest {
        val service = FakeSignInService()
        val vm = SignInViewModel(service)
        advanceUntilIdle()
        assertEquals(SignInUiState.Success, vm.uiState.value)
        assertEquals("access", service.completed?.accessToken)
    }

    @Test fun theReadyStateExposesTheUserCodeWhilePolling() = runTest {
        val service = FakeSignInService().apply { pollBlocks = true }
        val vm = SignInViewModel(service)
        runCurrent()
        val state = vm.uiState.value
        assertTrue(state is SignInUiState.Ready)
        assertEquals("ABCD-EFGH", (state as SignInUiState.Ready).userCode)
        assertEquals("https://www.youtube.com/activate", state.verificationUrl)
        vm.viewModelScope.cancel()
    }

    @Test fun anExpiredCodeExplainsItselfAndRetries() = runTest {
        val service = FakeSignInService().apply {
            pollError = MediaException(RequestError(RequestFailure.AUTH, "expired_token"))
        }
        val vm = SignInViewModel(service)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state is SignInUiState.Error)
        assertTrue((state as SignInUiState.Error).message.contains("expired"))
        service.pollError = null
        vm.onAction(SignInAction.Retry)
        advanceUntilIdle()
        assertEquals(SignInUiState.Success, vm.uiState.value)
        assertEquals(2, service.pollCalls)
    }

    @Test fun aTransportFailureMentionsTheConnection() = runTest {
        val service = FakeSignInService().apply {
            codeError = MediaException(RequestError(RequestFailure.TRANSPORT, "transport_failed"))
        }
        val vm = SignInViewModel(service)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state is SignInUiState.Error)
        assertTrue((state as SignInUiState.Error).message.contains("connection"))
    }

    @Test fun aDeclinedSignInIsReported() = runTest {
        val service = FakeSignInService().apply {
            pollError = MediaException(RequestError(RequestFailure.AUTH, "access_denied"))
        }
        val vm = SignInViewModel(service)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state is SignInUiState.Error)
        assertTrue((state as SignInUiState.Error).message.contains("declined"))
    }
}
