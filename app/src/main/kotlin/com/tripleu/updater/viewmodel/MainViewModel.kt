package com.tripleu.updater.viewmodel

import android.content.Intent
import android.content.pm.PackageInstaller
import com.tripleu.updater.BuildConfig
import com.tripleu.updater.util.NoOpLog as Log
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.tripleu.updater.data.ui.AppInstallStatus
import com.tripleu.updater.data.ui.Screen
import com.tripleu.updater.data.ui.UpdatesUiState
import com.tripleu.updater.prefs.Prefs
import com.tripleu.updater.util.DeviceRebooter
import com.tripleu.updater.util.InstallLog
import com.tripleu.updater.util.SessionInstaller
import com.tripleu.updater.util.UpdatesNotification
import com.tripleu.updater.util.getAppId
import com.tripleu.updater.util.getIntentExtra
import com.tripleu.updater.util.orFalse
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(
    private val prefs: Prefs,
    private val installLog: InstallLog,
    private val deviceRebooter: DeviceRebooter
) : ViewModel() {

    val screens = listOf(Screen.Updates)
    val isRefreshing = MutableStateFlow(false)

    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val uiEvents = _uiEvents.asSharedFlow()

    private val _drawerCommands = MutableSharedFlow<DrawerCommand>(extraBufferCapacity = 1)
    val drawerCommands = _drawerCommands.asSharedFlow()

    private var currentInstallId = 0
    private var refreshTimeoutJob: Job? = null
    private var stateMonitorJob: Job? = null
    private var updatesTimeoutHandled = false
    private val rebootInFlight = AtomicBoolean(false)
    private val spoofPromptShown = AtomicBoolean(false)

    init {
        maybePromptSpoofReboot()
    }

    fun refresh(
        appsViewModel: AppsViewModel,
        updatesViewModel: UpdatesViewModel
    ) = viewModelScope.launch {
        maybePromptSpoofReboot()
        isRefreshing.value = true
        updatesTimeoutHandled = false
        refreshTimeoutJob?.cancel()
        stateMonitorJob?.cancel()

        appsViewModel.refresh(false)
        val updatesJob = updatesViewModel.refresh(false)

        refreshTimeoutJob = viewModelScope.launch {
            delay(LOADING_TIMEOUT_MS)
            val state = updatesViewModel.state().value
            if (!updatesTimeoutHandled && state is UpdatesUiState.Loading) {
                onUpdatesLoadingTimeout()
            }
        }

        stateMonitorJob = viewModelScope.launch {
            updatesViewModel.state()
                .filter { it !is UpdatesUiState.Loading }
                .first()
            refreshTimeoutJob?.cancel()
            maybePromptSpoofReboot()
            if (!updatesTimeoutHandled) {
                isRefreshing.value = false
            }
        }

        updatesJob.invokeOnCompletion {
            maybePromptSpoofReboot()
            if (!updatesTimeoutHandled) {
                isRefreshing.value = false
            }
        }
    }

    fun processIntent(
        intent: Intent,
        launcher: ManagedActivityResultLauncher<Intent, ActivityResult>,
        updatesViewModel: UpdatesViewModel,
        navController: NavController
    ) {
        when {
            intent.action == UpdatesNotification.UpdateAction ->
                processUpdateIntent(navController, updatesViewModel)
            intent.action?.contains(SessionInstaller.INSTALL_ACTION).orFalse() ->
                processInstallIntent(intent, launcher)
            else -> Unit
        }
    }

    fun navigateTo(navController: NavController, route: String) = navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
        prefs.lastTab.put(route)
    }

    fun getLastRoute() = prefs.lastTab.get()

    fun evaluateSpoofPrompt() {
        maybePromptSpoofReboot()
    }

    fun onUpdatesLoadingTimeout() {
        if (updatesTimeoutHandled) return
        updatesTimeoutHandled = true
        refreshTimeoutJob?.cancel()
        viewModelScope.launch {
            _drawerCommands.emit(DrawerCommand.Open)
            _uiEvents.emit(
                UiEvent.ShowMessage(
                    "Connection failed, open the sidebar and switch sources off depending on the error, restart the app and try again."
                )
            )
        }
    }

    fun onSpoofRebootPromptAccepted() {
        if (!BuildConfig.TECHEAZ_PRIVILEGED_INSTALLER) return
        if (!rebootInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            spoofPromptShown.set(false)
            prefs.playSpoofRebootPending.put(false)
            prefs.playSpoofRebootHandled.put(true)
            val success = withContext(Dispatchers.IO) { deviceRebooter.reboot() }
            if (!success) {
                prefs.playSpoofRebootPending.put(true)
                prefs.playSpoofRebootHandled.put(false)
                _uiEvents.emit(UiEvent.ShowMessage("Automatic reboot failed. Please reboot manually."))
            }
            rebootInFlight.set(false)
        }
    }

    fun onSpoofRebootPromptDismissed() {
        viewModelScope.launch {
            spoofPromptShown.set(false)
            prefs.playSpoofRebootHandled.put(true)
            _uiEvents.emit(UiEvent.ShowMessage("Reboot skipped. Play Store spoof may remain unstable."))
        }
    }

    private fun maybePromptSpoofReboot() {
        if (!BuildConfig.TECHEAZ_PRIVILEGED_INSTALLER) {
            prefs.playSpoofRebootPending.put(false)
            if (!prefs.playSpoofRebootHandled.get()) {
                prefs.playSpoofRebootHandled.put(true)
            }
            return
        }
        if (prefs.playSpoofRebootPending.get() && !prefs.playSpoofRebootHandled.get() &&
            spoofPromptShown.compareAndSet(false, true)
        ) {
            viewModelScope.launch {
                _uiEvents.emit(UiEvent.ShowSpoofRebootPrompt)
            }
        }
    }

    private fun processInstallIntent(
        intent: Intent,
        launcher: ManagedActivityResultLauncher<Intent, ActivityResult>
    ) = viewModelScope.launch(Dispatchers.IO) {
        when (intent.extras?.getInt(PackageInstaller.EXTRA_STATUS)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                currentInstallId = intent.getAppId() ?: 0
                intent.getIntentExtra()?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    launcher.launch(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                intent.getAppId()?.let {
                    installLog.emitStatus(AppInstallStatus(true, it))
                }
            }
            else -> {
                intent.getAppId()?.let {
                    val message = intent.extras?.getString(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    installLog.emitStatus(AppInstallStatus(false, it, message = message))
                }
                val message = intent.extras?.getString(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.e("MainViewModel", "Failed to install app: $message $intent")
            }
        }
    }

    private fun processUpdateIntent(
        navController: NavController,
        updatesViewModel: UpdatesViewModel
    ) {
        navigateTo(navController, Screen.Updates.route)
        updatesViewModel.refresh()
    }

    sealed interface UiEvent {
        data object ShowSpoofRebootPrompt : UiEvent
        data class ShowMessage(val message: String) : UiEvent
    }

    sealed interface DrawerCommand {
        data object Open : DrawerCommand
    }

    private companion object {
        private const val LOADING_TIMEOUT_MS = 60_000L
    }
}

