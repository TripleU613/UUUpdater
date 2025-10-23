package com.apkupdater.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.rememberAsyncImagePainter
import androidx.tv.foundation.lazy.grid.items
import com.apkupdater.R
import com.apkupdater.data.ui.AppsUiState
import com.apkupdater.prefs.Prefs
import com.apkupdater.ui.component.DefaultErrorScreen
import com.apkupdater.ui.component.ExcludeAppStoreIcon
import com.apkupdater.ui.component.ExcludeDisabledIcon
import com.apkupdater.ui.component.ExcludeSystemIcon
import com.apkupdater.ui.component.InstalledGrid
import com.apkupdater.ui.component.InstalledItem
import com.apkupdater.ui.component.LoadingGrid
import com.apkupdater.ui.component.RefreshIndicatorBar
import com.apkupdater.ui.component.TvInstalledGrid
import com.apkupdater.ui.component.TvInstalledItem
import com.apkupdater.ui.theme.statusBarColor
import com.apkupdater.viewmodel.AppsViewModel
import org.koin.androidx.compose.get
import org.koin.androidx.compose.koinViewModel


@Composable
fun AppsScreen(
	viewModel: AppsViewModel = koinViewModel(),
	isRefreshing: Boolean = false
) {
	viewModel.state().collectAsStateWithLifecycle().value.onLoading {
		AppsScreenLoading(viewModel, it, isRefreshing)
	}.onError {
		AppsScreenError(isRefreshing)
	}.onSuccess {
		AppsScreenSuccess(viewModel, it, isRefreshing)
	}
}

@Composable
fun AppsScreenSuccess(viewModel: AppsViewModel, state: AppsUiState.Success, isRefreshing: Boolean) = Column {
	AppsTopBar(viewModel, state.excludeSystem, state.excludeAppStore, state.excludeDisabled)
	RefreshIndicatorBar(visible = isRefreshing, modifier = Modifier.padding(vertical = 6.dp))
	@Suppress("DEPRECATION")
	if (get<Prefs>().androidTvUi.get()) {
		TvInstalledGrid {
			items(state.apps) {
				TvInstalledItem(it) { app -> viewModel.ignore(app) }
			}
		}
	} else {
		InstalledGrid {
			items(state.apps) {
				InstalledItem(it) { app -> viewModel.ignore(app) }
			}
		}
	}
}

@Composable
fun AppsScreenLoading(viewModel: AppsViewModel, state: AppsUiState.Loading, isRefreshing: Boolean) = Column {
	AppsTopBar(viewModel, state.excludeSystem, state.excludeAppStore, state.excludeDisabled)
	RefreshIndicatorBar(visible = isRefreshing, modifier = Modifier.padding(vertical = 6.dp))
	LoadingGrid()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsTopBar(
	@Suppress("UNUSED_PARAMETER") viewModel: AppsViewModel,
	@Suppress("UNUSED_PARAMETER") excludeSystem: Boolean,
	@Suppress("UNUSED_PARAMETER") excludeAppStore: Boolean,
	@Suppress("UNUSED_PARAMETER") excludeDisabled: Boolean
) = TopAppBar(
	title = { Text(stringResource(R.string.app_name)) },
	colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.statusBarColor()),
	actions = {
	},
	navigationIcon = {
		Box(Modifier.minimumInteractiveComponentSize().size(40.dp), Alignment.Center) {
			Image(
				painter = rememberAsyncImagePainter(R.mipmap.ic_launcher_round),
				contentDescription = "App Icon",
				modifier = Modifier.size(40.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White)
			)
		}
	}
)

@Composable
fun AppsScreenError(isRefreshing: Boolean) = Column {
	RefreshIndicatorBar(visible = isRefreshing, modifier = Modifier.padding(vertical = 6.dp))
	DefaultErrorScreen()
}
