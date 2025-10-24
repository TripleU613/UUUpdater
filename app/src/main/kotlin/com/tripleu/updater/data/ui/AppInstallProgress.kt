package com.tripleu.updater.data.ui

data class AppInstallProgress(
    val id: Int,
    val progress: Long? = null,
    val total: Long? = null,
    val stage: InstallStage? = null
)
