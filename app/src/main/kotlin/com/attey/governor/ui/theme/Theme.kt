package com.attey.governor.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Fixed palette: status colors must not depend on the wallpaper.
private val Mint = Color(0xFF5FD3B4)
private val MintDim = Color(0xFF1E3B36)
private val Amber = Color(0xFFFFC46B)
private val AmberDim = Color(0xFF3A2F1B)
private val Coral = Color(0xFFFF6B6B)

private val Dark = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00201A),
    primaryContainer = MintDim,
    onPrimaryContainer = Mint,
    secondary = Color(0xFF8FB6FF),
    onSecondary = Color(0xFF00204B),
    tertiary = Amber,
    onTertiary = Color(0xFF2A1D00),
    tertiaryContainer = AmberDim,
    onTertiaryContainer = Amber,
    error = Coral,
    onError = Color(0xFF3A0A0A),
    background = Color(0xFF0A0C0E),
    onBackground = Color(0xFFE4E8EB),
    surface = Color(0xFF0A0C0E),
    onSurface = Color(0xFFE4E8EB),
    surfaceVariant = Color(0xFF171B1F),
    onSurfaceVariant = Color(0xFF9BA5AE),
    outline = Color(0xFF3A424A),
    outlineVariant = Color(0xFF262C32),
    // Override Material's violet container defaults.
    secondaryContainer = Color(0xFF1B2735),
    onSecondaryContainer = Color(0xFF8FB6FF),
    surfaceContainerLowest = Color(0xFF06080A),
    surfaceContainerLow = Color(0xFF101418),
    surfaceContainer = Color(0xFF141A1E),
    surfaceContainerHigh = Color(0xFF1A2126),
    surfaceContainerHighest = Color(0xFF222A30),
    inverseSurface = Color(0xFFE4E8EB),
    inverseOnSurface = Color(0xFF11181D),
    surfaceTint = Mint,
    scrim = Color(0xFF000000),
)

private val Light = lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8EFE1),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF2B5CA8),
    tertiary = Color(0xFF7A5300),
    tertiaryContainer = Color(0xFFFFE3B0),
    onTertiaryContainer = Color(0xFF2A1D00),
    error = Color(0xFFB3261E),
    background = Color(0xFFF7F9FA),
    onBackground = Color(0xFF11181D),
    surface = Color(0xFFF7F9FA),
    onSurface = Color(0xFF11181D),
    surfaceVariant = Color(0xFFE6EBEE),
    onSurfaceVariant = Color(0xFF4B555D),
    outline = Color(0xFFA8B2B9),
    // Override Material's violet container defaults.
    secondaryContainer = Color(0xFFD8E4F7),
    onSecondaryContainer = Color(0xFF15305C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F6),
    surfaceContainer = Color(0xFFEBEFF2),
    surfaceContainerHigh = Color(0xFFE4E9EC),
    surfaceContainerHighest = Color(0xFFDDE3E7),
    surfaceTint = Color(0xFF00695C),
)

// Tabular figures keep live readouts stable without using monospace body text.
private val GovernorTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(
            fontWeight = FontWeight.Light, fontSize = 36.sp, fontFeatureSettings = "tnum",
        ),
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.Light, fontFeatureSettings = "tnum",
        ),
        bodyMedium = base.bodyMedium.copy(fontFeatureSettings = "tnum"),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
        labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
fun GovernorTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) Dark else Light
    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = GovernorTypography) {
        // Paint the window immediately while the initial root probe runs.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = scheme.background,
            content = content,
        )
    }
}
