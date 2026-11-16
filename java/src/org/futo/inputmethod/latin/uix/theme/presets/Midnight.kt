package org.futo.inputmethod.latin.uix.theme.presets

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.extendedDarkColorScheme
import org.futo.inputmethod.latin.uix.theme.ThemeOption
import org.futo.inputmethod.latin.uix.theme.selector.ThemePreview

// Deep slate with a soft blue accent; the default look of this build.
private val darkScheme = extendedDarkColorScheme(
    primary=Color(0xFF7AA2F7),
    onPrimary=Color(0xFF0E1424),
    primaryContainer=Color(0xFF1F2B4A),
    onPrimaryContainer=Color(0xFFDCE6FF),
    secondary=Color(0xFFA9B8DA),
    onSecondary=Color(0xFF1A2033),
    secondaryContainer=Color(0xFF1C1F28),
    onSecondaryContainer=Color(0xFF8B93A7),
    tertiary=Color(0xFF9ECBFF),
    onTertiary=Color(0xFF102A44),
    tertiaryContainer=Color(0xFF26405F),
    onTertiaryContainer=Color(0xFFD6E8FF),
    error=Color(0xFFF7768E),
    onError=Color(0xFF3B0F18),
    errorContainer=Color(0xFF6E2232),
    onErrorContainer=Color(0xFFFFD9DF),
    outline=Color(0xFF6B7389),
    outlineVariant=Color(0xFF232733),
    surface=Color(0xFF12141A),
    onSurface=Color(0xFFE9ECF3),
    onSurfaceVariant=Color(0xFFB4BBCC),
    surfaceContainerHighest=Color(0xFF2C3140),
    shadow=Color(0xFF000000).copy(alpha = 0.7f),
    keyboardSurface=Color(0xFF12141A),
    keyboardSurfaceDim=Color(0xFF0D0F14),
    keyboardContainer=Color(0xFF262A35),
    keyboardContainerVariant=Color(0xFF1C1F28),
    onKeyboardContainer=Color(0xFFE9ECF3),
    keyboardPress=Color(0xFF3A4152),
    keyboardFade0=Color(0xFF12141A),
    keyboardFade1=Color(0xFF12141A),
    primaryTransparent=Color(0xFF7AA2F7).copy(alpha = 0.3f),
    onSurfaceTransparent=Color(0xFFE9ECF3).copy(alpha = 0.1f),
)

val Midnight = ThemeOption(
    dynamic = false,
    key = "Midnight",
    name = R.string.theme_midnight,
    available = { true }
) {
    darkScheme
}

@Composable
@Preview
private fun PreviewTheme() {
    ThemePreview(Midnight)
}
