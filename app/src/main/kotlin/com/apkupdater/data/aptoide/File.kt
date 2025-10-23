package com.apkupdater.data.aptoide

import com.google.gson.annotations.SerializedName

data class File(
    val vername: String = "",
    val vercode: String = "0",
    val path: String = "",
    @SerializedName("filesize") val size: Long = 0L
)
