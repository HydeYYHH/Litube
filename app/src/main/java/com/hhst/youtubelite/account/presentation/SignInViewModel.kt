package com.hhst.youtubelite.account.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hhst.youtubelite.account.data.SignInService
import com.hhst.youtubelite.account.domain.SignInAction
import com.hhst.youtubelite.account.domain.SignInUiState
import com.hhst.youtubelite.data.media.MediaException
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SignInViewModel(private val service: SignInService) : ViewModel() {
    private val _uiState = MutableStateFlow<SignInUiState>(SignInUiState.Loading)
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    init {
        start()
    }

    fun onAction(action: SignInAction) {
        when (action) {
            SignInAction.Retry -> start()
        }
    }

    private fun start() {
        job?.cancel()
        _uiState.value = SignInUiState.Loading
        job = viewModelScope.launch {
            try {
                val code = service.loadCode()
                _uiState.value = SignInUiState.Ready(code.userCode, code.verificationUrl)
                val token = service.poll(code)
                _uiState.value = SignInUiState.Completing
                service.complete(token)
                _uiState.value = SignInUiState.Success
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.value = SignInUiState.Error(messageFor(error))
            }
        }
    }

    private fun messageFor(error: Exception): String {
        val failure = (error as? MediaException)?.failure
        return when {
            failure?.code == "expired_token" -> "The sign-in code expired. Get a new one."
            failure?.code == "access_denied" -> "Sign-in was declined in the browser."
            failure?.kind == RequestFailure.TRANSPORT -> "No connection. Check the network and try again."
            failure?.kind == RequestFailure.AUTH -> "YouTube refused the sign-in. Try again."
            else -> "Sign-in failed. Try again."
        }
    }
}
