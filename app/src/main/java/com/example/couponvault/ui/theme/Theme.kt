package com.example.couponvault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = ClayPrimary,
    onPrimary = ClayOnPrimary,
    primaryContainer = StatusUnscannedContainerLight,
    onPrimaryContainer = StatusUnscannedTextLight,
    secondary = ClayPrimary,
    onSecondary = ClayOnPrimary,
    secondaryContainer = StatusReviewContainerLight,
    onSecondaryContainer = StatusReviewTextLight,
    background = PaperBackground,
    onBackground = PaperOnBackground,
    surface = PaperSurface,
    onSurface = PaperOnBackground,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = PaperOnSurfaceVariant,
    outline = PaperOutline,
    outlineVariant = PaperOutline,
    error = StatusFailedTextLight,
    onError = Color.White,
    errorContainer = StatusFailedContainerLight,
    onErrorContainer = StatusFailedTextLight
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkClayPrimary,
    onPrimary = DarkClayOnPrimary,
    primaryContainer = StatusUnscannedContainerDark,
    onPrimaryContainer = StatusUnscannedTextDark,
    secondary = DarkClayPrimary,
    onSecondary = DarkClayOnPrimary,
    secondaryContainer = StatusReviewContainerDark,
    onSecondaryContainer = StatusReviewTextDark,
    background = DarkPaperBackground,
    onBackground = DarkPaperOnBackground,
    surface = DarkPaperSurface,
    onSurface = DarkPaperOnBackground,
    surfaceVariant = DarkPaperSurfaceVariant,
    onSurfaceVariant = DarkPaperOnSurfaceVariant,
    outline = DarkPaperOutline,
    outlineVariant = DarkPaperOutline,
    error = StatusFailedTextDark,
    onError = Color(0xFF2A1208),
    errorContainer = StatusFailedContainerDark,
    onErrorContainer = StatusFailedTextDark
)

@Composable
fun CouponVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Ignored: dynamic color is disabled per specification
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
