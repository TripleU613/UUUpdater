package com.tripleu.updater.ui.component

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tripleu.updater.BuildConfig
import com.tripleu.updater.R
import com.tripleu.updater.data.ui.AppUpdate
import com.tripleu.updater.data.ui.InstallStage
import com.tripleu.updater.data.ui.Source
import com.tripleu.updater.util.clickableNoRipple


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcludeIcon(
    exclude: Boolean,
    @StringRes excludeString: Int,
    @StringRes includeString: Int,
    @DrawableRes excludeIcon: Int,
    @DrawableRes includeIcon: Int,
    @DrawableRes icon: Int = if (exclude) excludeIcon else includeIcon,
    @StringRes string: Int = if (exclude) includeString else excludeString,
    @StringRes contentDescription: Int = if (exclude) excludeString else includeString,
) = TooltipBox(
    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
    state = rememberTooltipState(),
    tooltip = { PlainTooltip { Text(stringResource(string)) } }
) {
    Icon(painterResource(icon), stringResource(contentDescription))
}

@Composable
fun ExcludeSystemIcon(exclude: Boolean) = ExcludeIcon(
    exclude = exclude,
    excludeString = R.string.exclude_system_apps,
    includeString = R.string.include_system_apps,
    excludeIcon = R.drawable.ic_system_off,
    includeIcon = R.drawable.ic_system
)

@Composable
fun ExcludeAppStoreIcon(exclude: Boolean) = ExcludeIcon(
    exclude = exclude,
    excludeString = R.string.exclude_app_store,
    includeString = R.string.include_app_store,
    excludeIcon = R.drawable.ic_appstore_off,
    includeIcon = R.drawable.ic_appstore
)

@Composable
fun ExcludeDisabledIcon(exclude: Boolean) = ExcludeIcon(
    exclude = exclude,
    excludeString = R.string.exclude_disabled_apps,
    includeString = R.string.include_disabled_apps,
    excludeIcon = R.drawable.ic_disabled_off,
    includeIcon = R.drawable.ic_disabled
)

@Composable
fun SourceIcon(source: Source, modifier: Modifier = Modifier) {
    if (BuildConfig.TECHEAZ_PRIVILEGED_INSTALLER) {
        return
    }

    Icon(
        painterResource(id = source.resourceId),
        source.name,
        modifier
    )
}

@Composable
fun IgnoreIcon(ignored: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) = Icon(
    painter = painterResource(
        id = if(ignored) R.drawable.ic_visible_off else R.drawable.ic_visible
    ),
    contentDescription = stringResource(if (ignored) R.string.unignore_cd else R.string.ignore_cd),
    modifier = Modifier.clickableNoRipple(onClick).then(modifier)
)

@Composable
fun InstallIcon(onClick: () -> Unit, modifier: Modifier = Modifier) = Icon(
    painter = painterResource(R.drawable.ic_install),
    contentDescription = stringResource(R.string.install_cd),
    modifier = Modifier.clickableNoRipple(onClick).then(modifier)
)

@Composable
fun BoxScope.InstallProgressIcon(
    appUpdate: AppUpdate,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (appUpdate.installStage == InstallStage.Idle) {
        InstallIcon(
            onClick = onClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .then(modifier)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshIcon(
    text: String,
    modifier: Modifier = Modifier
) = TooltipBox(
    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
    state = rememberTooltipState(),
    tooltip = { PlainTooltip { Text(text) } }
) {
    Icon(
        painter = painterResource(id = R.drawable.ic_refresh),
        contentDescription = text,
        modifier = modifier
    )
}
