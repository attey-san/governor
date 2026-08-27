package com.attey.governor.ui.theme

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

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7FB2FF),
    onPrimary = Color(0xFF002E66),
    primaryContainer = Color(0xFF13427A),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFFB7C4D4),
    onSecondary = Color(0xFF212E3D),
    secondaryContainer = Color(0xFF384454),
    onSecondaryContainer = Color(0xFFD3E0F0),
    tertiary = Color(0xFFE0B97A),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF161A1F),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF1E232A),
    onSurfaceVariant = Color(0xFFC1C7CF),
    outline = Color(0xFF8B9198),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF005AC1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF535F70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E0F0),
    onSecondaryContainer = Color(0xFF101C2B),
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

@Composable
fun GovernorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
