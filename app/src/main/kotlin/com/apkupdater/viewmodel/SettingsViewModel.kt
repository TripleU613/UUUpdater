package com.apkupdater.viewmodel

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.apkupdater.data.ui.SettingsUiState
import com.apkupdater.prefs.Prefs
import com.apkupdater.repository.AppsRepository
import com.apkupdater.ui.theme.isDarkTheme
import com.apkupdater.util.Clipboard
import com.apkupdater.util.Themer
import com.apkupdater.util.UpdatesNotification
import com.apkupdater.worker.UpdatesWorker
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch


class SettingsViewModel(
    private val prefs: Prefs,
    private val notification: UpdatesNotification,
    private val workManager: WorkManager,
	private val clipboard: Clipboard,
	private val appsRepository: AppsRepository,
	private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
	private val themer: Themer
) : ViewModel() {

	val state = MutableStateFlow<SettingsUiState>(SettingsUiState.Settings)

	init {
		viewModelScope.launch(Dispatchers.IO) {
			sanitizeInstallPreferences()
		}
	}

	fun setPortraitColumns(n: Int) = prefs.portraitColumns.put(n)
	fun getPortraitColumns() = prefs.portraitColumns.get()
	fun setLandscapeColumns(n: Int) = prefs.landscapeColumns.put(n)
	fun getLandscapeColumns() = prefs.landscapeColumns.get()
	fun setPlayTextAnimations(b: Boolean) = prefs.playTextAnimations.put(b)
	fun getPlayTextAnimations() = prefs.playTextAnimations.get()
	fun setIgnoreAlpha(b: Boolean) = prefs.ignoreAlpha.put(b)
	fun getIgnoreAlpha() = prefs.ignoreAlpha.get()
	fun setIgnoreBeta(b: Boolean) = prefs.ignoreBeta.put(b)
	fun getIgnoreBeta() = prefs.ignoreBeta.get()
	fun setIgnorePreRelease(b: Boolean) = prefs.ignorePreRelease.put(b)
	fun getIgnorePreRelease() = prefs.ignorePreRelease.get()
	fun getUseSafeStores() = prefs.useSafeStores.get()
	fun setUseSafeStores(b: Boolean) = prefs.useSafeStores.put(b)
	fun getUseApkMirror() = prefs.useApkMirror.get()
	fun setUseApkMirror(b: Boolean) = prefs.useApkMirror.put(b)
	fun getUseFdroid() = prefs.useFdroid.get()
	fun setUseFdroid(b: Boolean) = prefs.useFdroid.put(b)
	fun getUseIzzy() = prefs.useIzzy.get()
	fun setUseIzzy(b: Boolean) = prefs.useIzzy.put(b)
	fun getUseGitHub() = prefs.useGitHub.get()
	fun setUseGitHub(b: Boolean) = prefs.useGitHub.put(b)
	fun getUseGitLab() = prefs.useGitLab.get()
	fun setUseGitLab(b: Boolean) = prefs.useGitLab.put(b)
	fun getUseAptoide() = prefs.useAptoide.get()
	fun setUseAptoide(b: Boolean) = prefs.useAptoide.put(b)
	fun getUseApkPure() = prefs.useApkPure.get()
	fun setUseApkPure(b: Boolean) = prefs.useApkPure.put(b)
	fun getUsePlay() = prefs.usePlay.get()
	fun setUsePlay(b: Boolean) = prefs.usePlay.put(b)
	fun getAndroidTvUi() = prefs.androidTvUi.get()
	fun setAndroidTvUi(b: Boolean) = prefs.androidTvUi.put(b)
	fun getEnableAlarm() = prefs.enableAlarm.get()
	fun getRootInstall() = prefs.rootInstall.get()
	fun getPackageInstallerInstall() = prefs.packageInstallerInstall.get()
	fun getPrivAppInstall() = prefs.privAppInstall.get()
	fun getInstallModePromptSuppressed() = prefs.suppressInstallModePrompt.get()
	fun getAlarmHour() = prefs.alarmHour.get()
	fun getAlarmFrequency() = prefs.alarmFrequency.get()
	fun getTheme() = prefs.theme.get()

	fun setTheme(theme: Int) {
		prefs.theme.put(theme)
		themer.setTheme(isDarkTheme(theme))
	}

	fun setRootInstall(enabled: Boolean) {
		if (enabled) {
			val granted = attemptEnableRootInstall()
			if (!granted) {
				prefs.rootInstall.put(false)
				ensurePackageInstallerEnabled()
			}
		} else {
			prefs.rootInstall.put(false)
			ensurePackageInstallerEnabled()
		}
	}

	fun setPackageInstallerInstall(enabled: Boolean) {
		if (enabled) {
			prefs.packageInstallerInstall.put(true)
			if (prefs.rootInstall.get()) {
				prefs.rootInstall.put(false)
			}
		} else {
			prefs.packageInstallerInstall.put(false)
			if (!attemptEnableRootInstall()) {
				ensurePackageInstallerEnabled()
			}
		}
	}

	fun setPrivAppInstall(b: Boolean) {
		prefs.privAppInstall.put(b)
	}

	fun setInstallModePromptSuppressed(b: Boolean) {
		prefs.suppressInstallModePrompt.put(b)
	}

	fun setAlarmFrequency(frequency: Int) {
		prefs.alarmFrequency.put(frequency)
		if (getEnableAlarm()) UpdatesWorker.launch(workManager) else UpdatesWorker.cancel(workManager)
	}

	fun setEnableAlarm(b: Boolean, launcher: ManagedActivityResultLauncher<String, Boolean>) {
		prefs.enableAlarm.put(b)
		if (b) {
			notification.checkNotificationPermission(launcher)
			UpdatesWorker.launch(workManager)
		} else {
			UpdatesWorker.cancel(workManager)
		}
	}

	fun setAlarmHour(hour: Int) {
		prefs.alarmHour.put(hour)
		if (getEnableAlarm()) UpdatesWorker.launch(workManager) else UpdatesWorker.cancel(workManager)
	}

	fun setAbout() {
		state.value = SettingsUiState.About
	}

	fun setSettings() {
		state.value = SettingsUiState.Settings
	}

	fun copyAppList() = viewModelScope.launch(Dispatchers.IO) {
		appsRepository.getApps().collectLatest { apps ->
			apps.onSuccess {
				clipboard.copy(gson.toJson(it), "App List")
			}
		}
	}

	fun copyAppLogs() = viewModelScope.launch(Dispatchers.IO) {
		val process = Runtime.getRuntime().exec("logcat -d")
		val data = process.inputStream.readBytes()
		clipboard.copy(data.decodeToString(), "App Logs")
	}

	private fun sanitizeInstallPreferences() {
		val rootEnabled = prefs.rootInstall.get()
		val packageEnabled = prefs.packageInstallerInstall.get()
		when {
			rootEnabled && packageEnabled -> {
				// Prefer root installs by default.
				prefs.packageInstallerInstall.put(false)
			}
			!rootEnabled && !packageEnabled -> {
				if (!attemptEnableRootInstall()) {
					prefs.packageInstallerInstall.put(true)
				}
			}
		}
	}

	private fun attemptEnableRootInstall(): Boolean {
		val hasRoot = hasRootAccess()
		if (hasRoot) {
			prefs.rootInstall.put(true)
			if (prefs.packageInstallerInstall.get()) {
				prefs.packageInstallerInstall.put(false)
			}
		}
		return hasRoot
	}

	private fun ensurePackageInstallerEnabled() {
		if (!prefs.packageInstallerInstall.get()) {
			prefs.packageInstallerInstall.put(true)
		}
	}

	private fun hasRootAccess(): Boolean = try {
		Shell.getShell().isRoot
	} catch (_: Exception) {
		false
	}

}
