package com.apkupdater.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.InstallStage
import com.apkupdater.data.ui.progressFraction

@Composable
fun BoxScope.AppInstallProgressRing(
    appUpdate: AppUpdate,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 3.dp
) {
    if (appUpdate.installStage == InstallStage.Idle) return

    val indicatorModifier = modifier
        .align(Alignment.Center)
        .fillMaxSize(1.15f)

    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    when (appUpdate.installStage) {
        InstallStage.Downloading -> {
            val target = appUpdate.progressFraction()
            if (target == null) {
                CircularProgressIndicator(
                    modifier = indicatorModifier,
                    color = color,
                    trackColor = track,
                    strokeWidth = strokeWidth
                )
            } else {
                val animatedProgress by animateFloatAsState(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
                    label = "app_install_progress_ring"
                )
                CircularProgressIndicator(
                    progress = animatedProgress,
                    modifier = indicatorModifier,
                    color = color,
                    trackColor = track,
                    strokeWidth = strokeWidth
                )
            }
        }
        InstallStage.Pending,
        InstallStage.Installing -> {
            CircularProgressIndicator(
                modifier = indicatorModifier,
                color = color,
                trackColor = track,
                strokeWidth = strokeWidth
            )
        }
        InstallStage.Idle -> Unit
    }
}
