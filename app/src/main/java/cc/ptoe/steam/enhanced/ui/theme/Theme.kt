package cc.ptoe.steam.enhanced.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import cc.ptoe.steam.enhanced.app.Appearance
import cc.ptoe.steam.enhanced.app.Skin
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
@Composable
internal fun SteamEnhancedTheme(
    appearance: Appearance,
    skin: Skin,
    content: @Composable () -> Unit,
) {
    val controller = remember(appearance, skin) {
        ThemeController(
            colorSchemeMode = when (appearance) {
                Appearance.SYSTEM -> ColorSchemeMode.MonetSystem
                Appearance.LIGHT -> ColorSchemeMode.MonetLight
                Appearance.DARK -> ColorSchemeMode.MonetDark
            },
            keyColor = when (skin) {
                Skin.STEAM -> Color(0xFF34658D)
                Skin.NEON -> Color(0xFF8C40C9)
                Skin.PIXEL -> Color(0xFF557C48)
            },
        )
    }
    MiuixTheme(controller = controller, content = content)
}
