package cc.ptoe.steam.enhanced.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.ptoe.steam.enhanced.R
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Instruction only; the real sign-in QR code is rendered separately below. */
@Composable
internal fun SteamScanAnimation() {
    val transition = rememberInfiniteTransition(label = "steam-scan")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Reverse),
        label = "scan-instruction",
    )
    val colors = MiuixTheme.colorScheme
    val description = stringResource(R.string.ui_qr_animation_description)
    Canvas(Modifier.size(168.dp, 140.dp).semantics { contentDescription = description }) {
        withTransform({ scale(size.width / 168f, size.height / 140f, pivot = Offset.Zero) }) {
            drawRoundRect(colors.surfaceContainerHigh, Offset(45f, 6f), Size(78f, 128f), CornerRadius(13f))
            drawRoundRect(colors.onSurfaceVariantActions, Offset(45f, 6f), Size(78f, 128f), CornerRadius(13f), style = Stroke(2.5f))
            drawRoundRect(colors.surface, Offset(51f, 26f), Size(66f, 88f), CornerRadius(5f))
            drawRoundRect(colors.onSurfaceVariantSummary, Offset(73f, 15f), Size(22f, 3f), CornerRadius(2f))
            drawRoundRect(colors.onSurfaceVariantSummary, Offset(72f, 124f), Size(24f, 3f), CornerRadius(2f))

            // A deliberately sparse QR symbol, not a second scannable code.
            for (origin in listOf(Offset(65f, 48f), Offset(91f, 48f), Offset(65f, 74f))) {
                drawRect(colors.onSurfaceVariantSummary, origin, Size(14f, 14f), style = Stroke(2f))
                drawRect(colors.onSurfaceVariantSummary, origin + Offset(4f, 4f), Size(6f, 6f))
            }
            drawRect(colors.onSurfaceVariantSummary, Offset(88f, 72f), Size(6f, 6f))
            drawRect(colors.onSurfaceVariantSummary, Offset(99f, 80f), Size(6f, 8f))
            drawRect(colors.onSurfaceVariantSummary, Offset(88f, 84f), Size(6f, 4f))

            val scanY = 40f + phase * 56f
            drawRoundRect(colors.primary.copy(alpha = 0.09f), Offset(58f, scanY - 8f), Size(52f, 16f), CornerRadius(4f))
            drawLine(colors.primary, Offset(58f, scanY), Offset(110f, scanY), 2.5f, StrokeCap.Round)
        }
    }
}
