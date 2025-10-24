package com.tripleu.updater.data.ui


sealed class SettingsUiState {
    object Settings : SettingsUiState()
    object About : SettingsUiState()
}
