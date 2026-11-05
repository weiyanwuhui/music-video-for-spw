package local.spw.mv

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Compare the same native renderer with and without the warp, without decoder or screenshot overhead. */
fun main() {
    val ready = CompletableFuture<androidx.compose.ui.awt.ComposeWindow>()
    val progress = Animatable(0f)
    val phase = mutableIntStateOf(0)
    val finished = CompletableFuture<Unit>()
    val intervals = Array(2) { mutableListOf<Double>() }
    val samples = java.util.concurrent.ConcurrentHashMap<String, Int>()
    val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
    val sampler = Thread {
        while (sampling.get()) {
            Thread.getAllStackTraces().forEach { (thread, stack) ->
                if (thread.name.contains("AWT-EventQueue") || stack.any { it.className.startsWith("org.jetbrains.skiko") }) {
                    stack.firstOrNull()?.let { samples.merge("${thread.name}: $it", 1, Int::plus) }
                }
            }
            Thread.sleep(10)
        }
    }.apply { isDaemon = true; start() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val app = scope.launchApplication {
        Window(onCloseRequest = { exitApplication() }, state = rememberWindowState(width = 1120.dp, height = 760.dp), undecorated = true) {
            SideEffect { window.isAlwaysOnTop = true; ready.complete(window) }
            val genie = remember { MvGenie() }
            DisposableEffect(genie) { onDispose { genie.close() } }
            LaunchedEffect(Unit) {
                delay(500)
                repeat(2) { mode ->
                    phase.intValue = mode
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(1800, easing = LinearEasing))
                    delay(300)
                }
                finished.complete(Unit)
            }
            Box(Modifier.fillMaxSize().then(if (phase.intValue == 1)
                genie.modifier({ Rect(1500f, 1000f, 1560f, 1060f) }, { progress.value }) else Modifier)
                .drawBehind {
                    drawRect(Color(0xFF47657B))
                    drawRect(Color.White, androidx.compose.ui.geometry.Offset(size.width * progress.value, 0f), androidx.compose.ui.geometry.Size(8f, size.height))
                })
        }
    }
    val window = ready.get(15, TimeUnit.SECONDS)
    edt {
        fun layer(component: java.awt.Component): org.jetbrains.skiko.SkiaLayer? = if (component is org.jetbrains.skiko.SkiaLayer) component
            else (component as? java.awt.Container)?.components?.firstNotNullOfOrNull(::layer)
        val native = layer(window)!!
        println("MOTION_ENV: api=${window.renderApi}; scale=${native.contentScale}; vsync=${System.getProperty("skiko.vsync.enabled", "true")}")
        val delegate = native.renderDelegate!!
        var last = 0L; var lastMode = 0
        native.renderDelegate = object : org.jetbrains.skiko.SkikoRenderDelegate {
            override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) {
                val now = System.nanoTime(); val mode = phase.intValue
                if (last != 0L && mode == lastMode && progress.value in .02f.. .98f) intervals[mode] += (now - last) / 1e6
                last = now; lastMode = mode
                delegate.onRender(canvas, width, height, nanoTime)
            }
        }
        window.toFront()
    }
    try {
        finished.get(20, TimeUnit.SECONDS)
        intervals.forEachIndexed { index, values ->
            val sorted = values.sorted()
            println("MOTION_COMPARE: mode=${if (index == 0) "baseline" else "genie"}; samples=${values.size}; p95=${sorted.getOrElse(((sorted.size - 1) * .95).toInt().coerceAtLeast(0)) { 0.0 }}; max=${values.maxOrNull()}")
        }
        samples.entries.sortedByDescending { it.value }.take(10).forEach { println("MOTION_STACK: ${it.value} ${it.key}") }
    } finally { sampling.set(false); sampler.join(); app.cancel(); scope.cancel(); edt { window.dispose() } }
    System.exit(0)
}
