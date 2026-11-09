package cc.ptoe.steam.enhanced.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import cc.ptoe.steam.enhanced.net.SteamTime
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator

/** Read subsecond server time each frame; reset exactly at the next code period. */
@Composable
internal fun SteamCodeProgress(time: SteamTime, active: Boolean = true) {
    var millis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        if (active) while (true) withFrameNanos { millis = System.currentTimeMillis() }
    }
    val serverMillis = millis + time.offset() * 1_000L
    val progress = (30_000L - Math.floorMod(serverMillis, 30_000L)) / 30_000f
    LinearProgressIndicator(Modifier.fillMaxWidth(), progress = progress)
}
