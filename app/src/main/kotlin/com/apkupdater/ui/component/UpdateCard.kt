package com.apkupdater.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import com.apkupdater.R
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.Link
import com.apkupdater.util.getAppName
import com.apkupdater.util.getAppIcon

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
            // App Icon with progress indicator overlay
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(56.dp)
            ) {
                // Base icon - circular with caching and lazy loading
                LazyLoadImage(
                    packageName = appUpdate.packageName,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                )

                // Progress ring around icon for downloading/installing
                if (appUpdate.isInstalling) {
                    if (appUpdate.total > 0) {
                        // Determinate progress ring
                        val progress = (appUpdate.progress.toFloat() / appUpdate.total.toFloat()).coerceIn(0f, 1f)
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(56.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    } else {
                        // Indeterminate circular progress ring
                        CircularProgressIndicator(
                            modifier = Modifier.size(56.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    }
                }
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
                    "ApkMirror" -> Color(0xFFFFB74D)  // Light orange
                    "GitHub" -> Color(0xFF9C27B0)      // Purple
                    "F-Droid (Main)", "F-Droid (Izzy)" -> Color(0xFF2196F3)  // Blue
                    "Aptoide" -> Color(0xFFFF6F00)     // Dark orange
                    "ApkPure" -> Color(0xFF4CAF50)     // Green
                    "GitLab" -> Color(0xFFFC6D26)      // Orange-red
                    "Play" -> Color(0xFF34A853)        // Green
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                if (!expanded) {
                    // Collapsed: Source • package name (can be cut off)
                    androidx.compose.foundation.layout.Row(
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

        // Linear progress bar for download progress
        if (appUpdate.isInstalling && appUpdate.total > 0) {
            val progress = (appUpdate.progress.toFloat() / appUpdate.total.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
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
    if (appUpdate.isInstalling && appUpdate.total > 0) {
        // Downloading - show button with fill progress
        val progress = (appUpdate.progress.toFloat() / appUpdate.total.toFloat()).coerceIn(0f, 1f)
        val percentage = (progress * 100).toInt()

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
        ) {
            // Background button
            Button(
                onClick = { /* No action during download */ },
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
                    text = "${stringResource(R.string.downloading)} $percentage%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.Transparent // Hide the text, we'll overlay it
                )
            }

            // Progress fill overlay
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(20.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(40.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                )
            }

            // Text overlay
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${stringResource(R.string.downloading)} $percentage%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    } else if (appUpdate.isInstalling) {
        // Installing - indeterminate state
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
    } else {
        // Ready to update
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
