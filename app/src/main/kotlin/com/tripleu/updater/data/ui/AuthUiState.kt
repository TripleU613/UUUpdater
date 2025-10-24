package com.tripleu.updater.data.ui

sealed class AuthUiState {
    data object Loading : AuthUiState()
    data class RequiresLogin(val message: String? = null) : AuthUiState()
    data class Authenticated(val email: String, val displayName: String?) : AuthUiState()
    data class Blocked(val email: String) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}
