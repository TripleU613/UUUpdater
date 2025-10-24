package com.tripleu.updater.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.log10
import kotlin.math.pow
import androidx.compose.ui.unit.sp
import com.tripleu.updater.R
import com.tripleu.updater.data.ui.AppPackaging
import com.tripleu.updater.data.ui.AppUpdate
import com.tripleu.updater.data.ui.InstallStage
import com.tripleu.updater.data.ui.Link
import com.tripleu.updater.util.getAppName
import com.tripleu.updater.util.getAppIcon
import coil.compose.AsyncImage
import coil.request.CachePolicy

@Composable
fun UpdateCard(
    appUpdate: AppUpdate,
    onActionClick: (Link) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = false) { /* Card is clickable for ripple effect */ }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Icon
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(60.dp)
            ) {
                AppInstallProgressRing(
                    appUpdate = appUpdate,
                    strokeWidth = 4.dp
                )
                LazyLoadImage(
                    packageName = appUpdate.packageName,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // App Info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { expanded = !expanded }
                    .animateContentSize(),
                verticalArrangement = Arrangement.Center
            ) {
                // App Name - single line, 14sp
                Text(
                    text = appUpdate.name.ifEmpty { LocalContext.current.getAppName(appUpdate.packageName) },
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Source with color coding
                val sourceColor = when (appUpdate.source.name) {
                    "GitHub" -> Color(0xFF9C27B0)      // Purple
                    "F-Droid (Main)", "F-Droid (Izzy)" -> Color(0xFF2196F3)  // Blue
                    "Aptoide" -> Color(0xFFFF6F00)     // Dark orange
                    "ApkPure" -> Color(0xFF4CAF50)     // Green
                    "GitLab" -> Color(0xFFFC6D26)      // Orange-red
                    "Play" -> Color(0xFFEA4335)        // Red
                    "Techeaz" -> Color(0xFF1E88E5)     // Blue highlight for Techeaz feed
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                if (!expanded) {
                    // Collapsed: Source • package name (can be cut off)
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = appUpdate.source.name,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = sourceColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "• ${appUpdate.packageName}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    // Expanded: Source only at top
                    Text(
                        text = appUpdate.source.name,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = sourceColor,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                // Expandable details
                if (expanded) {
                    // Package name - full, no cutoff
                    Text(
                        text = appUpdate.packageName,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    if (appUpdate.packaging == AppPackaging.Bundle) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.bundle_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    // Divider after package name
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    // Version info - no ellipsis, full text
                    Text(
                        text = appUpdate.version,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Divider between version and update info
                    if (appUpdate.whatsNew.isNotEmpty()) {
                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )

                        // What's New
                        Text(
                            text = appUpdate.whatsNew,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Action Button - moved to the right
            UpdateActionButton(
                appUpdate = appUpdate,
                onClick = {
                    android.util.Log.d("UpdateCard", "UpdateActionButton onClick called for: ${appUpdate.name}")
                    onActionClick(appUpdate.link)
                }
            )
        }

        // Divider line between cards
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                .padding(horizontal = 16.dp)
        )
    }
}

@Composable
private fun UpdateActionButton(
    appUpdate: AppUpdate,
    onClick: () -> Unit
) {
    when (appUpdate.installStage) {
        InstallStage.Downloading -> {
            val totalBytes = appUpdate.total
            val hasTotal = totalBytes > 0
            val progressBytes = appUpdate.progress
            val progressRatio = if (hasTotal) {
                (progressBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
            val labelText = when {
                progressRatio != null -> "${(progressRatio * 100).toInt().coerceIn(0, 100)}%"
                progressBytes > 0L -> formatBytes(progressBytes)
                else -> stringResource(R.string.downloading)
            }
            val progressValue = progressRatio ?: 0f
            val shape = RoundedCornerShape(20.dp)

            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Box(
                    modifier = Modifier
                        .clip(shape)
                        .defaultMinSize(minHeight = 40.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                    ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressValue)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f))
                    )
                }
                Text(
                    text = labelText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
        InstallStage.Installing -> {
            Button(
                onClick = { /* No action during install */ },
                enabled = false,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.installing),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        InstallStage.Pending -> {
            Button(
                onClick = { /* No action during pending */ },
                enabled = false,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.pending),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        InstallStage.Idle -> {
            Button(
                onClick = {
                    android.util.Log.d("UpdateCard", "Update button clicked!")
                    onClick()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.update),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return String.format("%.1f %s", value, units[digitGroups])
}

@Composable
private fun LazyLoadImage(
    packageName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    coil.compose.AsyncImage(
        model = coil.request.ImageRequest.Builder(context)
            .data(context.getAppIcon(packageName))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build(),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop
    )
}
