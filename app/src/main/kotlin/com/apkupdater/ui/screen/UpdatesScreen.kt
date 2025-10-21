package com.apkupdater.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.rememberAsyncImagePainter
import androidx.navigation.NavController
import androidx.tv.foundation.lazy.grid.items
import com.apkupdater.R
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.Screen
import com.apkupdater.prefs.Prefs
import com.apkupdater.ui.component.DefaultErrorScreen
import com.apkupdater.ui.component.EmptyGrid
import com.apkupdater.ui.component.InstalledGrid
import com.apkupdater.ui.component.LoadingGrid
import com.apkupdater.ui.component.RefreshIcon
import com.apkupdater.ui.component.TvInstalledGrid
import com.apkupdater.ui.component.TvUpdateItem
import com.apkupdater.ui.component.UpdateCard
import com.apkupdater.ui.component.UpdateItem
import com.apkupdater.ui.theme.statusBarColor
import com.apkupdater.viewmodel.UpdatesViewModel
import org.koin.androidx.compose.get


@Composable
fun UpdatesScreen(viewModel: UpdatesViewModel, navController: NavController, isRefreshing: Boolean = false) {
	var searchQuery by remember { mutableStateOf("") }

	viewModel.state().collectAsStateWithLifecycle().value.onLoading {
		UpdatesScreenLoading(viewModel, navController, isRefreshing, searchQuery) { searchQuery = it }
	}.onError {
		UpdatesScreenError(isRefreshing)
	}.onSuccess {
		UpdatesScreenSuccess(viewModel, navController, it.updates, isRefreshing, searchQuery) { searchQuery = it }
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesTopBar(
	searchQuery: String,
	onSearchQueryChange: (String) -> Unit,
	onUpdateAll: () -> Unit = {}
) {
	var isSearchActive by remember { mutableStateOf(false) }
	var showThemeDialog by remember { mutableStateOf(false) }
	var showUpdateAllDialog by remember { mutableStateOf(false) }
	@Suppress("DEPRECATION")
	val prefs: Prefs = get()
	val currentTheme = prefs.theme.get()

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
			Box(
			Modifier
				.minimumInteractiveComponentSize()
				.size(40.dp)
				.padding(start = 8.dp),
			Alignment.Center
		) {
				Image(
					painter = rememberAsyncImagePainter(R.mipmap.ic_launcher_round),
					contentDescription = "App Icon",
					modifier = Modifier.size(40.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White)
				)
			}
		},
		actions = {
			// Search toggle button
			IconButton(onClick = {
				isSearchActive = !isSearchActive
				if (!isSearchActive) {
					onSearchQueryChange("")
				}
			}) {
				Icon(
					imageVector = Icons.Filled.Search,
					contentDescription = "Search"
				)
			}

			// Update all button
			IconButton(onClick = {
				showUpdateAllDialog = true
			}) {
				Icon(
					imageVector = Icons.Filled.ThumbUp,
					contentDescription = "Update All"
				)
			}

			// Theme toggle button
			IconButton(onClick = {
				showThemeDialog = true
			}) {
				Icon(
					painter = painterResource(R.drawable.ic_daynight),
					contentDescription = "Toggle Theme"
				)
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
	isRefreshing: Boolean,
	searchQuery: String,
	onSearchQueryChange: (String) -> Unit
) = Column {
	UpdatesTopBar(searchQuery, onSearchQueryChange)
	if (isRefreshing) {
		LinearProgressIndicator(
			modifier = Modifier.fillMaxWidth(),
			color = MaterialTheme.colorScheme.primary,
			trackColor = MaterialTheme.colorScheme.surfaceVariant
		)
	}
	LoadingGrid()
}

@Composable
fun UpdatesScreenError(isRefreshing: Boolean) = Column {
	if (isRefreshing) {
		LinearProgressIndicator(
			modifier = Modifier.fillMaxWidth(),
			color = MaterialTheme.colorScheme.primary,
			trackColor = MaterialTheme.colorScheme.surfaceVariant
		)
	}
	DefaultErrorScreen()
}

@Composable
fun UpdatesScreenSuccess(
	viewModel: UpdatesViewModel,
	@Suppress("UNUSED_PARAMETER") navController: NavController,
	updates: List<AppUpdate>,
	isRefreshing: Boolean,
	searchQuery: String,
	onSearchQueryChange: (String) -> Unit
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

	UpdatesTopBar(searchQuery, onSearchQueryChange) {
		filteredUpdates.forEach { update ->
			viewModel.install(update, handler)
		}
	}
	if (isRefreshing) {
		LinearProgressIndicator(
			modifier = Modifier.fillMaxWidth(),
			color = MaterialTheme.colorScheme.primary,
			trackColor = MaterialTheme.colorScheme.surfaceVariant
		)
	}

	when {
		filteredUpdates.isEmpty() -> EmptyGrid()
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
