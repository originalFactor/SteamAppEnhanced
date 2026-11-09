package cc.ptoe.steam.enhanced.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Miuix button geometry/colors with an application-owned long-press fill. */
@Composable
internal fun HoldToConfirmButton(label: String, modifier: Modifier = Modifier, onConfirm: () -> Unit) {
    val colors = ButtonDefaults.textButtonColorsPrimary()
    val interactions = remember { MutableInteractionSource() }
    val progress = remember { Animatable(0f) }
    val holdDuration = LocalViewConfiguration.current.longPressTimeoutMillis.toInt()
    LaunchedEffect(interactions, holdDuration) {
        var fill: Job? = null
        interactions.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    fill?.cancel()
                    fill = launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(holdDuration, easing = LinearEasing))
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    fill?.cancel()
                    progress.snapTo(0f)
                }
            }
        }
    }
    Box(
        modifier.defaultMinSize(minWidth = ButtonDefaults.MinWidth, minHeight = ButtonDefaults.MinHeight)
            .squircleSurface(colors.disabledColor, ButtonDefaults.CornerRadius)
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Button,
                onClick = {},
                onLongClickLabel = label,
                onLongClick = onConfirm,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = colors.disabledTextColor, style = MiuixTheme.textStyles.button,
            modifier = Modifier.fillMaxWidth().padding(ButtonDefaults.InsideMargin),
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier.matchParentSize().clearAndSetSemantics {}.drawWithContent {
                clipRect(right = size.width * progress.value) {
                    drawRect(colors.color)
                    this@drawWithContent.drawContent()
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label, color = colors.textColor, style = MiuixTheme.textStyles.button,
                modifier = Modifier.fillMaxWidth().padding(ButtonDefaults.InsideMargin),
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
