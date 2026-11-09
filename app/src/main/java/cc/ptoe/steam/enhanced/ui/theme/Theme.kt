package cc.ptoe.steam.enhanced.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import cc.ptoe.steam.enhanced.app.Appearance
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme

@Composable
internal fun SteamEnhancedTheme(
    appearance: Appearance,
    content: @Composable () -> Unit,
) {
    val controller = remember(appearance) {
        ThemeController(
            colorSchemeMode = when (appearance) {
                Appearance.SYSTEM -> ColorSchemeMode.System
                Appearance.LIGHT -> ColorSchemeMode.Light
                Appearance.DARK -> ColorSchemeMode.Dark
            },
            darkColors = greyDarkColorScheme(),
        )
    }
    MiuixTheme(controller = controller, content = content)
}

/** Separate page, card and control surfaces with neutral tones in night mode. */
private fun greyDarkColorScheme() = darkColorScheme(
    primary = Color(0xFFE5E5E5),
    onPrimary = Color(0xFF202020),
    primaryVariant = Color(0xFFD0D0D0),
    onPrimaryVariant = Color(0xFF202020),
    disabledPrimary = Color(0xFF383838),
    disabledOnPrimary = Color(0xFF858585),
    disabledPrimaryButton = Color(0xFF383838),
    disabledOnPrimaryButton = Color(0xFF858585),
    disabledPrimarySlider = Color(0xFF626262),
    primaryContainer = Color(0xFF4A4A4A),
    onPrimaryContainer = Color(0xFFF0F0F0),
    secondary = Color(0xFF505050),
    onSecondary = Color(0xFFF2F2F2),
    secondaryVariant = Color(0xFF414141),
    onSecondaryVariant = Color(0xFFE0E0E0),
    disabledSecondary = Color(0xFF383838),
    disabledOnSecondary = Color(0xFF858585),
    disabledSecondaryVariant = Color(0xFF383838),
    disabledOnSecondaryVariant = Color(0xFF858585),
    secondaryContainer = Color(0xFF3D3D3D),
    onSecondaryContainer = Color(0xFFB0B0B0),
    secondaryContainerVariant = Color(0xFF484848),
    onSecondaryContainerVariant = Color(0xFFBDBDBD),
    tertiaryContainer = Color(0xFF454545),
    onTertiaryContainer = Color(0xFFF0F0F0),
    tertiaryContainerVariant = Color(0xFF505050),
    background = Color(0xFF202020),
    onBackground = Color(0xFFF2F2F2),
    onBackgroundVariant = Color(0xFFA8A8A8),
    surface = Color(0xFF202020),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF282828),
    onSurfaceSecondary = Color(0xFFD0D0D0),
    onSurfaceVariantSummary = Color(0xFFAAAAAA),
    onSurfaceVariantActions = Color(0xFF999999),
    disabledOnSurface = Color(0xFF7D7D7D),
    surfaceContainer = Color(0xFF2C2C2C),
    onSurfaceContainer = Color(0xFFF0F0F0),
    onSurfaceContainerVariant = Color(0xFFA0A0A0),
    surfaceContainerHigh = Color(0xFF353535),
    onSurfaceContainerHigh = Color(0xFFB0B0B0),
    surfaceContainerHighest = Color(0xFF414141),
    onSurfaceContainerHighest = Color(0xFFF0F0F0),
    outline = Color(0xFF555555),
    dividerLine = Color(0xFF414141),
    windowDimming = Color.Black.copy(alpha = 0.35f),
    sliderKeyPoint = Color(0x66888888),
    sliderKeyPointForeground = Color(0xFFE5E5E5),
)
