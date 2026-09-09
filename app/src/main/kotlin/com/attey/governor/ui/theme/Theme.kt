package com.attey.governor.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Fixed palette: status colors must not depend on the wallpaper.
private val Mint = Color(0xFF92D7BE)
private val MintDim = Color(0xFF19382E)
private val Amber = Color(0xFFE9B86B)
private val AmberDim = Color(0xFF3B2D19)
private val Coral = Color(0xFFEF7774)

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
    background = Color(0xFF0E1011),
    onBackground = Color(0xFFE8E7E2),
    surface = Color(0xFF111315),
    onSurface = Color(0xFFE8E7E2),
    surfaceVariant = Color(0xFF1B1E20),
    onSurfaceVariant = Color(0xFF9FA4A2),
    outline = Color(0xFF444A48),
    outlineVariant = Color(0xFF292E2C),
    secondaryContainer = Color(0xFF1B2735),
    onSecondaryContainer = Color(0xFF8FB6FF),
    surfaceContainerLowest = Color(0xFF0A0C0D),
    surfaceContainerLow = Color(0xFF141718),
    surfaceContainer = Color(0xFF181B1D),
    surfaceContainerHigh = Color(0xFF202426),
    surfaceContainerHighest = Color(0xFF292D30),
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
    background = Color(0xFFF3F1EC),
    onBackground = Color(0xFF1A1C1B),
    surface = Color(0xFFF7F5F0),
    onSurface = Color(0xFF1A1C1B),
    surfaceVariant = Color(0xFFE7E5DF),
    onSurfaceVariant = Color(0xFF555B58),
    outline = Color(0xFFA8ADA9),
    secondaryContainer = Color(0xFFD8E4F7),
    onSecondaryContainer = Color(0xFF15305C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0EEE9),
    surfaceContainer = Color(0xFFEAE8E3),
    surfaceContainerHigh = Color(0xFFE2E0DA),
    surfaceContainerHighest = Color(0xFFD9D7D1),
    surfaceTint = Color(0xFF00695C),
)

// Monospace readouts and tabular body figures do not shift as live values change.
private val GovernorTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            letterSpacing = (-0.2).sp,
        ),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Normal,
            fontSize = 38.sp,
            fontFeatureSettings = "tnum",
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontFeatureSettings = "tnum",
        ),
        bodyMedium = base.bodyMedium.copy(fontFeatureSettings = "tnum"),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    )
}

private val GovernorShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

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
    MaterialTheme(
        colorScheme = scheme,
        typography = GovernorTypography,
        shapes = GovernorShapes,
    ) {
        // Paint the window immediately while the initial root probe runs.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = scheme.background,
            content = content,
        )
    }
}
