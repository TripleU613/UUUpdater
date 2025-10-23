package com.apkupdater.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.tv.foundation.lazy.grid.items
import coil.compose.rememberAsyncImagePainter
import com.apkupdater.R
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.prefs.Prefs
import com.apkupdater.ui.component.DefaultErrorScreen
import com.apkupdater.ui.component.LoadingGrid
import com.apkupdater.ui.component.RefreshIndicatorBar
import com.apkupdater.ui.component.SwitchSetting
import com.apkupdater.ui.component.TvInstalledGrid
import com.apkupdater.ui.component.TvUpdateItem
import com.apkupdater.ui.component.UpdateCard
import com.apkupdater.ui.theme.statusBarColor
import com.apkupdater.util.InstallLog
import com.apkupdater.util.InstallModePrompt
import com.apkupdater.viewmodel.SettingsViewModel
import com.apkupdater.viewmodel.UpdatesViewModel
import kotlinx.coroutines.launch
import org.koin.androidx.compose.get


@Composable
fun UpdatesScreen(
	viewModel: UpdatesViewModel,
	navController: NavController,
	settingsViewModel: SettingsViewModel,
	isRefreshing: Boolean = false
) {
	var searchQuery by remember { mutableStateOf("") }
	val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
	val scope = rememberCoroutineScope()
	@Suppress("DEPRECATION")
	val installLog: InstallLog = get()
	var installModePrompt by remember { mutableStateOf<InstallModePrompt?>(null) }
	installLog.installModePrompts().CollectAsEffect {
		installModePrompt = it
	}
	val state = viewModel.state().collectAsStateWithLifecycle().value

	ModalNavigationDrawer(
		drawerState = drawerState,
		drawerContent = {
			UpdatesDrawerContent(settingsViewModel)
		}
	) {
		val openDrawer: () -> Unit = {
			scope.launch { drawerState.open() }
		}

		installModePrompt?.let { prompt ->
			AlertDialog(
				onDismissRequest = { installModePrompt = null },
				title = { Text(stringResource(R.string.root_install_prompt_title)) },
				text = { Text(stringResource(R.string.root_install_prompt_message, prompt.packageName)) },
				confirmButton = {
					TextButton(onClick = {
						installModePrompt = null
						openDrawer()
					}) {
						Text(stringResource(R.string.root_install_prompt_yes))
					}
				},
				dismissButton = {
					Row {
						TextButton(onClick = { installModePrompt = null }) {
							Text(stringResource(R.string.root_install_prompt_no))
						}
						TextButton(onClick = {
							settingsViewModel.setInstallModePromptSuppressed(true)
							installModePrompt = null
						}) {
							Text(stringResource(R.string.root_install_prompt_never))
						}
					}
				}
			)
		}

	state.onLoading {
		UpdatesScreenLoading(
			viewModel = viewModel,
			navController = navController,
			searchQuery = searchQuery,
			isRefreshing = isRefreshing,
			onSearchQueryChange = { searchQuery = it },
			onOpenDrawer = openDrawer
		)
	}.onError {
		UpdatesScreenError(
			searchQuery = searchQuery,
			isRefreshing = isRefreshing,
			onSearchQueryChange = { searchQuery = it },
			onOpenDrawer = openDrawer
		)
	}.onSuccess {
		UpdatesScreenSuccess(
			viewModel = viewModel,
			navController = navController,
			updates = it.updates,
			isRefreshing = isRefreshing,
			searchQuery = searchQuery,
			onSearchQueryChange = { searchQuery = it },
			onOpenDrawer = openDrawer
		)
	}
}
}

@Composable
private fun UpdatesDrawerContent(settingsViewModel: SettingsViewModel) {
	ModalDrawerSheet {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.padding(top = 24.dp, bottom = 24.dp)
		) {
			Text(
				text = stringResource(R.string.settings_sources),
				style = MaterialTheme.typography.titleMedium,
				modifier = Modifier.padding(horizontal = 16.dp)
			)
			Spacer(modifier = Modifier.height(8.dp))
			SwitchSetting(
				getValue = { settingsViewModel.getUseGitHub() },
				setValue = { settingsViewModel.setUseGitHub(it) },
				text = stringResource(R.string.source_github),
				icon = R.drawable.ic_github
			)
			SwitchSetting(
				getValue = { settingsViewModel.getUseGitLab() },
				setValue = { settingsViewModel.setUseGitLab(it) },
				text = stringResource(R.string.source_gitlab),
				icon = R.drawable.ic_gitlab
			)
			SwitchSetting(
				getValue = { settingsViewModel.getUseApkMirror() },
				setValue = { settingsViewModel.setUseApkMirror(it) },
				text = stringResource(R.string.source_apkmirror),
				icon = R.drawable.ic_apkmirror
			)
			SwitchSetting(
				getValue = { settingsViewModel.getUseFdroid() },
				setValue = { settingsViewModel.setUseFdroid(it) },
				text = stringResource(R.string.source_fdroid),
				icon = R.drawable.ic_fdroid
			)
			SwitchSetting(
				getValue = { settingsViewModel.getUseIzzy() },
				setValue = { settingsViewModel.setUseIzzy(it) },
				text = stringResource(R.string.source_izzy),
				icon = R.drawable.ic_izzy
			)
			SwitchSetting(
				getValue = { settingsViewModel.getUseAptoide() },
				setValue = { settingsViewModel.setUseAptoide(it) },
				text = stringResource(R.string.source_aptoide),
				icon = R.drawable.ic_aptoide
			)
			SwitchSetting(
				getValue = { settingsViewModel.getUseApkPure() },
				setValue = { settingsViewModel.setUseApkPure(it) },
				text = stringResource(R.string.source_apkpure),
				icon = R.drawable.ic_apkpure
			)
			SwitchSetting(
				getValue = { settingsViewModel.getUsePlay() },
				setValue = { settingsViewModel.setUsePlay(it) },
				text = stringResource(R.string.source_play) + " (Alpha)",
				icon = R.drawable.ic_play
			)
			Spacer(modifier = Modifier.height(16.dp))
			HorizontalDivider(
				modifier = Modifier.padding(horizontal = 16.dp),
				thickness = DividerDefaults.Thickness,
				color = DividerDefaults.color
			)
			Spacer(modifier = Modifier.height(16.dp))
			Text(
				text = stringResource(R.string.settings_options),
				style = MaterialTheme.typography.titleMedium,
				modifier = Modifier.padding(horizontal = 16.dp)
			)
			Spacer(modifier = Modifier.height(8.dp))
			SwitchSetting(
				getValue = { settingsViewModel.getRootInstall() },
				setValue = { settingsViewModel.setRootInstall(it) },
				text = stringResource(R.string.root_install),
				icon = R.drawable.ic_root
			)
			SwitchSetting(
				getValue = { settingsViewModel.getPackageInstallerInstall() },
				setValue = { settingsViewModel.setPackageInstallerInstall(it) },
				text = stringResource(R.string.package_installer_install),
				icon = R.drawable.ic_install
			)
			SwitchSetting(
				getValue = { settingsViewModel.getPrivAppInstall() },
				setValue = { settingsViewModel.setPrivAppInstall(it) },
				text = stringResource(R.string.priv_app_install),
				icon = R.drawable.ic_system
			)
			SwitchSetting(
				getValue = { !settingsViewModel.getInstallModePromptSuppressed() },
				setValue = { settingsViewModel.setInstallModePromptSuppressed(!it) },
				text = stringResource(R.string.install_mode_prompt),
				icon = R.drawable.ic_info
			)
			SwitchSetting(
				getValue = { settingsViewModel.getIgnoreAlpha() },
				setValue = { settingsViewModel.setIgnoreAlpha(it) },
				text = stringResource(R.string.ignore_alpha),
				icon = R.drawable.ic_alpha
			)
			SwitchSetting(
				getValue = { settingsViewModel.getIgnoreBeta() },
				setValue = { settingsViewModel.setIgnoreBeta(it) },
				text = stringResource(R.string.ignore_beta),
				icon = R.drawable.ic_beta
			)
			SwitchSetting(
				getValue = { settingsViewModel.getIgnorePreRelease() },
				setValue = { settingsViewModel.setIgnorePreRelease(it) },
				text = stringResource(R.string.ignore_preRelease),
				icon = R.drawable.ic_pre_release
			)
			SwitchSetting(
				getValue = { settingsViewModel.getUseSafeStores() },
				setValue = { settingsViewModel.setUseSafeStores(it) },
				text = stringResource(R.string.use_safe_stores),
				icon = R.drawable.ic_safe
			)
			Spacer(modifier = Modifier.height(16.dp))
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesTopBar(
	searchQuery: String,
	onSearchQueryChange: (String) -> Unit,
	onOpenDrawer: () -> Unit,
	onUpdateAll: () -> Unit = {}
) {
	var isSearchActive by remember { mutableStateOf(false) }
	var showThemeDialog by remember { mutableStateOf(false) }
	var showUpdateAllDialog by remember { mutableStateOf(false) }
	@Suppress("DEPRECATION")
	val prefs: Prefs = get()
	val currentTheme = prefs.theme.get()
	val iconButtonModifier = Modifier.size(36.dp)

	TopAppBar(
		title = {
			if (isSearchActive) {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(end = 8.dp)
						.clip(CircleShape)
						.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
						.padding(horizontal = 12.dp, vertical = 0.dp)
				) {
					OutlinedTextField(
						value = searchQuery,
						onValueChange = onSearchQueryChange,
						placeholder = {
							Text(
								"Search",
								style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp)
							)
						},
						modifier = Modifier.fillMaxWidth(),
						singleLine = true,
						maxLines = 1,
						textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
						colors = OutlinedTextFieldDefaults.colors(
							focusedBorderColor = Color.Transparent,
							unfocusedBorderColor = Color.Transparent,
							focusedContainerColor = Color.Transparent,
							unfocusedContainerColor = Color.Transparent
						),
						keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
						keyboardActions = KeyboardActions(onSearch = { })
					)
				}
			} else {
				Text(
					stringResource(R.string.app_name),
					style = MaterialTheme.typography.titleLarge,
					fontWeight = FontWeight.SemiBold
				)
			}
		},
		colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.statusBarColor()),
		navigationIcon = {
			IconButton(
				onClick = onOpenDrawer,
				modifier = Modifier
					.minimumInteractiveComponentSize()
					.padding(start = 8.dp)
			) {
				Image(
					painter = rememberAsyncImagePainter(R.mipmap.ic_launcher_round),
					contentDescription = "Open drawer",
					modifier = Modifier
						.size(32.dp)
						.clip(CircleShape)
				)
			}
		},
		actions = {
			if (isSearchActive) {
				IconButton(
					onClick = {
						isSearchActive = false
						onSearchQueryChange("")
					},
					modifier = iconButtonModifier
				) {
					Icon(
						imageVector = Icons.Filled.Close,
						contentDescription = "Clear search"
					)
				}
			} else {
				IconButton(
					onClick = { isSearchActive = true },
					modifier = iconButtonModifier
				) {
					Icon(
						imageVector = Icons.Filled.Search,
						contentDescription = "Search"
					)
				}

				IconButton(
					onClick = { showUpdateAllDialog = true },
					modifier = iconButtonModifier
				) {
					Icon(
						imageVector = ImageVector.vectorResource(id = R.drawable.ic_update),
						contentDescription = "Update All"
					)
				}

				IconButton(
					onClick = { showThemeDialog = true },
					modifier = iconButtonModifier
				) {
					Icon(
						painter = painterResource(R.drawable.ic_daynight),
						contentDescription = "Toggle Theme"
					)
				}
			}
		}
	)

	if (showThemeDialog) {
		val newTheme = if (currentTheme == 1) 2 else 1
		val newThemeName = if (newTheme == 1) "Dark" else "Light"

		AlertDialog(
			onDismissRequest = { showThemeDialog = false },
			title = {
				Text(
					"Change Theme",
					style = MaterialTheme.typography.headlineSmall
				)
			},
			text = {
				Text(
					"Switch to $newThemeName theme? The app will restart.",
					style = MaterialTheme.typography.bodyMedium
				)
			},
			confirmButton = {
				TextButton(onClick = {
					prefs.theme.put(newTheme)
					showThemeDialog = false
				}) {
					Text("Yes")
				}
			},
			dismissButton = {
				TextButton(onClick = {
					showThemeDialog = false
				}) {
					Text("Cancel")
				}
			}
		)
	}

	if (showUpdateAllDialog) {
		AlertDialog(
			onDismissRequest = { showUpdateAllDialog = false },
			title = {
				Text(
					"Update All Apps",
					style = MaterialTheme.typography.headlineSmall
				)
			},
			text = {
				Text(
					"This will download and install all available updates. Continue?",
					style = MaterialTheme.typography.bodyMedium
				)
			},
			confirmButton = {
				TextButton(onClick = {
					onUpdateAll()
					showUpdateAllDialog = false
				}) {
					Text("Update All")
				}
			},
			dismissButton = {
				TextButton(onClick = {
					showUpdateAllDialog = false
				}) {
					Text("Cancel")
				}
			}
		)
	}
}

@Composable
fun UpdatesScreenLoading(
	@Suppress("UNUSED_PARAMETER") viewModel: UpdatesViewModel,
	@Suppress("UNUSED_PARAMETER") navController: NavController,
	searchQuery: String,
	isRefreshing: Boolean,
	onSearchQueryChange: (String) -> Unit,
	onOpenDrawer: () -> Unit
) = Column {
	UpdatesTopBar(searchQuery, onSearchQueryChange, onOpenDrawer)
	RefreshIndicatorBar(visible = isRefreshing, modifier = Modifier.padding(vertical = 6.dp))
	LoadingGrid()
}

@Composable
fun UpdatesScreenError(
	searchQuery: String,
	isRefreshing: Boolean,
	onSearchQueryChange: (String) -> Unit,
	onOpenDrawer: () -> Unit
) = Column {
	UpdatesTopBar(searchQuery, onSearchQueryChange, onOpenDrawer)
	RefreshIndicatorBar(visible = isRefreshing, modifier = Modifier.padding(vertical = 6.dp))
	DefaultErrorScreen()
}

@Composable
fun UpdatesScreenSuccess(
	viewModel: UpdatesViewModel,
	@Suppress("UNUSED_PARAMETER") navController: NavController,
	updates: List<AppUpdate>,
	isRefreshing: Boolean,
	searchQuery: String,
	onSearchQueryChange: (String) -> Unit,
	onOpenDrawer: () -> Unit
) = Column {
	val handler = LocalUriHandler.current
	@Suppress("DEPRECATION")
	val tv = get<Prefs>().androidTvUi.get()

	// Filter and sort updates alphabetically based on search query
	val filteredUpdates = if (searchQuery.isBlank()) {
		updates.sortedBy { it.name.lowercase() }
	} else {
		updates.filter {
			it.name.contains(searchQuery, ignoreCase = true) ||
			it.packageName.contains(searchQuery, ignoreCase = true)
		}.sortedBy { it.name.lowercase() }
	}

    UpdatesTopBar(searchQuery, onSearchQueryChange, onOpenDrawer) {
        filteredUpdates.forEach { update ->
            viewModel.install(update, handler)
        }
    }
    RefreshIndicatorBar(visible = isRefreshing, modifier = Modifier.padding(vertical = 6.dp))

    when {
        filteredUpdates.isEmpty() -> EmptyUpdatesState()
        tv -> TvGrid(viewModel, filteredUpdates, handler)
        !tv -> ListLayout(viewModel, filteredUpdates, handler)
    }
}

@Composable
fun TvGrid(
	viewModel: UpdatesViewModel,
	updates: List<AppUpdate>,
	handler: UriHandler
) = TvInstalledGrid {
	items(updates) { update ->
		TvUpdateItem(
			update,
			{ viewModel.install(update, handler) },
			{ viewModel.ignoreVersion(update.id)}
		)
	}
}

@Composable
fun ListLayout(
	viewModel: UpdatesViewModel,
	updates: List<AppUpdate>,
	handler: UriHandler
) = LazyColumn(
	modifier = Modifier.fillMaxSize()
) {
	items(updates) { update ->
		UpdateCard(
			appUpdate = update,
			onActionClick = { viewModel.install(update, handler) }
		)
	}
}

@Composable
private fun EmptyUpdatesState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_update),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.all_up_to_date_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.all_up_to_date_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

