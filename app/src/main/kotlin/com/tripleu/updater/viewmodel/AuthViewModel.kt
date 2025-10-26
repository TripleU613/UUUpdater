package com.tripleu.updater.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tripleu.updater.data.ui.AuthUiState
import com.tripleu.updater.repository.AuthRepository
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

class AuthViewModel(
    private val repository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val errorHandler = CoroutineExceptionHandler { _, throwable ->
        _state.value = AuthUiState.Error(throwable.message ?: "Authentication failed.")
    }

    private val randomSource = Random(System.nanoTime())
    private var verifierJob: Job? = null
    private var activeEmail: String? = null

    init {
        viewModelScope.launch(errorHandler) {
            ensureAuthenticated()
        }
    }

    fun onGoogleIdTokenReceived(idToken: String?, errorMessage: String) {
        if (idToken.isNullOrBlank()) {
            _state.value = AuthUiState.Error(errorMessage)
            return
        }
        _state.value = AuthUiState.Loading
        stopSessionVerifier()
        viewModelScope.launch(errorHandler) {
            try {
                val user = repository.signInWithGoogle(idToken)
                val email = user.email
                if (email.isNullOrBlank()) {
                    repository.signOut()
                    _state.value = AuthUiState.Error("Google account has no email address associated.")
                } else {
                    evaluateUser(email, user.displayName)
                }
            } catch (error: Exception) {
                val message = when (error) {
                    is FirebaseAuthInvalidUserException,
                    is FirebaseAuthInvalidCredentialsException ->
                        errorMessage
                    else -> error.message ?: errorMessage
                }
                stopSessionVerifier()
                repository.signOut()
                _state.value = AuthUiState.Error(message)
            }
        }
    }

    fun retry() {
        _state.value = AuthUiState.Loading
        stopSessionVerifier()
        viewModelScope.launch(errorHandler) {
            ensureAuthenticated()
        }
    }

    fun useAnotherAccount() {
        stopSessionVerifier()
        repository.signOut()
        _state.value = AuthUiState.RequiresLogin()
    }

    fun onGoogleSignInError(message: String) {
        stopSessionVerifier()
        repository.signOut()
        _state.value = AuthUiState.Error(message)
    }

    private suspend fun ensureAuthenticated() {
        val current = repository.currentUser()
        if (current == null) {
            stopSessionVerifier()
            _state.value = AuthUiState.RequiresLogin()
            return
        }
        val email = current.email
        if (email.isNullOrBlank()) {
            stopSessionVerifier()
            repository.signOut()
            _state.value = AuthUiState.RequiresLogin("We were unable to read the email for the signed in account. Please try again.")
            return
        }
        evaluateUser(email, current.displayName)
    }

    private suspend fun evaluateUser(email: String, displayName: String?) {
        if (repository.isEmailBlocked(email)) {
            stopSessionVerifier()
            repository.signOut()
            _state.value = AuthUiState.Blocked(email)
            return
        }
        repository.observeBlocked(email) {
            viewModelScope.launch(errorHandler) {
                stopSessionVerifier()
                repository.signOut()
                _state.value = AuthUiState.Blocked(email)
            }
        }
        _state.value = AuthUiState.Authenticated(email, displayName)
        startSessionVerifier(email)
    }

    private fun startSessionVerifier(email: String) {
        activeEmail = email
        verifierJob?.cancel()
        verifierJob = viewModelScope.launch(errorHandler) {
            while (isActive) {
                val spread = if (SESSION_CHECK_SPREAD_MS > 0L) randomSource.nextLong(SESSION_CHECK_SPREAD_MS + 1) else 0L
                val delayWindow = SESSION_CHECK_MIN_DELAY_MS + spread
                delay(delayWindow)
                if (!isActive) break
                val shouldVerify = randomSource.nextInt(100) < SESSION_CHECK_PROBABILITY
                if (shouldVerify) {
                    val stillValid = verifyCurrentSession()
                    if (!stillValid) break
                }
            }
        }
    }

    private suspend fun verifyCurrentSession(): Boolean {
        val expectedEmail = activeEmail
        val current = repository.currentUser()
        if (expectedEmail.isNullOrBlank() || current == null) {
            invalidateSession()
            return false
        }
        val actualEmail = current.email
        if (actualEmail.isNullOrBlank() || !actualEmail.equals(expectedEmail, ignoreCase = true)) {
            invalidateSession()
            return false
        }
        if (!current.isEmailVerified) {
            invalidateSession()
            return false
        }
        return true
    }

    private fun invalidateSession() {
        repository.signOut()
        stopSessionVerifier()
        _state.value = AuthUiState.Error(USER_NOT_VERIFIED_MESSAGE)
    }

    private fun stopSessionVerifier() {
        verifierJob?.cancel()
        verifierJob = null
        activeEmail = null
    }

    override fun onCleared() {
        repository.clearBlockObserver()
        stopSessionVerifier()
        super.onCleared()
    }

    private companion object {
        private const val USER_NOT_VERIFIED_MESSAGE = "User session verification failed. Please sign in again."
        private const val SESSION_CHECK_MIN_DELAY_MS = 45_000L
        private const val SESSION_CHECK_MAX_DELAY_MS = 120_000L
        private const val SESSION_CHECK_PROBABILITY = 65
        private const val SESSION_CHECK_SPREAD_MS = SESSION_CHECK_MAX_DELAY_MS - SESSION_CHECK_MIN_DELAY_MS
    }
}
