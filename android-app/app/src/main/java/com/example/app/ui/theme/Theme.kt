package com.example.app.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = CalmSage,
    secondary = DeepTeal,
    tertiary = CalmMint,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

// Every role is set. Material fills unset ones from its default lavender
// palette, so the bottom-bar highlight, segmented buttons, dialogs, slider
// tracks, text-field labels and the Settings avatar were all tinted purple in
// an otherwise teal app.
private val LightColorScheme = lightColorScheme(
    primary = DeepTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00382F),
    secondary = CalmSage,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFFCDE8E4),
    onSecondaryContainer = Color(0xFF0B3A33),
    tertiary = SoftBlue,
    background = CalmMint,
    onBackground = TextGray,
    surface = Color.White,
    onSurface = TextGray,
    surfaceVariant = Color(0xFFE4EEEC),
    onSurfaceVariant = TextMuted,
    surfaceTint = DeepTeal,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5FAF9),
    surfaceContainer = Color(0xFFEFF6F5),
    surfaceContainerHigh = Color(0xFFE9F2F0),
    surfaceContainerHighest = Color(0xFFE3EDEB),
    inverseSurface = Color(0xFF2B3A3D),
    inverseOnSurface = Color(0xFFEAF4F2),
    inversePrimary = Color(0xFF80CBC4),
    outline = Color(0xFF6F8986),
    outlineVariant = Color(0xFFBFD3D0),
    error = AlertRed,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

@Composable
fun AppTheme(
    // Light mode is the brand: the calm palette doesn't translate to dark.
    // We deliberately ignore the system setting.
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}