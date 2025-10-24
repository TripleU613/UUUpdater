package com.tripleu.updater.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.tripleu.updater.util.isDark


@Composable
fun AppTheme(
	darkTheme: Boolean,
	dynamicColor: Boolean = true,
	content: @Composable () -> Unit
) {
	val colorScheme = when {
		dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
			val context = LocalContext.current
			if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
		}
		darkTheme -> darkColorScheme()
		else -> lightColorScheme()
	}

	val view = LocalView.current
	if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as Activity
            val window = activity.window
            val insetsController = WindowCompat.getInsetsController(window, view)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                window.navigationBarColor = colorScheme.statusBarColor().toArgb()
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }

            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.statusBarColor().toArgb()
            insetsController.isAppearanceLightStatusBars = !darkTheme
        }
	}

	MaterialTheme(colorScheme = colorScheme, content = content)
}

fun ColorScheme.statusBarColor() = surfaceColorAtElevation(3.dp)

fun isDarkTheme(theme: Int): Boolean {
	if (theme == 1) return true
	if (theme == 2) return false
	return isDark()
}
