package com.apkupdater.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apkupdater.data.ui.AuthUiState
import com.apkupdater.repository.AuthRepository
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(
    private val repository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val errorHandler = CoroutineExceptionHandler { _, throwable ->
        _state.value = AuthUiState.Error(throwable.message ?: "Authentication failed.")
    }

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
                repository.signOut()
                _state.value = AuthUiState.Error(message)
            }
        }
    }

    fun retry() {
        _state.value = AuthUiState.Loading
        viewModelScope.launch(errorHandler) {
            ensureAuthenticated()
        }
    }

    fun useAnotherAccount() {
        repository.signOut()
        _state.value = AuthUiState.RequiresLogin()
    }

    fun onGoogleSignInError(message: String) {
        repository.signOut()
        _state.value = AuthUiState.Error(message)
    }

    private suspend fun ensureAuthenticated() {
        val current = repository.currentUser()
        if (current == null) {
            _state.value = AuthUiState.RequiresLogin()
            return
        }
        val email = current.email
        if (email.isNullOrBlank()) {
            repository.signOut()
            _state.value = AuthUiState.RequiresLogin("We were unable to read the email for the signed in account. Please try again.")
            return
        }
        evaluateUser(email, current.displayName)
    }

    private suspend fun evaluateUser(email: String, displayName: String?) {
        if (repository.isEmailBlocked(email)) {
            repository.signOut()
            _state.value = AuthUiState.Blocked(email)
            return
        }
        repository.observeBlocked(email) {
            viewModelScope.launch(errorHandler) {
                repository.signOut()
                _state.value = AuthUiState.Blocked(email)
            }
        }
        _state.value = AuthUiState.Authenticated(email, displayName)
    }

    override fun onCleared() {
        repository.clearBlockObserver()
        super.onCleared()
    }
}
