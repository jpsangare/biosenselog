package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = SleekTealAccent,
    onPrimary = SleekTeal950,
    primaryContainer = SleekTeal800,
    onPrimaryContainer = SleekTealAccent,
    secondary = SleekTeal700,
    onSecondary = Color.White,
    background = SleekDarkBackground,
    onBackground = Color.White,
    surface = SleekDarkSurface,
    onSurface = Color.White,
    surfaceVariant = SleekDarkSurfaceVariant,
    onSurfaceVariant = Color.White.copy(alpha = 0.8f)
  )

private val LightColorScheme =
  lightColorScheme(
    primary = SleekTeal600,
    onPrimary = Color.White,
    primaryContainer = SleekPrimaryContainer,
    onPrimaryContainer = SleekTeal800,
    secondary = SleekTeal700,
    onSecondary = Color.White,
    background = SleekBackground,
    onBackground = SleekTeal950,
    surface = Color.White,
    onSurface = SleekTeal950,
    surfaceVariant = SleekSurfaceVariant,
    onSurfaceVariant = SleekTeal900,
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Set to false by default to respect custom brand colors
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
