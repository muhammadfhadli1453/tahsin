package com.tahsin.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.tahsin.app.settings.ThemeChoice

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6B3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F1C6),
    onPrimaryContainer = Color(0xFF00210E),
    secondary = Color(0xFF4E6355),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1E8D6),
    onSecondaryContainer = Color(0xFF0C1F14),
    tertiary = Color(0xFF8A6A00),
    onTertiary = Color.White,
    background = Color(0xFFFBFDF7),
    onBackground = Color(0xFF191C19),
    surface = Color(0xFFFBFDF7),
    onSurface = Color(0xFF191C19),
    surfaceVariant = Color(0xFFDDE5DB),
    onSurfaceVariant = Color(0xFF414941),
    outline = Color(0xFF717970),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BD5AB),
    onPrimary = Color(0xFF00391B),
    primaryContainer = Color(0xFF00522A),
    onPrimaryContainer = Color(0xFFB7F1C6),
    secondary = Color(0xFFB5CCBA),
    onSecondary = Color(0xFF213528),
    secondaryContainer = Color(0xFF374B3E),
    onSecondaryContainer = Color(0xFFD1E8D6),
    tertiary = Color(0xFFE9C46A),
    onTertiary = Color(0xFF3F2E00),
    background = Color(0xFF111411),
    onBackground = Color(0xFFE1E3DE),
    surface = Color(0xFF111411),
    onSurface = Color(0xFFE1E3DE),
    surfaceVariant = Color(0xFF414941),
    onSurfaceVariant = Color(0xFFC1C9BF),
    outline = Color(0xFF8B938A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Word-state colours, kept out of the Material roles so both themes stay legible. */
data class RecitationColors(
    val correct: Color,
    val onCorrect: Color,
    val wrong: Color,
    val onWrong: Color,
    val missed: Color,
    val onMissed: Color,
    val current: Color,
    val onCurrent: Color,
    val hidden: Color,
)

private val LightRecitationColors = RecitationColors(
    correct = Color(0xFFCDEBD6),
    onCorrect = Color(0xFF0C3B1E),
    wrong = Color(0xFFFFD6D2),
    onWrong = Color(0xFF5C1512),
    missed = Color(0xFFFFE6B8),
    onMissed = Color(0xFF4A3300),
    current = Color(0xFF1B6B3A),
    onCurrent = Color.White,
    hidden = Color(0xFFD6DCD4),
)

private val DarkRecitationColors = RecitationColors(
    correct = Color(0xFF1E4630),
    onCorrect = Color(0xFFB7F1C6),
    wrong = Color(0xFF5C1F1C),
    onWrong = Color(0xFFFFD6D2),
    missed = Color(0xFF4A3A10),
    onMissed = Color(0xFFFFE6B8),
    current = Color(0xFF9BD5AB),
    onCurrent = Color(0xFF00391B),
    hidden = Color(0xFF2A2F2A),
)

val LocalRecitationColors = androidx.compose.runtime.staticCompositionLocalOf { LightRecitationColors }

@Composable
fun TahsinTheme(
    themeChoice: ThemeChoice = ThemeChoice.System,
    content: @Composable () -> Unit,
) {
    val dark = when (themeChoice) {
        ThemeChoice.System -> isSystemInDarkTheme()
        ThemeChoice.Light -> false
        ThemeChoice.Dark -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalRecitationColors provides if (dark) DarkRecitationColors else LightRecitationColors,
        LocalTajweedPalette provides if (dark) DarkTajweedPalette else LightTajweedPalette,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = TahsinTypography,
            content = content,
        )
    }
}
