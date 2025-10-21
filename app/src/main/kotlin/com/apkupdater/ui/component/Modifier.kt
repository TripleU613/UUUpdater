package com.apkupdater.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize


@Composable
fun Modifier.shimmering(
    enabled: Boolean,
    baseColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    highlightColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
): Modifier = composed {
    if (enabled) {
        var size by remember { mutableStateOf(IntSize.Zero) }
        val transition = rememberInfiniteTransition(label = "shimmering")
        val startOffsetX by transition.animateFloat(
            label = "shimmering_offset",
            initialValue = -2 * size.width.toFloat(),
            targetValue = 2 * size.width.toFloat(),
            animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing))
        )
        background(
            brush = Brush.linearGradient(
                colors = listOf(
                    baseColor.copy(alpha = 0.7f),
                    highlightColor.copy(alpha = 0.9f),
                    baseColor.copy(alpha = 0.7f)
                ),
                start = Offset(startOffsetX, 0f),
                end = Offset(startOffsetX + size.width.toFloat(), size.height.toFloat())
            )
        ).onGloballyPositioned {
            size = it.size
        }
    } else {
        Modifier
    }
}
