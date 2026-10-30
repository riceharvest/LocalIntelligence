package dev.localintelligence.app.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.localintelligence.app.LocalIntelligenceApp
import dev.localintelligence.core.settings.CoreSettings

/**
 * Material 3 theming: the colour and the light/dark choice from Settings
 * (Theme colour, Appearance), with the wallpaper's colours on Android 12+.
 *
 * Colours that *carry meaning* are not in this file — success and failure use
 * the Material error/primary roles via
 * [dev.localintelligence.app.ui.components.ToolStepRow] so there is exactly one
 * definition of "red" in the app.
 */
private class Palette(val light: ColorScheme, val dark: ColorScheme)

private val Green = Palette(
    light = lightColorScheme(
        primary = Color(0xFF2E5C4A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB2F1D4),
        onPrimaryContainer = Color(0xFF002115),
        secondary = Color(0xFF4D6357),
        tertiary = Color(0xFF3F6375),
        error = Color(0xFFBA1A1A),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    ),
    dark = darkColorScheme(
        primary = Color(0xFF97D5B9),
        onPrimary = Color(0xFF003828),
        primaryContainer = Color(0xFF15503A),
        onPrimaryContainer = Color(0xFFB2F1D4),
        secondary = Color(0xFFB5CCBF),
        tertiary = Color(0xFFA7CDDF),
        error = Color(0xFFFFB4AB),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    ),
)

private val Blue = Palette(
    light = lightColorScheme(
        primary = Color(0xFF3B5BA9),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDAE2FF),
        onPrimaryContainer = Color(0xFF001946),
        secondary = Color(0xFF575E71),
        tertiary = Color(0xFF715573),
    ),
    dark = darkColorScheme(
        primary = Color(0xFFB1C5FF),
        onPrimary = Color(0xFF0A2B6C),
        primaryContainer = Color(0xFF284383),
        onPrimaryContainer = Color(0xFFDAE2FF),
        secondary = Color(0xFFBFC6DC),
        tertiary = Color(0xFFDFBBDE),
    ),
)

private val Purple = Palette(
    light = lightColorScheme(
        primary = Color(0xFF6750A4),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFEADDFF),
        onPrimaryContainer = Color(0xFF21005D),
        secondary = Color(0xFF625B71),
        tertiary = Color(0xFF7D5260),
    ),
    dark = darkColorScheme(
        primary = Color(0xFFD0BCFF),
        onPrimary = Color(0xFF381E72),
        primaryContainer = Color(0xFF4F378B),
        onPrimaryContainer = Color(0xFFEADDFF),
        secondary = Color(0xFFCCC2DC),
        tertiary = Color(0xFFEFB8C8),
    ),
)

private val Orange = Palette(
    light = lightColorScheme(
        primary = Color(0xFF8B5000),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDCBE),
        onPrimaryContainer = Color(0xFF2C1600),
        secondary = Color(0xFF725A42),
        tertiary = Color(0xFF58633A),
    ),
    dark = darkColorScheme(
        primary = Color(0xFFFFB870),
        onPrimary = Color(0xFF4A2800),
        primaryContainer = Color(0xFF6A3C00),
        onPrimaryContainer = Color(0xFFFFDCBE),
        secondary = Color(0xFFE1C1A4),
        tertiary = Color(0xFFC0CC9A),
    ),
)

private val Pink = Palette(
    light = lightColorScheme(
        primary = Color(0xFF984061),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFD9E2),
        onPrimaryContainer = Color(0xFF3E001D),
        secondary = Color(0xFF74565F),
        tertiary = Color(0xFF7C5635),
    ),
    dark = darkColorScheme(
        primary = Color(0xFFFFB0C8),
        onPrimary = Color(0xFF5E1133),
        primaryContainer = Color(0xFF7B2949),
        onPrimaryContainer = Color(0xFFFFD9E2),
        secondary = Color(0xFFE2BDC6),
        tertiary = Color(0xFFEFBD94),
    ),
)

private fun palette(id: String): Palette = when (id) {
    CoreSettings.THEME_BLUE -> Blue
    CoreSettings.THEME_PURPLE -> Purple
    CoreSettings.THEME_ORANGE -> Orange
    CoreSettings.THEME_PINK -> Pink
    else -> Green
}

/** The colour a theme choice is shown as in Settings: its light primary, or the wallpaper's. */
fun themeSwatch(id: String, context: Context): Color =
    if (id == CoreSettings.THEME_WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(context).primary
    } else {
        palette(id).light.primary
    }

/** The colour for a mark drawn on [themeSwatch], so a check stays readable on light swatches. */
fun themeSwatchOn(id: String, context: Context): Color =
    if (id == CoreSettings.THEME_WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(context).onPrimary
    } else {
        palette(id).light.onPrimary
    }

@Composable
fun LocalIntelligenceTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = (context.applicationContext as LocalIntelligenceApp).container.settings
    // Any settings write bumps this, so a new colour shows on every open screen at once.
    val revision by settings.changes.collectAsState()
    val colorId = remember(revision) { settings[CoreSettings.THEME_COLOR] }
    val appearance = remember(revision) { settings[CoreSettings.APPEARANCE] }
    val systemDark = isSystemInDarkTheme()
    val dark = when (appearance) {
        CoreSettings.APPEARANCE_LIGHT -> false
        CoreSettings.APPEARANCE_DARK -> true
        else -> systemDark
    }
    val colors = when {
        colorId == CoreSettings.THEME_WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> palette(colorId).dark
        else -> palette(colorId).light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
