package com.tripleu.updater.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.tripleu.updater.prefs.Prefs
import com.tripleu.updater.transform.toAppInstalled
import com.tripleu.updater.util.orFalse
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow


class AppsRepository(
	private val context: Context,
	private val prefs: Prefs
) {

	suspend fun getApps() = flow {
		val pm = context.packageManager
		val ignoreSystem = excludeSystem()
		val ignoreDisabled = excludeDisabled()
		val ignoreStore = excludeStore()
		val ignored = ignoredApps()

		val apps = pm
			.getInstalledPackages(PackageManager.MATCH_ALL + getSignatureFlag())
			.asSequence()
			.mapNotNull { packageInfo ->
				val info = packageInfo.applicationInfo ?: return@mapNotNull null

				if (ignoreSystem && info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
				if (ignoreSystem && info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) return@mapNotNull null
				if (ignoreDisabled && info.enabled.not()) return@mapNotNull null
				if (ignoreStore && isAppStore(getInstallerPackageName(packageInfo.packageName))) return@mapNotNull null

				packageInfo.toAppInstalled(context, ignored)
			}
			.sortedBy { it.name }
			.sortedBy { it.ignored }
			.toList()
		emit(Result.success(apps))
	}.catch {
		Log.e("AppsRepository", "Error getting apps.", it)
		emit(Result.failure(it))
	}

	private fun excludeSystem() = prefs.excludeSystem.get()
	private fun excludeDisabled() = prefs.excludeDisabled.get()
	private fun excludeStore() = prefs.excludeStore.get()
	private fun ignoredApps() = prefs.ignoredApps.get()

	@Suppress("DEPRECATION")
	private fun getSignatureFlag(): Int {
		return if (Build.VERSION.SDK_INT >= 28) {
			PackageManager.GET_SIGNING_CERTIFICATES
		} else {
			PackageManager.GET_SIGNATURES
		}
	}

	@Suppress("DEPRECATION")
	private fun getInstallerPackageName(packageName: String): String {
		return if (Build.VERSION.SDK_INT < 30) {
			runCatching { context.packageManager.getInstallerPackageName(packageName) }
				.getOrNull()
				.orEmpty()
		} else {
			runCatching { context.packageManager.getInstallSourceInfo(packageName).installingPackageName }
				.getOrNull()
				.orEmpty()
		}
	}

	// Checks if Play Store or Amazon Store
	private fun isAppStore(name: String?) = name?.contains("com.android.vending").orFalse()
		|| name?.contains("com.amazon").orFalse()

}
