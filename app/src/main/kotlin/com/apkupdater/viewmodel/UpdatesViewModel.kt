package com.apkupdater.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.compose.ui.platform.UriHandler
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.InstallStage
import com.apkupdater.data.ui.UpdatesUiState
import com.apkupdater.data.ui.removeId
import com.apkupdater.data.ui.setInstallStage
import com.apkupdater.data.ui.setProgress
import com.apkupdater.prefs.Prefs
import com.apkupdater.repository.UpdatesRepository
import com.apkupdater.util.Badger
import com.apkupdater.util.Downloader
import com.apkupdater.util.InstallLog
import com.apkupdater.util.SessionInstaller
import com.apkupdater.util.SnackBar
import com.apkupdater.util.Stringer
import com.apkupdater.util.launchWithMutex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex


class UpdatesViewModel(
    private val updatesRepository: UpdatesRepository,
    private val installer: SessionInstaller,
    prefs: Prefs,
    private val badger: Badger,
    downloader: Downloader,
    snackBar: SnackBar,
    stringer: Stringer,
    installLog: InstallLog
) : InstallViewModel(downloader, installer, prefs, snackBar, stringer, installLog) {

	private val mutex = Mutex()
	private val state = MutableStateFlow<UpdatesUiState>(UpdatesUiState.Loading)
    private val _bulkInstallRunning = MutableStateFlow(false)
    val bulkInstallRunning: StateFlow<Boolean> = _bulkInstallRunning.asStateFlow()
    private var bulkInstallJob: Job? = null

        init {
                subscribeToInstallStatus(state.value.updates())
                subscribeToInstallProgress { progress ->
                        val updated = state.value.mutableUpdates().setProgress(progress)
                        state.value = UpdatesUiState.Success(updated.applyActiveInstallState())
                }
        }

	fun state(): StateFlow<UpdatesUiState> = state

	fun refresh(load: Boolean = true) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		if (load) state.value = UpdatesUiState.Loading
		badger.changeUpdatesBadge("")
        updatesRepository.updates().collect {
            setSuccess(it)
        }
    }

    fun installAll(updates: List<AppUpdate>, handler: UriHandler) {
        if (updates.isEmpty()) return
        if (bulkInstallJob?.isActive == true) return

        val queue = updates
            .distinctBy { it.packageName to it.versionCode }

        if (queue.isEmpty()) return

        _bulkInstallRunning.value = true
        bulkInstallJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                for (update in queue) {
                    val job = install(update, handler)
                    job?.join()
                }
            } finally {
                _bulkInstallRunning.value = false
            }
        }
    }

	fun ignoreVersion(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
		val ignored = prefs.ignoredVersions.get().toMutableList()
		if (ignored.contains(id)) ignored.remove(id) else ignored.add(id)
		prefs.ignoredVersions.put(ignored)
		setSuccess(state.value.mutableUpdates())
	}

    override fun cancelInstall(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
        val blocked = consumeBlockedInstall(id)
        val updates = state.value.mutableUpdates()
        if (blocked) {
            clearTrackedInstall(id)
            setSuccess(updates.removeId(id))
        } else {
            state.value = UpdatesUiState.Success(updates.setInstallStage(id, InstallStage.Idle).applyActiveInstallState())
            clearTrackedInstall(id)
        }
        installer.finish()
    }

    override fun finishInstall(id: Int) = viewModelScope.launchWithMutex(mutex, Dispatchers.IO) {
        clearTrackedInstall(id)
        setSuccess(state.value.mutableUpdates().removeId(id))
        installer.finish()
    }

    override fun downloadAndRootInstall(update: AppUpdate) = viewModelScope.launch(Dispatchers.IO) {
        registerInstall(update)
        val updated = state.value.mutableUpdates().setInstallStage(update.id, InstallStage.Pending)
        state.value = UpdatesUiState.Success(updated.applyActiveInstallState())
        emitInitialDownloadProgress(update)
        downloadAndRootInstall(update.id, update.packageName, update.link)
    }

    override fun downloadAndInstall(update: AppUpdate) = viewModelScope.launch(Dispatchers.IO) {
        if(installer.checkPermission()) {
            registerInstall(update)
            val updated = state.value.mutableUpdates().setInstallStage(update.id, InstallStage.Pending)
            state.value = UpdatesUiState.Success(updated.applyActiveInstallState())
            emitInitialDownloadProgress(update)
            downloadAndInstall(update.id, update.packageName, update.link)
        }
    }

	private fun List<AppUpdate>.filterIgnoredVersions(ignoredVersions: List<Int>) = this
		.filter { !ignoredVersions.contains(it.id) }

	private fun setSuccess(updates: List<AppUpdate>) = updates
		.filterIgnoredVersions(prefs.ignoredVersions.get())
		.let {
	state.value = UpdatesUiState.Success(it.applyActiveInstallState())
			badger.changeUpdatesBadge(it.size.toString())
		}

}
