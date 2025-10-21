package com.apkupdater.data.ui

import android.net.Uri
import android.util.Log

data class AppUpdate(
	val name: String,
	val packageName: String,
	val version: String,
	val oldVersion: String,
	val versionCode: Long,
	val oldVersionCode: Long,
	val source: Source,
	val iconUri: Uri = Uri.EMPTY,
	val link: Link = Link.Empty,
	val whatsNew: String = "",
	val isInstalling: Boolean = false,
	val total: Long = 0L,
	val progress: Long = 0L,
	val id: Int = "${source.name}.$packageName.$versionCode.$version".hashCode()
)

fun List<AppUpdate>.indexOf(id: Int) = indexOfFirst { it.id == id }

fun MutableList<AppUpdate>.setIsInstalling(id: Int, b: Boolean): List<AppUpdate> {
	val index = this.indexOf(id)
	if (index != -1) {
		this[index] = this[index].copy(isInstalling = b)
	}
	return this
}

fun MutableList<AppUpdate>.removeId(id: Int): List<AppUpdate> {
	val index = this.indexOf(id)
	if (index != -1) this.removeAt(index)
	return this
}

fun MutableList<AppUpdate>.setProgress(progress: AppInstallProgress): MutableList<AppUpdate> {
	val index = this.indexOf(progress.id)
	Log.d("AppUpdate", "setProgress: id=${progress.id}, index=$index, progress=${progress.progress}, total=${progress.total}")
	if (index != -1) {
		val app = this[index]
		Log.d("AppUpdate", "Found app: ${app.name}, current progress=${app.progress}, current total=${app.total}")
		progress.progress?.let {
			this[index] = this[index].copy(progress = it)
			Log.d("AppUpdate", "Updated progress to $it")
		}
		progress.total?.let {
			this[index] = this[index].copy(total = it)
			Log.d("AppUpdate", "Updated total to $it")
		}
		Log.d("AppUpdate", "After update: progress=${this[index].progress}, total=${this[index].total}")
	} else {
		Log.w("AppUpdate", "App not found for id=${progress.id}")
	}
	return this
}
