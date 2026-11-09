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

/** A looping instruction illustration, independent of the actual approval result. */
@Composable
internal fun SteamApprovalAnimation() {
    val transition = rememberInfiniteTransition(label = "steam-approval")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_800, easing = LinearEasing), RepeatMode.Restart),
        label = "approval-instruction",
    )
    val colors = MiuixTheme.colorScheme
    val description = stringResource(R.string.ui_two_factor_animation_description)
    Canvas(Modifier.size(168.dp, 156.dp).semantics { contentDescription = description }) {
        withTransform({ scale(size.width / 168f, size.height / 156f, pivot = Offset.Zero) }) {
            drawRoundRect(colors.surfaceContainerHigh, Offset(45f, 10f), Size(78f, 132f), CornerRadius(13f))
            drawRoundRect(colors.onSurfaceVariantActions, Offset(45f, 10f), Size(78f, 132f), CornerRadius(13f), style = Stroke(2.5f))
            drawRoundRect(colors.surface, Offset(51f, 31f), Size(66f, 91f), CornerRadius(5f))
            drawRoundRect(colors.onSurfaceVariantSummary, Offset(73f, 19f), Size(22f, 3f), CornerRadius(2f))
            drawRoundRect(colors.onSurfaceVariantSummary, Offset(72f, 132f), Size(24f, 3f), CornerRadius(2f))

            drawArc(colors.onSurfaceVariantSummary, 180f, 180f, false, Offset(76f, 39f), Size(16f, 18f), style = Stroke(2.5f))
            drawRoundRect(colors.onSurfaceVariantSummary, Offset(73f, 48f), Size(22f, 18f), CornerRadius(4f))
            drawCircle(colors.surface, 2f, Offset(84f, 56f))

            val alpha = (phase / 0.16f).coerceIn(0f, 1f) * ((1f - phase) / 0.12f).coerceIn(0f, 1f)
            val slide = (1f - (phase / 0.2f).coerceIn(0f, 1f)) * 9f
            withTransform({ translate(top = slide) }) {
                drawRoundRect(colors.tertiaryContainer.copy(alpha = alpha), Offset(20f, 73f), Size(128f, 46f), CornerRadius(10f))
                drawRoundRect(colors.outline.copy(alpha = alpha), Offset(20f, 73f), Size(128f, 46f), CornerRadius(10f), style = Stroke(1.3f))
                drawRoundRect(colors.onSurfaceContainer.copy(alpha = alpha * 0.75f), Offset(32f, 84f), Size(66f, 4f), CornerRadius(2f))
                drawRoundRect(colors.onSurfaceVariantSummary.copy(alpha = alpha), Offset(32f, 96f), Size(45f, 3f), CornerRadius(2f))

                val tap = ((phase - 0.32f) / 0.36f).coerceIn(0f, 1f)
                drawCircle(colors.primary.copy(alpha = alpha * (1f - tap) * 0.22f), 18f + tap * 13f, Offset(131f, 116f))
                drawCircle(colors.primary.copy(alpha = alpha), 17f, Offset(131f, 116f))
                val check = ((phase - 0.4f) / 0.27f).coerceIn(0f, 1f)
                val start = Offset(123f, 115f)
                val middle = Offset(129f, 121f)
                val end = Offset(140f, 109f)
                if (check > 0f) drawLine(colors.onPrimary.copy(alpha = alpha), start, start + (middle - start) * (check / 0.35f).coerceAtMost(1f), 3f, StrokeCap.Round)
                if (check > 0.35f) drawLine(colors.onPrimary.copy(alpha = alpha), middle, middle + (end - middle) * ((check - 0.35f) / 0.65f), 3f, StrokeCap.Round)
            }
        }
    }
}
