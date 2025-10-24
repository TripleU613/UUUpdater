package com.tripleu.updater.data.ui

data class AppInstallStatus(
    val success: Boolean,
    val id: Int,
    val snack: Boolean = true,
    val message: String? = null
)
