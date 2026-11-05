package local.spw.mv

import org.jetbrains.skia.*
import kotlin.math.ceil
import kotlin.math.floor

class DanmakuSprite(val image: Image?, val blob: TextBlob?, val left: Float, val top: Float, val bytes: Int) : AutoCloseable {
    override fun close() { image?.close(); blob?.close() }
}

/** Cache the completed fill and outline, rather than rerasterizing text on every animation frame. */
class DanmakuSprites(private val font: Font, private val scale: Float, private val maxBytes: Int = 16 * 1024 * 1024) : AutoCloseable {
    private data class Key(val text: String, val size: Int, val color: Int)
    private val entries = java.util.LinkedHashMap<Key, DanmakuSprite>(128, .75f, true)
    private val paint = Paint().apply { isAntiAlias = true }
    var usedBytes = 0
        private set

    fun get(text: String, size: Int, color: Int): DanmakuSprite {
        val key = Key(text, size, color)
        entries[key]?.let { return it }
        font.size = size.coerceAtMost(30) * scale
        val builder = TextBlobBuilder()
        val blob = try { requireNotNull(builder.appendRun(font, text, 0f, 0f).build()) } finally { builder.close() }
        val bounds = font.measureText(text)
        val pad = ceil(2 * scale + 1).toInt()
        val left = floor(bounds.left) - pad
        val top = floor(bounds.top) - pad
        val width = (ceil(bounds.right) - left + pad).toInt().coerceAtLeast(1)
        val height = (ceil(bounds.bottom) - top + pad).toInt().coerceAtLeast(1)
        val bytes = width.toLong() * height * 4
        val sprite = if (width <= 8192 && height <= 512 && bytes <= maxBytes) {
            try {
                val image = Surface.makeRasterN32Premul(width, height).use { surface ->
                    surface.canvas.clear(0)
                    surface.canvas.translate(-left, -top)
                    paint.color = 0xff000000.toInt(); paint.mode = PaintMode.STROKE; paint.strokeWidth = 3 * scale
                    surface.canvas.drawTextBlob(blob, 0f, 0f, paint)
                    paint.color = (0xff000000L or color.toLong()).toInt(); paint.mode = PaintMode.FILL
                    surface.canvas.drawTextBlob(blob, 0f, 0f, paint)
                    surface.makeImageSnapshot()
                }
                blob.close()
                DanmakuSprite(image, null, left, top, bytes.toInt())
            } catch (error: Throwable) { blob.close(); throw error }
        } else DanmakuSprite(null, blob, 0f, 0f, 0)
        while (entries.isNotEmpty() && (usedBytes + sprite.bytes > maxBytes || entries.size >= 512)) {
            val iterator = entries.entries.iterator()
            val removed = iterator.next().value
            usedBytes -= removed.bytes; removed.close(); iterator.remove()
        }
        entries[key] = sprite; usedBytes += sprite.bytes
        return sprite
    }

    override fun close() { entries.values.forEach { it.close() }; entries.clear(); usedBytes = 0; paint.close() }
}
