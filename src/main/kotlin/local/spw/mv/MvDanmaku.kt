package local.spw.mv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.*
import kotlin.math.abs
import kotlin.math.roundToInt

data class DanmakuOptions(val enabled: Boolean = true, val opacity: Float = .8f, val scale: Float = 1f,
    val area: Float = .5f, val modes: Set<Int> = (1..5).toSet(), val words: List<String> = emptyList(), val weight: Int = 0, val duration: Double = 20.0)
fun danmakuOptions(store: MvStore) = DanmakuOptions(
    enabled = store.danmakuSetting("enabled", "true").toBoolean(),
    opacity = store.danmakuSetting("opacity", ".8").toFloatOrNull()?.coerceIn(.2f,1f) ?: .8f,
    scale = store.danmakuSetting("scale", "1").toFloatOrNull()?.coerceIn(.5f,2f) ?: 1f,
    area = store.danmakuSetting("area", ".5").toFloatOrNull()?.coerceIn(.25f,1f) ?: .5f,
    modes = (if (store.danmakuSetting("mode1", "true").toBoolean()) setOf(1,2,3) else emptySet()) +
        (4..5).filter { store.danmakuSetting("mode$it", "true").toBoolean() },
    words = store.danmakuSetting("words", "").split('|').filter { it.isNotBlank() },
    weight = store.danmakuSetting("weight", "0").toFloatOrNull()?.takeIf { it.isFinite() }?.roundToInt()?.coerceIn(0,10) ?: 0,
    duration = (20.0 / (store.danmakuSetting("speed", "").toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(1.0/6.0,20.0)
        ?: (20.0 / (store.danmakuSetting("duration", "20").toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(1.0,120.0) ?: 20.0)))).coerceIn(1.0,120.0))

data class DanmakuPlacement(val comment: BiliDanmaku, val lane: Int, val width: Float, val duration: Double)
private const val BILI_FIXED_DURATION = 4.5

class DanmakuClock {
    private var initialized = false
    private var position = 0.0
    private var anchor = 0L
    private var rate = 0.0
    private var playing = false
    private var lastFrame = 0L
    private var displayed = 0.0
    private var estimatedPeriod = 0.0

    fun at(nanos: Long): Double = position + (nanos - anchor).coerceAtLeast(0L) / 1e9 * rate

    fun frameAt(nanos: Long, refreshRate: Int): Double {
        val target = at(nanos)
        val elapsed = nanos - lastFrame
        if (lastFrame == 0L || elapsed <= 0 || elapsed >= 200_000_000L || rate == 0.0) {
            lastFrame = nanos; displayed = target
            return target
        }
        lastFrame = nanos
        if (estimatedPeriod == 0.0) estimatedPeriod = elapsed.toDouble().coerceIn(2e6,50e6)
        estimatedPeriod += (elapsed.toDouble().coerceIn(estimatedPeriod * .7,estimatedPeriod * 1.3)-estimatedPeriod) * .05
        val nominal = if (refreshRate > 0) 1e9 / refreshRate else estimatedPeriod
        val period = if (estimatedPeriod in nominal*.6..nominal*1.6) nominal else estimatedPeriod
        // Skiko callbacks jitter relative to vsync; move one display period and repay phase error gradually.
        val step = period / 1e9 * rate
        val next = displayed + step
        displayed = next + ((target-next) * .15).coerceIn(-step*.12,step*.12)
        return displayed
    }

    fun update(reported: Double, moving: Boolean, speed: Double, nanos: Long) {
        val predicted = if (initialized) at(nanos) else reported
        val error = reported - predicted
        // Runtime publishes position asynchronously; slew small errors without rewinding every poll.
        val reset = !initialized || !moving || !playing || abs(error) > 1.0
        val next = when {
            !initialized || abs(error) > 1.0 -> reported
            !moving && playing -> if (lastFrame != 0L) displayed else reported
            !moving || !playing -> position
            else -> predicted
        }
        if (reset) lastFrame = 0L
        position = next
        anchor = nanos
        rate = if (moving) speed * (1.0 + (if (reset) 0.0 else error * .4).coerceIn(-.15, .15)) else 0.0
        playing = moving
        initialized = true
    }
}

fun layoutDanmaku(comments: List<BiliDanmaku>, width: Float, lanes: Int, options: DanmakuOptions, measure: (BiliDanmaku) -> Float): List<DanmakuPlacement> {
    val groups = mutableMapOf<Int, Array<DanmakuPlacement?>>()
    val placed = mutableListOf<DanmakuPlacement>()
    val recent = mutableMapOf<String, ArrayDeque<Double>>()
    val level = options.weight.coerceIn(0, 10)
    val window = when (level) { in 1..2 -> 8.0; in 3..4 -> 12.0; in 5..6 -> 18.0; in 7..8 -> 25.0; else -> 35.0 }
    val limit = when (level) { in 1..2 -> 4; in 3..4 -> 3; in 5..8 -> 2; else -> 1 }
    for (comment in comments) {
        if (comment.mode !in 1..5 || comment.mode !in options.modes || options.words.any { comment.text.contains(it,true) }) continue
        val history = if (level == 0) null else {
            val normalized = java.text.Normalizer.normalize(comment.text, java.text.Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)
            val key = normalized.filter { it.isLetterOrDigit() }.ifEmpty { normalized.trim() }
            recent.getOrPut(key) { ArrayDeque() }.also { times ->
                while (times.isNotEmpty() && comment.time - times.first() >= window) times.removeFirst()
            }
        }
        if (history != null && history.size >= limit) continue
        val group = if (comment.mode in 1..3) 1 else comment.mode
        val rows = groups.getOrPut(if (group == 4) 4 else 1) { arrayOfNulls(lanes.coerceAtLeast(1)) }
        val textWidth = measure(comment)
        val duration = if (group == 4 || group == 5) BILI_FIXED_DURATION else options.duration
        val row = rows.indexOfFirst { previous ->
            if (previous == null) true
            else if (group == 4 || group == 5 || previous.comment.mode == 5) comment.time >= previous.comment.time + previous.duration
            else {
                val elapsed = comment.time - previous.comment.time
                val oldSpeed = (width + previous.width) / previous.duration
                val newSpeed = (width + textWidth) / duration
                elapsed >= maxOf((previous.width + 24) / oldSpeed, (width + previous.width) / oldSpeed - width / newSpeed)
            }
        }
        if (row >= 0) DanmakuPlacement(comment,row,textWidth,duration).also { rows[row] = it; placed += it; history?.addLast(comment.time) }
    }
    return placed
}

/** Draw from media time, not wall time: seek, pause and speed changes share one clock. */
@Composable fun MvDanmaku(state: MvScreenState, controller: MvController) {
    if (state.online == null || state.danmaku.isEmpty()) return
    val options = danmakuOptions(controller.store)
    if (!options.enabled) return
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val typeface = remember { FontMgr.default.matchFamilyStyle("Microsoft YaHei", FontStyle.BOLD) ?: FontMgr.default.legacyMakeTypeface("Arial", FontStyle.NORMAL) }
    val font = remember(typeface) { Font(typeface, 25f) }
    val paint = remember { Paint().apply { isAntiAlias = true } }
    DisposableEffect(font, paint) { onDispose { font.close(); paint.close(); typeface?.close() } }
    val scale = (viewport.width / 1280f).coerceIn(.6f,1.5f) * options.scale
    val sprites = remember(scale) { DanmakuSprites(font, scale) }
    DisposableEffect(sprites) { onDispose { sprites.close() } }
    val line = 36f * scale
    val placements = remember(state.danmaku, viewport, options.area, options.scale, options.modes, options.words, options.weight, options.duration) {
        layoutDanmaku(state.danmaku,viewport.width.toFloat(), ((viewport.height * options.area - 66f) / line).toInt(),options) { comment ->
            font.size = comment.size.coerceAtMost(30) * scale; font.measureTextWidth(comment.text)
        }
    }
    val clock = remember(state.video, state.online?.cid, state.seekGeneration) { DanmakuClock() }
    var framePosition by remember(clock) { mutableDoubleStateOf(state.playback.positionSeconds) }
    remember(clock, state.playback.positionSeconds, state.playback.paused, state.playback.buffering, state.playback.speed) {
        clock.update(state.playback.positionSeconds, !state.playback.paused && !state.playback.buffering, state.playback.speed, System.nanoTime())
    }
    val refresh = controller.page.displayRefreshRate()
    LaunchedEffect(clock,state.playback.paused,state.playback.buffering,refresh) {
        if (!state.playback.paused && !state.playback.buffering) while (true) withFrameNanos { framePosition = clock.frameAt(it,refresh) }
    }
    PlayerCanvas(Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        val now = framePosition
        val canvas = drawContext.canvas.nativeCanvas
        // Binary-search the visible interval; drawing cost depends on active comments, not the whole video.
        var low = 0; var high = placements.size
        while (low < high) { val mid = (low + high) ushr 1; if (placements[mid].comment.time < now - maxOf(options.duration,16.0)) low = mid + 1 else high = mid }
        var endLow = low; var endHigh = placements.size
        while (endLow < endHigh) { val mid = (endLow + endHigh) ushr 1; if (placements[mid].comment.time <= now) endLow = mid + 1 else endHigh = mid }
        for (i in endLow - 1 downTo low) {
            val item = placements[i]; val c = item.comment; val elapsed = now - c.time
            if (elapsed >= item.duration) continue
            val x = if (c.mode in 4..5) (size.width-item.width)/2 else size.width-(size.width+item.width)*(elapsed/item.duration).toFloat()
            val y = if (c.mode == 4) size.height-90f-(item.lane+1)*line else 66f+(item.lane+1)*line
            val alpha = options.opacity
            val sprite = sprites.get(c.text, c.size, c.color)
            canvas.save(); canvas.translate(x,y)
            if (sprite.image != null) {
                paint.color = 0xffffffff.toInt(); paint.alpha = (alpha * 255).toInt(); paint.mode = PaintMode.FILL
                canvas.drawImageRect(sprite.image, Rect.makeWH(sprite.image.width.toFloat(), sprite.image.height.toFloat()),
                    Rect.makeXYWH(sprite.left, sprite.top, sprite.image.width.toFloat(), sprite.image.height.toFloat()), SamplingMode.LINEAR, paint, true)
            } else {
                paint.color = 0xff000000.toInt(); paint.alpha = (alpha * 255).toInt(); paint.mode = PaintMode.STROKE; paint.strokeWidth = 3f * scale
                canvas.drawTextBlob(requireNotNull(sprite.blob),0f,0f,paint)
                paint.color = (0xff000000L or c.color.toLong()).toInt(); paint.alpha = (alpha * 255).toInt(); paint.mode = PaintMode.FILL
                canvas.drawTextBlob(sprite.blob,0f,0f,paint)
            }
            canvas.restore()
        }
    }
}
