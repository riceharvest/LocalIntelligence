package dev.localintelligence.app.ui

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

/**
 * Material 3 theming. One theme, two schemes, dynamic colour on 12+.
 *
 * Deliberately not a design system: this app is a debug tool before it is a
 * product, and a second agent adding a `Typography` scale here would be building
 * something nobody has agreed on. Colours that *carry meaning* are not in this
 * file — success and failure use the Material error/primary roles via
 * [dev.localintelligence.app.ui.ToolActivityCard] so there is exactly one
 * definition of "red" in the app.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5C4A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB2F1D4),
    onPrimaryContainer = Color(0xFF002115),
    secondary = Color(0xFF4D6357),
    tertiary = Color(0xFF3F6375),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF97D5B9),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF15503A),
    onPrimaryContainer = Color(0xFFB2F1D4),
    secondary = Color(0xFFB5CCBF),
    tertiary = Color(0xFFA7CDDF),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun LocalIntelligenceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Off by default so a debug build looks the same on every device. */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
