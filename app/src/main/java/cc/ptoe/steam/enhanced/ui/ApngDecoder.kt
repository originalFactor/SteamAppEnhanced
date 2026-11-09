package cc.ptoe.steam.enhanced.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

internal data class ApngFrame(val bitmap: Bitmap, val delayMillis: Long)

internal object ApngDecoder {
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private const val MAX_PIXELS = 8_000_000L

    private data class Frame(
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
        val delay: Long,
        val dispose: Int,
        val blend: Int,
        val chunks: MutableList<ByteArray> = mutableListOf(),
    )

    fun decode(bytes: ByteArray): List<ApngFrame>? {
        if (bytes.size < 33 || !bytes.copyOfRange(0, 8).contentEquals(signature)) return null
        val data = ByteBuffer.wrap(bytes).apply { position(8) }
        var header: ByteArray? = null
        var width = 0
        var height = 0
        var count = 0
        var sawImage = false
        var firstFrameUsesIdat = false
        val global = mutableListOf<Pair<String, ByteArray>>()
        val frames = mutableListOf<Frame>()
        while (data.remaining() >= 12) {
            val length = data.int
            if (length < 0 || length > data.remaining() - 8) return null
            val type = ByteArray(4).also { data.get(it) }.decodeToString()
            val payload = ByteArray(length).also { data.get(it) }
            val crc = data.int
            if (crc != checksum(type.encodeToByteArray(), payload)) return null
            when (type) {
                "IHDR" -> {
                    if (length != 13 || header != null) return null
                    header = payload
                    val dimensions = ByteBuffer.wrap(payload)
                    width = dimensions.int
                    height = dimensions.int
                    if (width !in 1..512 || height !in 1..512) return null
                }
                "acTL" -> {
                    if (length != 8 || sawImage || count != 0) return null
                    count = ByteBuffer.wrap(payload).int
                    if (count !in 1..160 || width.toLong() * height * count > MAX_PIXELS) return null
                }
                "PLTE", "tRNS" -> if (!sawImage) global += type to payload
                "fcTL" -> {
                    if (length != 26 || frames.size >= count) return null
                    val metadata = ByteBuffer.wrap(payload)
                    metadata.int
                    val w = metadata.int
                    val h = metadata.int
                    val x = metadata.int
                    val y = metadata.int
                    val numerator = metadata.short.toInt() and 0xffff
                    val denominator = (metadata.short.toInt() and 0xffff).let { if (it == 0) 100 else it }
                    val dispose = metadata.get().toInt()
                    val blend = metadata.get().toInt()
                    if (w !in 1..width || h !in 1..height || x !in 0..(width - w) || y !in 0..(height - h) ||
                        dispose !in 0..2 || blend !in 0..1
                    ) return null
                    if (frames.isEmpty()) firstFrameUsesIdat = !sawImage
                    frames += Frame(w, h, x, y, (1_000L * numerator / denominator).coerceIn(16L, 10_000L), dispose, blend)
                }
                "IDAT" -> {
                    sawImage = true
                    if (firstFrameUsesIdat && frames.size == 1) frames.first().chunks += payload
                }
                "fdAT" -> {
                    if (frames.isEmpty() || length < 4) return null
                    frames.last().chunks += payload.copyOfRange(4, payload.size)
                }
                "IEND" -> break
            }
        }
        val ihdr = header ?: return null
        if (frames.isEmpty() || frames.size != count || frames.any { it.chunks.isEmpty() }) return null

        val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        val paint = Paint()
        val output = mutableListOf<ApngFrame>()
        try {
            for (frame in frames) {
                val frameImage = decodeFrame(frame, ihdr, global) ?: break
                val previous = if (frame.dispose == 2) canvasBitmap.copy(Bitmap.Config.ARGB_8888, false) else null
                paint.xfermode = if (frame.blend == 0) PorterDuffXfermode(PorterDuff.Mode.SRC) else null
                canvas.drawBitmap(frameImage, frame.x.toFloat(), frame.y.toFloat(), paint)
                frameImage.recycle()
                output += ApngFrame(checkNotNullUi(canvasBitmap.copy(Bitmap.Config.ARGB_8888, false)), frame.delay)
                if (frame.dispose == 1) {
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    canvas.drawRect(frame.x.toFloat(), frame.y.toFloat(),
                        (frame.x + frame.width).toFloat(), (frame.y + frame.height).toFloat(), paint)
                } else if (previous != null) {
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                    canvas.drawBitmap(previous, 0f, 0f, paint)
                    previous.recycle()
                }
            }
        } finally {
            canvasBitmap.recycle()
        }
        if (output.size == count) return output
        output.forEach { it.bitmap.recycle() }
        return null
    }

    private fun decodeFrame(frame: Frame, header: ByteArray, global: List<Pair<String, ByteArray>>): Bitmap? {
        val stream = ByteArrayOutputStream()
        DataOutputStream(stream).use { output ->
            output.write(signature)
            val ihdr = header.copyOf()
            ByteBuffer.wrap(ihdr).putInt(frame.width).putInt(frame.height)
            output.chunk("IHDR", ihdr)
            global.forEach { (type, payload) -> output.chunk(type, payload) }
            frame.chunks.forEach { output.chunk("IDAT", it) }
            output.chunk("IEND", byteArrayOf())
        }
        val encoded = stream.toByteArray()
        return BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
    }

    private fun DataOutputStream.chunk(type: String, bytes: ByteArray) {
        val name = type.encodeToByteArray()
        writeInt(bytes.size)
        write(name)
        write(bytes)
        writeInt(checksum(name, bytes))
    }

    private fun checksum(type: ByteArray, bytes: ByteArray): Int =
        CRC32().apply { update(type); update(bytes) }.value.toInt()
}
