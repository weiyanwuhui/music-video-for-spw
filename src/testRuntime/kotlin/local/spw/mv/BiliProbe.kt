package local.spw.mv

import dev.gaboron.spwmedia.core.*
import dev.gaboron.spwmedia.playback.PlaybackProvider
import dev.gaboron.spwmedia.protocol.PlayerOptions
import dev.gaboron.spwmedia.sdk.*
import java.nio.file.*
import java.util.concurrent.TimeUnit

/** Explicit network smoke: anonymous public video only; never logs in or posts a comment. */
fun main() {
    val api = BiliApi()
    val video = api.resolve(biliLink("BV1GJ411x7h7"))
    check(video.qualities.isNotEmpty())
    println("PASS: live Bilibili metadata and authorized DASH qualities (${video.qualities.map { it.label }})")
    val comments = api.danmaku(video.cid,1)
    check(comments.isNotEmpty())
    println("PASS: live protobuf danmaku (${comments.size} comments)")
    val runtimeRoot = Path.of("build/runtime").toAbsolutePath()
    val root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "bili-network-")
    val loginState = java.util.concurrent.atomic.AtomicReference<BiliLoginState?>()
    BiliLogin(root.resolve("test-account.dat")) { loginState.set(it) }.use { auth ->
        auth.start()
        val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
        while (loginState.get()?.png == null) {
            check(loginState.get()?.finished != true) { "QR generation failed" }
            check(System.nanoTime()<deadline) { "QR generation timed out" }; Thread.sleep(50)
        }
        Thread.sleep(2500)
        check(loginState.get()?.finished != true)
        auth.cancel(); check(loginState.get()==null && !Files.exists(root.resolve("test-account.dat")))
        println("PASS: live native QR generation, pending scan and cancellation; no login or credential written")
    }
    val assets = NativeAssets(runtimeRoot.resolve("native"),root.resolve("native"))
    val workerClasspath = Files.list(runtimeRoot.resolve("lib")).use { files -> files.filter { it.toString().endsWith(".jar") }.map { it.toString() }.toList() }
        .plus(Path.of(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI()).toString()).joinToString(java.io.File.pathSeparator)
    val engine = RuntimeEngine(listOf(PlaybackProvider()),assets,root.resolve("runtime-data"),emptyMap(),workerCommand = { mode ->
        if (mode == "playback") WorkerCommands.playback(assets,workerClasspath,Path.of(System.getProperty("spw.test.javaHome"))) else null
    })
    RuntimeEndpoint(engine) { "local.spw.mv.bilitest" }.use {
        MediaRuntime.connect("local.spw.mv.bilitest").use { runtime ->
            val q = video.qualities.last()
            val options = mutableMapOf("pause" to "yes","volume" to "0","referrer" to "https://www.bilibili.com/","user-agent" to BiliApi.USER_AGENT)
            q.audio?.let { options["audio-files"] = it }
            val begin = System.nanoTime()
            options["cache"] = "yes"; options["demuxer-readahead-secs"] = "8"
            val player = runtime.playback.open(PlayerOptions(frameWidth=640,frameHeight=360,options=options)).result.toCompletableFuture().get(25,TimeUnit.SECONDS)
            try {
                player.load(q.video).toCompletableFuture().get(20,TimeUnit.SECONDS)
                val buffer = player.frames()
                try {
                    val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(25)
                    while (buffer.latest()==null || player.state().durationSeconds<=0) { check(System.nanoTime()<deadline) { "No online video frame" }; player.snapshot().error?.let { error(it.code) }; Thread.sleep(50) }
                    println("PASS: live Bilibili DASH decoded first frame in ${(System.nanoTime()-begin)/1_000_000} ms")
                    println("STATE: "+player.state().copy(media="[redacted]"))
                    player.play().toCompletableFuture().get(5,TimeUnit.SECONDS)
                    Thread.sleep(800)
                    try { player.seek(20.0).toCompletableFuture().get(12,TimeUnit.SECONDS) }
                    catch (error: Exception) {
                        val detail = MediaRuntimeException.unwrap(error)?.detail
                        if (Files.exists(root.resolve("mpv.log"))) println(Files.readString(root.resolve("mpv.log")).takeLast(6000).replace(Regex("https?://[^\\s]+"), "[URL]"))
                        println("BACKEND: " + detail?.backend?.stderrTail?.replace(Regex("https?://[^\\s]+"), "[URL]"))
                        throw error
                    }
                    Thread.sleep(1500)
                    check(player.state().positionSeconds>=19)
                    println("PASS: live Bilibili seek keeps the external audio stream attached (muted)")
                } finally { buffer.close() }
            } finally { player.close() }
        }
    }
    assets.close()
}


