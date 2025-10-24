package com.tripleu.updater.data.apkpure


data class GetAppUpdateResponse(
    val retcode: Int,
    val app_update_response: List<AppUpdateResponse>
)
