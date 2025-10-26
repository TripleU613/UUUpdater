package com.tripleu.updater.ui.screen

import android.app.Activity.RESULT_CANCELED
import android.content.Intent
import com.tripleu.updater.util.NoOpLog as Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tripleu.updater.data.ui.Screen
import com.tripleu.updater.ui.component.BadgeText
import com.tripleu.updater.ui.theme.AppTheme
import com.tripleu.updater.util.Badger
import com.tripleu.updater.util.InstallLog
import com.tripleu.updater.util.SnackBar
import com.tripleu.updater.util.Themer
import com.tripleu.updater.viewmodel.AppsViewModel
import com.tripleu.updater.viewmodel.MainViewModel
import com.tripleu.updater.viewmodel.SearchViewModel
import com.tripleu.updater.viewmodel.SettingsViewModel
import com.tripleu.updater.viewmodel.UpdatesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.androidx.compose.get
import org.koin.androidx.compose.koinViewModel
import kotlin.coroutines.CoroutineContext


@Composable
@OptIn(ExperimentalMaterialApi::class)
fun MainScreen(mainViewModel: MainViewModel = koinViewModel()) {
	// ViewModels
	val appsViewModel: AppsViewModel = koinViewModel()
	val updatesViewModel: UpdatesViewModel = koinViewModel()
	val searchViewModel: SearchViewModel = koinViewModel()
	val settingsViewModel: SettingsViewModel = koinViewModel()

	// Navigation
	val navController = rememberNavController()

    var showSpoofDialog by remember { mutableStateOf(false) }

    val snackBarHostState = handleSnackBar()

    mainViewModel.uiEvents.CollectAsEffect(Dispatchers.Main) { event ->
        when (event) {
            MainViewModel.UiEvent.ShowSpoofRebootPrompt -> showSpoofDialog = true
            is MainViewModel.UiEvent.ShowMessage -> snackBarHostState.showSnackbar(event.message)
        }
    }

    val isRefreshing = mainViewModel.isRefreshing.collectAsStateWithLifecycle()
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing.value,
        onRefresh = { mainViewModel.refresh(appsViewModel, updatesViewModel) }
    )
    LaunchedEffect(Unit) {
        mainViewModel.refresh(appsViewModel, updatesViewModel)
    }
    val refreshIndicatorVisible = isRefreshing.value || pullRefreshState.progress > 0f

	// Used to launch the install intent and get dismissal result
	@Suppress("DEPRECATION")
	val installLog = get<InstallLog>()
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		if (it.resultCode == RESULT_CANCELED) {
			installLog.cancelCurrentInstall()
		}
	}

	// Check intent when cold starting from notification
	checkNotificationIntent(mainViewModel, updatesViewModel, navController, launcher)

	// Check notification intent when hot starting
	intentListener(mainViewModel, updatesViewModel, navController, launcher)

	// Theme
	@Suppress("DEPRECATION")
	val theme = get<Themer>().flow().collectAsStateWithLifecycle().value

    if (showSpoofDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(text = "Reboot Required") },
            text = { Text(text = "Needs a reboot to sync Play Store spoof.") },
            confirmButton = {
                TextButton(onClick = {
                    showSpoofDialog = false
                    mainViewModel.onSpoofRebootPromptAccepted()
                }) {
                    Text(text = "Reboot now")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSpoofDialog = false
                    mainViewModel.onSpoofRebootPromptDismissed()
                }) {
                    Text(text = "Later")
                }
            }
        )
    }

        AppTheme(theme) {
                Scaffold(
                        snackbarHost = { SnackbarHost(snackBarHostState) },
                        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pullRefresh(pullRefreshState)
                    .padding(padding)
            ) {
                NavHost(
                    navController,
                    PaddingValues(0.dp),
                    mainViewModel,
                    appsViewModel,
                    updatesViewModel,
                    searchViewModel,
                    settingsViewModel,
                    refreshIndicatorVisible
                )
            }
		}
	}
}

@Composable
fun handleSnackBar(): SnackbarHostState {
	val snackBarHostState = remember { SnackbarHostState() }
	@Suppress("DEPRECATION")
	get<SnackBar>().flow().CollectAsEffect(Dispatchers.IO) {
		snackBarHostState.showSnackbar(it)
	}
	return snackBarHostState
}

@Composable
fun <T> Flow<T>.CollectAsEffect(
	context: CoroutineContext = Dispatchers.IO,
	block: suspend (T) -> Unit
) = LaunchedEffect(Unit) {
	onEach(block).flowOn(context).launchIn(this)
}

@Composable
fun intentListener(
	mainViewModel: MainViewModel,
	updatesViewModel: UpdatesViewModel,
	navController: NavController,
	launcher: ManagedActivityResultLauncher<Intent, ActivityResult>
) = runCatching {
	val activity = LocalContext.current as ComponentActivity
	DisposableEffect(Unit) {
		val listener = Consumer<Intent> {
			mainViewModel.processIntent(it, launcher, updatesViewModel, navController)
		}
		activity.addOnNewIntentListener(listener)
		onDispose { activity.removeOnNewIntentListener(listener) }
	}
}.getOrElse {
	Log.e("MainScreen", "Error listening to intent.", it)
}

@Composable
fun checkNotificationIntent(
	mainViewModel: MainViewModel,
	updatesViewModel: UpdatesViewModel,
	navController: NavController,
	launcher: ManagedActivityResultLauncher<Intent, ActivityResult>
) = runCatching {
	val activity = LocalContext.current as ComponentActivity
	mainViewModel.processIntent(activity.intent, launcher, updatesViewModel, navController)
}.getOrElse {
	Log.e("MainScreen", "Error checking notification intent.", it)
}

@Composable
fun NavHost(
	navController: NavHostController,
	padding: PaddingValues,
	mainViewModel: MainViewModel,
	appsViewModel: AppsViewModel,
	updatesViewModel: UpdatesViewModel,
	searchViewModel: SearchViewModel,
	settingsViewModel: SettingsViewModel,
	isRefreshing: Boolean
) = NavHost(
	navController = navController,
	startDestination = mainViewModel.getLastRoute(),
	modifier = Modifier.padding(padding)
) {
	composable(Screen.Apps.route) { AppsScreen(appsViewModel, isRefreshing) }
	composable(Screen.Search.route) { SearchScreen(searchViewModel, isRefreshing) }
	composable(Screen.Updates.route) {
        UpdatesScreen(
            viewModel = updatesViewModel,
            navController = navController,
            settingsViewModel = settingsViewModel,
            mainViewModel = mainViewModel,
            isRefreshing = isRefreshing
        )
    }
}
