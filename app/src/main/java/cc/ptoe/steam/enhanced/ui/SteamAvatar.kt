package cc.ptoe.steam.enhanced.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.util.LruCache
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cc.ptoe.steam.enhanced.app.SteamProfile
import cc.ptoe.steam.enhanced.core.model.SteamGuardAccount
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val avatarHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .callTimeout(15, TimeUnit.SECONDS).build()

private val avatarImages = object : LruCache<String, ByteArray>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ByteArray): Int = value.size
}

@Composable
internal fun SteamAvatar(account: SteamGuardAccount?, animated: Boolean) {
    SteamAvatar(account?.avatarUrl, account?.animatedAvatarUrl, account?.avatarFrameUrl, animated)
}

@Composable
internal fun SteamAvatar(
    avatarUrl: String?,
    animatedAvatarUrl: String? = null,
    avatarFrameUrl: String? = null,
    animated: Boolean = false,
) {
    Box(
        Modifier.size(if (animated) 84.dp else 40.dp).clip(CircleShape)
            .border(1.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MiuixIcons.ContactsCircle, null, Modifier.matchParentSize(), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        AvatarLayer(avatarUrl, Modifier.matchParentSize(), false)
        if (animated && animatedAvatarUrl != avatarUrl) {
            AvatarLayer(animatedAvatarUrl, Modifier.matchParentSize(), true)
        }
        if (animated) AvatarLayer(avatarFrameUrl, Modifier.matchParentSize(), true)
    }
}

@Composable
private fun AvatarLayer(url: String?, modifier: Modifier, animated: Boolean) {
    val bytes by produceState<ByteArray?>(url?.let(avatarImages::get), url) {
        value = url?.let(avatarImages::get)
        if (value != null) return@produceState
        val target = SteamProfile.imageUrl(url) ?: return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                avatarHttp.newCall(Request.Builder().url(target).build()).execute().use {
                    if (!it.isSuccessful) return@use null
                    val source = it.body.source()
                    if (source.request(3 * 1024 * 1024 + 1L)) null else source.readByteArray().also { data ->
                        avatarImages.put(checkNotNullUi(url), data)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    val data = bytes ?: return
    val frames by produceState<List<ApngFrame>?>(null, data, animated) {
        value = if (animated) withContext(Dispatchers.Default) { ApngDecoder.decode(data) } else null
    }
    val sequence = frames
    if (sequence != null && sequence.isNotEmpty()) {
        var frame by remember(sequence) { mutableIntStateOf(0) }
        LaunchedEffect(sequence) {
            while (true) {
                delay(sequence[frame].delayMillis)
                frame = (frame + 1) % sequence.size
            }
        }
        Image(sequence[frame].bitmap.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit)
    } else if (Build.VERSION.SDK_INT >= 28) {
        DecodedDrawable(data, modifier, animated)
    } else {
        val bitmap by produceState<android.graphics.Bitmap?>(null, data) {
            value = withContext(Dispatchers.Default) {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) null
                else android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
            }
        }
        bitmap?.let { Image(it.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit) }
    }
}

@RequiresApi(28)
@Composable
private fun DecodedDrawable(data: ByteArray, modifier: Modifier, animate: Boolean) {
    val drawable by produceState<android.graphics.drawable.Drawable?>(null, data) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(data))) { _, info, _ ->
                    val size = info.size
                    requireUi(size.width in 1..1024 && size.height in 1..1024)
                }
            }.getOrNull()
        }
    }
    val image = drawable ?: return
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        modifier = modifier,
        update = { view ->
            if (view.drawable != image) view.setImageDrawable(image)
            if (image is AnimatedImageDrawable) {
                if (animate) image.start() else image.stop()
            }
        },
        onRelease = {
            (it.drawable as? AnimatedImageDrawable)?.stop()
            it.setImageDrawable(null)
        },
    )
}
