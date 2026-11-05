@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.runtime.tooling.ComposeToolingApi::class)
package local.spw.mv

import com.xuncorp.spw.workshop.api.*
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import dev.gaboron.spwmedia.core.*
import dev.gaboron.spwmedia.playback.PlaybackProvider
import dev.gaboron.spwmedia.processing.ProcessingProvider
import dev.gaboron.spwmedia.sdk.*
import java.lang.reflect.Proxy
import java.nio.file.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import kotlin.math.abs

private fun await(label: String, timeout: Long = 20000, test: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
    while (!test()) { check(System.nanoTime() < deadline) { "Timed out: $label" }; Thread.sleep(50) }
    println("PASS: $label")
}

fun main(args: Array<String>) {
    println("Runtime scenario: ${args.joinToString()}; vsync=${System.getProperty("skiko.vsync.enabled", "true")}")
    val runtimeRoot = Path.of("build/runtime").toAbsolutePath()
    val root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "mv-runtime-")
    val assets = NativeAssets(runtimeRoot.resolve("native"), root.resolve("native"))
    val workerClasspath = Files.list(runtimeRoot.resolve("lib")).use { files -> files.filter { it.toString().endsWith(".jar") }.map { it.toString() }.toList() }
        .plus(Path.of(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI()).toString()).joinToString(java.io.File.pathSeparator)
    val engine = RuntimeEngine(listOf(ProcessingProvider(), PlaybackProvider()), assets, root.resolve("runtime-data"), emptyMap(), workerCommand = { mode ->
        if (mode == "playback") WorkerCommands.playback(assets, workerClasspath, Path.of(System.getProperty("spw.test.javaHome"))) else null
    })
    var endpoint: RuntimeEndpoint? = null
    val videoFolder = Files.createDirectory(root.resolve("MV"))
    val videoA = Files.copy(Path.of("build/synthetic-mv.mp4"), videoFolder.resolve("歌手 - 测试歌曲.mp4"))
    val videoB = Files.copy(videoA, videoFolder.resolve("歌手 - 下一首.mp4"))
    val songA = MediaItem("测试歌曲", "歌手", "", "", root.resolve("歌手 - 测试歌曲.flac").toString(), id = "a")
    val songB = MediaItem("下一首", "歌手", "", "", root.resolve("歌手 - 下一首.flac").toString(), id = "b")
    val current = AtomicReference<MediaItem?>(songA)
    val playing = AtomicBoolean(true); val position = AtomicLong(3456); val resumes = AtomicInteger()
    val playback = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader, arrayOf(WorkshopApi.Playback::class.java)) { _, called, args -> when (called.name) {
        "getCurrentMediaItem" -> current.get()
        "pause" -> { playing.set(false); null }
        "play" -> { playing.set(true); resumes.incrementAndGet(); null }
        "seekTo" -> { position.set(args[0] as Long); null }
        "next" -> { current.set(if (current.get() == songA) songB else songA); playing.set(true); null }
        "previous" -> { current.set(if (current.get() == songB) songA else songB); playing.set(true); null }
        else -> error("Unexpected playback method: $called")
    } } as WorkshopApi.Playback
    val library = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader, arrayOf(WorkshopApi.Library::class.java)) { _, called, _ -> when(called.name) {
        "getAllTracks" -> CompletableFuture.completedFuture(listOf(songA, songB))
        else -> error("Unexpected library method: $called")
    } } as WorkshopApi.Library
    val api = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader, arrayOf(WorkshopApi::class.java)) { _, called, _ -> when(called.name) {
        "getPlayback" -> playback; "getLibrary" -> library; else -> error("Unexpected API method: $called")
    } } as WorkshopApi
    WorkshopApi.instance = api
    val store = MvStore(root.resolve("config/mv.properties"))
    store.saveFolder(videoFolder); store.bind(songA, videoA); store.bind(songB, videoB); store.setAutoOpen(true)
    var controller: MvController? = null
    val hostState = WindowState(position = WindowPosition(120.dp, 80.dp), size = androidx.compose.ui.unit.DpSize(1120.dp, 760.dp))
    val hostContent = mutableStateOf<(@Composable FrameWindowScope.() -> Unit)?>(null)
    val hooksEnabled = mutableStateOf(true)
    val hostReady = CompletableFuture<ComposeWindow>()
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val application = applicationScope.launchApplication {
        Window(onCloseRequest = { exitApplication() }, state = hostState, undecorated = true, title = "SPW MV integration") {
            SideEffect { window.name = "spw-test-host"; hostReady.complete(window) }
            hostContent.value?.invoke(this)
        }
    }
    val host = hostReady.get(15, TimeUnit.SECONDS)
    await("real Compose WindowState binds the native host") { edt { host.width == 1120 && host.height == 760 } }
    val hostBounds = edt { host.bounds }
    var entryBounds = androidx.compose.ui.geometry.Rect.Zero
    val picked = AtomicReference<Path?>(null)
    val pickerRequests = AtomicInteger()
    try {
        val control = MvController("local.spw.mv.test", store, pickVideo = { pickerRequests.incrementAndGet(); picked.get() }, hostWindow = { host }) { position.get() to playing.get() }; controller = control
        MvPlugin.activeController = control
        checkClosedPageInput(control.page)
        checkGeniePixels()
        edt {
            hostContent.value = {
                val frameScope = this
                NativeUi.AppTheme { CompositionLocalProvider(MvHostWindow.windowLocal provides hostState) {
                val body: @Composable FrameWindowScope.() -> Unit = {
                    val originalPage: @Composable () -> Unit = {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF665750))) {
                        PlayerText("SPW 原播放页", androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.Center), size = 26)
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(28.dp)
                            .onGloballyPositioned { entryBounds = it.boundsInRoot(); HostBridge.layoutEntry(entryBounds) }) {
                            NativeUi.MvButton { HostBridge.captureEntry(entryBounds); control.open() }
                        }
                    }
                    }
                    if (hooksEnabled.value) MvPageHook(control.page) { true }.Layer(originalPage) else originalPage()
                }
                NativeFrame(frameScope, control.page.isVisible, body)
            } } }
        }
        await("host root layout is ready") { control.page.rootBounds.width > 0 && entryBounds.width > 0 }
        checkNativeFrameLifecycle(host, hooksEnabled)
        val opening = observeOpening(host, control.page)
        edt { HostBridge.captureHost(host); HostBridge.captureEntry(entryBounds) }

        await("initial scan and current-song lookup") { control.screen.value.track?.id == "a" && control.screen.value.match.file == videoA }
        check(playing.get() && control.screen.value.frames == null)
        endpoint = RuntimeEndpoint(engine) { "local.spw.mv.test" }
        await("real libmpv video decodes, frames reach Compose, and SPW audio pauses") {
            control.screen.value.frames != null && control.screen.value.playback.positionSeconds > 0.2 && !playing.get()
        }
        println("PASS: Runtime may start after MV plugin; waiting does not interrupt song audio")
        check(!control.page.full.value) { "Floating SPW must open a floating MV" }
        opening.get(8, TimeUnit.SECONDS)
        if ("--window-only" !in args) {
        checkActivePageInput(control)
        await("header back returns to the original song") { !control.page.isVisible && playing.get() }
        check(resumes.get() == 1); resumes.set(0)
        control.open()
        await("reopen after full-page pointer regression") { control.screen.value.frames != null && control.page.motionReady }
        captureOpening(host, control.page, entryBounds)
        control.autoOpen(false)
        render(control)
        await("vertical volume drag reaches native backend") { control.screen.value.playback.volume == 75.0 }
        await("pause") { control.screen.value.playback.paused }
        val pausedAt = control.screen.value.playback.positionSeconds
        Thread.sleep(500)
        check(kotlin.math.abs(control.screen.value.playback.positionSeconds - pausedAt) < 0.12)
        control.speed(1.5); await("1.5x speed acknowledged by native backend") { control.screen.value.playback.speed == 1.5 }
        control.boost(2.0); await("hold boost reaches native 2x") { control.screen.value.playback.speed == 2.0 }
        control.boost(null); await("release restores selected 1.5x") { control.screen.value.playback.speed == 1.5 }
        control.boost(3.0); await("left hold reaches native 3x") { control.screen.value.playback.speed == 3.0 }
        control.speed(1.25); control.boost(null); await("changing selected speed during boost restores new selection") { control.screen.value.playback.speed == 1.25 }
        edt {
            check(java.awt.Window.getWindows().none { it.name == "spw-mv-player" && it.isShowing }) { "MV created a separate native window" }
            check(host.width == 1120 && host.height == 760)
            check(control.page.rootBounds.width == (host.contentPane.width * host.graphicsConfiguration.defaultTransform.scaleX).toFloat())
            saveNative(host, "mv-native-page")
        }
        println("PASS: MV is a host-root page with no independent native window")
        edt { control.page.fullscreen() }
        await("real host fullscreen covers monitor and removes every external gap") { edt {
            host.placement == androidx.compose.ui.window.WindowPlacement.Fullscreen && host.bounds == host.graphicsConfiguration.bounds &&
                kotlin.math.abs(control.page.rootBounds.width - host.width * host.graphicsConfiguration.defaultTransform.scaleX) <= 1 &&
                kotlin.math.abs(control.page.rootBounds.height - host.height * host.graphicsConfiguration.defaultTransform.scaleY) <= 1
        } }
        edt { saveNative(host, "mv-fullscreen"); control.page.fullscreen() }
        await("fullscreen restores the exact original host bounds") { edt { host.bounds == hostBounds && host.placement == androidx.compose.ui.window.WindowPlacement.Floating } }
        control.seek(0.0); control.toggle(); await("resume before hidden-toolbar seek check") { !control.screen.value.playback.paused }
        await("transport automatically hides during playback", 6000) { edt { !hasControl(host, "播放进度") } }
        edt {
            control.page.action("FORWARD_5S"); control.page.action("FORWARD_5S")
            check(!hasControl(host, "播放进度")) { "Arrow seeking flashed the timeline" }
            check(control.screen.value.playback.positionSeconds >= 10.0) { "Rapid arrows did not accumulate immediately" }
        }
        await("rapid seek reaches accumulated backend position") { control.screen.value.playback.positionSeconds in 10.0..11.5 }
        Thread.sleep(150)
        check(edt { !hasControl(host, "播放进度") }) { "Backend seek events woke the timeline" }
        control.toggle(); await("pause after arrow seek") { control.screen.value.playback.paused }
        println("PASS: rapid arrows accumulate without revealing controls or progress text")
        control.seek(4.0); await("seek to 4 seconds") { control.screen.value.playback.positionSeconds in 3.9..4.2 }
        control.volume(0.0); await("mute") { control.screen.value.playback.volume == 0.0 }
        control.volume(30.0); await("30 percent volume") { control.screen.value.playback.volume == 30.0 }
        edt { control.page.fullscreen(); check(control.page.full.value) }
        edt { control.page.fullscreen(); check(!control.page.full.value) }
        println("PASS: real ComposeWindow enters and exits fullscreen")
        val closingFrame = CompletableFuture<Unit>()
        edt {
            visibleNodes(host).first { described(it, "返回歌曲") }.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!.invoke()
            javax.swing.Timer(310) { try {
                check(control.screen.value.frames != null); saveNative(host, "mv-closing-middle"); closingFrame.complete(Unit)
            } catch (error: Throwable) { closingFrame.completeExceptionally(error) } }.apply { isRepeats = false; start() }
        }
        await("close restores original song position and playing state") { !control.page.isOpen && playing.get() && position.get() == 3456L && resumes.get() == 1 }
        await("back animation returns to the retained original page") { !control.page.isVisible }
        closingFrame.get(5, TimeUnit.SECONDS)
        println("PASS: reverse genie motion retains the real video frame until the page is hidden")
        edt { saveNative(host, "mv-return"); check(host.bounds == hostBounds) }
        edt { hostState.placement = WindowPlacement.Fullscreen }; control.open()
        await("reopen creates a fresh playable session") { control.screen.value.frames != null }
        await("fullscreen SPW opens MV in fullscreen") { control.page.full.value }
        control.autoOpen(true)
        current.set(songB); playing.set(true); MvPlayback().onStateChanged(PlaybackExtensionPoint.State.Buffering)
        await("switch song replaces old video with corresponding MV") { control.screen.value.video == videoB && control.screen.value.frames != null && !playing.get() }
        check(resumes.get() == 1) { "Old song was resumed over a new selection" }
        control.autoOpen(false)
        current.set(songA); playing.set(true); MvPlayback().onStateChanged(PlaybackExtensionPoint.State.Buffering)
        await("automatic opening can be disabled; switch releases old player") { control.screen.value.track?.id == "a" && control.screen.value.frames == null }
        check(playing.get() && resumes.get() == 1) { "New song paused or old song resumed: playing=${playing.get()}, resumes=${resumes.get()}" }
        control.bind(videoB, songA)
        await("manual binding opens chosen MV even with automatic opening disabled") { control.screen.value.video == videoB && !playing.get() }
        check(MvStore(root.resolve("config/mv.properties")).bound(songA) == videoB)
        control.closeVideo()
        await("manual playback closes and restores song") { playing.get() && !control.page.isOpen }
        control.bind(null, songA, false)
        await("settings unbinds without opening playback") { store.bound(songA) == null && !control.page.isOpen }
        val unmatched = songA.copy(title = "无匹配", path = root.resolve("无匹配.flac").toString(), id = "c")
        current.set(unmatched); playing.set(true)
        val barrier = CompletableFuture<Unit>()
        control.open(); control.dispatch { barrier.complete(Unit) }; barrier.get(5, TimeUnit.SECONDS)
        check(store.bound(unmatched) == null && playing.get() && !control.page.isOpen) { "Cancelling file selection mutated binding or playback" }
        println("PASS: unmatched MV prompts file selection; cancel preserves playback and binding")
        picked.set(videoA); edt { host.placement = androidx.compose.ui.window.WindowPlacement.Floating; host.bounds = hostBounds }; control.open()
        await("selecting a missing MV binds it and immediately plays") { store.bound(unmatched) == videoA && control.screen.value.frames != null }
        await("ordinary SPW opens chosen MV without fullscreen") { control.page.isOpen && !control.page.full.value }
        control.closeVideo(); await("chosen MV closes normally") { !control.page.isOpen && playing.get() }
        current.set(songA)
        control.selectVideo(false)
        await("configuration binding saves without opening playback") { store.bound(songA) == videoA && !control.page.isOpen }
        control.refreshPlaylist()
        await("playlist lists the configured MV folder") { control.screen.value.queue.size == 2 }
        control.navigate(false)
        await("Next follows MV files without switching the SPW song") { current.get() == songA && control.screen.value.video == videoB && control.screen.value.frames != null }
        control.navigate(true)
        await("Previous follows MV files without switching the SPW song") { current.get() == songA && control.screen.value.video == videoA && control.screen.value.frames != null }
        control.select(control.screen.value.queue.first { it.file == videoB })
        await("playlist selection changes only the MV") { current.get() == songA && control.screen.value.video == videoB && control.screen.value.frames != null }
        control.closeVideo(); await("playlist playback closes and restores selected song") { !control.page.isOpen && playing.get() }
        control.open(); await("reopen before playback-order checks") { control.screen.value.frames != null }
        control.order("RepeatOne"); control.seek(11.8)
        await("repeat-one restarts the MV without changing the song") {
            current.get() == songA && control.screen.value.playback.positionSeconds < 2 && !control.screen.value.playback.paused
        }
        control.toggle(); await("manual pause before end") { control.screen.value.playback.paused }
        control.order("Circle"); control.seek(11.95); Thread.sleep(500)
        check(current.get() == songA && control.screen.value.playback.paused) { "A manual near-end pause advanced the song" }
        control.order("CircleEnd"); control.toggle(); control.seek(11.8)
        await("sequential mode stops at the last song") { control.screen.value.playback.paused && control.screen.value.playback.positionSeconds > 11.8 }
        Thread.sleep(400); check(current.get() == songA && control.page.isOpen)
        control.order("Circle"); control.toggle(); control.seek(11.8)
        await("list loop advances at MV end without touching the SPW queue") { current.get() == songA && control.screen.value.frames != null && control.screen.value.video == videoB }
        control.navigate(false); await("order test returns to second MV") { current.get() == songA && control.screen.value.video == videoA && control.screen.value.frames != null }
        control.closeVideo(); await("order playback closes cleanly") { !control.page.isOpen && playing.get() }
        println("PASS: native playback order controls EOF behavior")
        val stale = control.screen.value.queue.first { it.file == videoB }
        val movedVideo = Files.move(videoB, videoFolder.resolve("moved.mp4"))
        control.select(stale)
        await("stale playlist cannot open a moved video") { control.screen.value.message.contains("MV 已移走") }
        check(current.get() == songA && !control.page.isOpen && playing.get())
        Files.move(movedVideo, videoB)
        val addedSong = songA.copy(title = "One Last Kiss", artist = "宇多田ヒカル", id = "added", path = root.resolve("One Last Kiss.flac").toString())
        current.set(addedSong); picked.set(null); control.playbackChanged()
        await("song without a video remains ordinary audio") { control.screen.value.track == addedSong && control.screen.value.match.file == null }
        val pickerBefore = pickerRequests.get()
        val addedVideo = Files.copy(videoA, videoFolder.resolve("One Last Kiss.mp4"))
        control.open()
        await("new same-name One Last Kiss is discovered without rescanning or choosing a file") { control.screen.value.frames != null && store.bound(addedSong) == addedVideo }
        check(pickerRequests.get() == pickerBefore && control.screen.value.linkRequest == null)
        control.closeVideo(); await("newly discovered MV closes normally") { !control.page.isVisible && playing.get() }
        val renamedVideo = Files.move(addedVideo, videoFolder.resolve("【官方MV】宇多田光 - 《One Last Kiss》（电影主题曲）-1080P-AVC.mp4"))
        control.open()
        await("renamed One Last Kiss repairs its stored link automatically") { control.screen.value.frames != null && store.bound(addedSong) == renamedVideo }
        check(pickerRequests.get() == pickerBefore)
        control.closeVideo(); await("renamed MV closes normally") { !control.page.isVisible && playing.get() }
        println("PASS: new and renamed One Last Kiss auto-matches without a file picker")
        val freshSong = songA.copy(title = "待确认歌曲", id = "fresh", path = root.resolve("unlinked.flac").toString())
        val versionA = Files.copy(videoA, videoFolder.resolve("歌手 - 待确认歌曲【版本甲】.mp4"))
        Files.copy(videoA, videoFolder.resolve("歌手 - 待确认歌曲【版本乙】.mp4"))
        current.set(freshSong); picked.set(null); control.playbackChanged()
        await("ambiguous versions are ranked without starting playback") { control.screen.value.track == freshSong && control.screen.value.match.file == null && control.screen.value.match.candidates.contains(versionA) }
        control.open(); await("first MV click asks for a persistent link") { control.screen.value.linkRequest?.track == freshSong }
        check(!control.page.isOpen && playing.get() && store.bound(freshSong) == null)
        checkLinkPromptThemes(host)
        check(store.bound(freshSong) == null && playing.get())
        control.open(); await("link prompt can be opened after cancelling") { control.screen.value.linkRequest != null }
        Thread.sleep(250)
        edt {
            fun action(label: String) = visibleNodes(host).first { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
                && "选择 MV $label" in it.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
                && it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) }
                .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
            check(action(versionA.fileName.toString()).invoke())
        }
        Thread.sleep(100)
        edt {
            val confirm = visibleNodes(host).first { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)
                && it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { text -> text.text == "链接并播放" }
                && it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) }
            check(confirm.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!.invoke())
        }
        await("confirmed link is persisted before playing") { store.bound(freshSong) == versionA && control.screen.value.frames != null }
        println("PASS: native SPW candidate selection and confirmation bind the selected MV")
        check(MvStore(root.resolve("config/mv.properties")).bound(freshSong) == versionA)
        control.closeVideo(); await("confirmed playback closes normally") { !control.page.isVisible && playing.get() }
        control.unbindCurrent(); await("unlink excludes the song from automatic matching") { store.bound(freshSong) == null && store.blocked(freshSong) && control.screen.value.match.candidates.isEmpty() }
        control.open(); await("explicit MV click offers recovery instead of a file picker") { control.screen.value.linkRequest?.track == freshSong }
        control.cancelLink(); control.resumeMatching()
        await("excluded song can restore automatic matching") { !store.blocked(freshSong) && control.screen.value.match.candidates.isNotEmpty() }
        println("PASS: first-use consent, persistent link, cancel and unlink exclusion")
        } else {
            control.autoOpen(false); control.closeVideo()
            await("initial playback closes before native window regression") { !control.page.isVisible && playing.get() }
        }
        current.set(songB); playing.set(true); control.playbackChanged()
        await("return to linked song after link checks") { control.screen.value.track == songB }
        edt { hostState.placement = WindowPlacement.Fullscreen }
        await("host enters fullscreen before regression sequence") { edt { host.placement == WindowPlacement.Fullscreen } }
        control.open(); await("MV inherits fullscreen with native floating history") { control.screen.value.frames != null && control.page.full.value }
        edt { control.page.fullscreen() }
        await("MV exits fullscreen using host WindowState") { edt { host.placement == WindowPlacement.Floating && host.bounds == hostBounds } }
        edt { control.page.fullscreen() }
        await("MV returns to actual fullscreen through host WindowState") { edt { host.placement == WindowPlacement.Fullscreen } }
        control.closeVideo(); await("return retains original native fullscreen") { edt {
            !control.page.isVisible && hostState.placement == WindowPlacement.Fullscreen && host.placement == WindowPlacement.Fullscreen
        } }
        edt { hostState.placement = WindowPlacement.Floating }
        await("caption restore after MV returns the exact original size and position") { edt { host.bounds == hostBounds } }
        edt { hostState.placement = WindowPlacement.Maximized }
        await("host maximizes with floating restore history intact") { edt { host.placement == WindowPlacement.Maximized } }
        control.open(); await("MV opens from a maximized lyrics host") { control.screen.value.frames != null && control.page.isOpen }
        edt { control.page.fullscreen() }
        await("maximized host enters native MV fullscreen") { edt { host.placement == WindowPlacement.Fullscreen } }
        control.closeVideo()
        await("return restores native maximized state without a stuck fullscreen flag") { edt { !control.page.isVisible && hostState.placement == WindowPlacement.Maximized && host.placement == WindowPlacement.Maximized } }
        edt { hostState.placement = WindowPlacement.Floating }
        await("caption restore after maximized/fullscreen MV returns original bounds") { edt { host.bounds == hostBounds } }
        control.open(); await("reopen before stop action") { control.screen.value.frames != null && !playing.get() }
        control.stopVideo(); await("SPW stop action closes MV without resuming song audio") { !control.page.isOpen && control.screen.value.frames == null }
        check(!playing.get())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val windowsOpened = AtomicInteger()
        val observer = java.awt.event.AWTEventListener { event ->
            if (event is java.awt.event.WindowEvent && event.id == java.awt.event.WindowEvent.WINDOW_OPENED && event.window.name == "spw-mv-player") windowsOpened.incrementAndGet()
        }
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(observer, java.awt.AWTEvent.WINDOW_EVENT_MASK)
        try {
            control.dispatch { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            check(entered.await(5, TimeUnit.SECONDS))
            control.open(); control.closeVideo()
            val drained = CompletableFuture<Unit>(); control.dispatch { drained.complete(Unit) }
            release.countDown(); drained.get(20, TimeUnit.SECONDS)
            check(windowsOpened.get() == 0 && !control.page.isOpen) { "A queued open resurrected a closed MV window" }
            println("PASS: close cancels queued opening without creating or reopening a window")
        } finally { release.countDown(); java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(observer) }
        println("PASS: isolated real Runtime + plugin controller integration; no live SPW installation or user media touched")
    } finally {
        MvPlugin.activeController = null
        controller?.close()
        Thread.sleep(1500)
        application.cancel(); applicationScope.cancel()
        edt { host.dispose() }; endpoint?.close() ?: engine.close(); assets.close()
    }
    System.exit(0)
}

@Composable private fun NativeFrame(scope: FrameWindowScope, hidden: Boolean, body: @Composable FrameWindowScope.() -> Unit) {
    val original = remember { HostBridge.resolver.composable("WindowsSaltWindowFrame", 6, null) }
    val properties = remember(hidden) { nativeCaptionProperties(hidden) }
    @Suppress("UNCHECKED_CAST")
    val local = remember { HostBridge.resolver.facade("LocalSaltWindowProperties").declaredMethods.single {
        it.parameterCount == 0 && it.returnType == ProvidableCompositionLocal::class.java
    }.let { HostBridge.invoke(it, null) as ProvidableCompositionLocal<Any> } }
    CompositionLocalProvider(local provides properties) {
        HostBridge.invoke(original, null, scope, true, properties, body, currentComposer, 0)
    }
}
private fun checkNativeFrameLifecycle(window: ComposeWindow, enabled: MutableState<Boolean>) {
    val user = com.sun.jna.platform.win32.User32.INSTANCE
    val hwnd = com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Native.getWindowPointer(window))
    val before = user.GetWindowLongPtr(hwnd, -4).toLong()
    repeat(3) {
        edt { enabled.value = false }; Thread.sleep(250)
        edt { enabled.value = true }; Thread.sleep(250)
        System.gc(); Thread.sleep(100)
        check(user.GetWindowLongPtr(hwnd, -4).toLong() == before) { "MV remounted the native window frame" }
        com.sun.jna.Memory(256).use { text ->
            val result = user.SendMessage(hwnd, 13, com.sun.jna.platform.win32.WinDef.WPARAM(128),
                com.sun.jna.platform.win32.WinDef.LPARAM(com.sun.jna.Pointer.nativeValue(text)))
            check(result.toLong() > 0 && text.getWideString(0) == "SPW MV integration") { "Native window messages stopped after MV hook replacement" }
        }
    }
    println("PASS: hot MV root enable/disable preserves native message callbacks after garbage collection")
}
private fun nativeCaptionProperties(hidden: Boolean = false): Any {
    val type = HostBridge.resolver.composable("WindowsSaltWindowFrame", 6, null).parameterTypes[2]
    val constructor = type.declaredConstructors.single { it.parameterCount == 14 }.apply { isAccessible = true }
    val callback: (Any?, Any?) -> Unit = { _, _ -> }
    return constructor.newInstance(0L, callback, callback, if (hidden) 0f else 32f, !hidden, 32f, true, constructor.parameterTypes[7].enumConstants.first(), true, 1f, 1f, true, true, !hidden)
}

@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
private fun checkGeniePixels() = edt {
    val frame = androidx.compose.ui.platform.FrameRecomposer(Dispatchers.Unconfined)
    val scene = androidx.compose.ui.scene.CanvasLayersComposeScene(frameRecomposer = frame,
        density = androidx.compose.ui.unit.Density(1f), size = androidx.compose.ui.unit.IntSize(320, 240))
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(320, 240)
    val genie = MvGenie()
    var progress by mutableFloatStateOf(0f)
    try {
        scene.setContent {
            PlayerCanvas(androidx.compose.ui.Modifier.fillMaxSize().then(genie.modifier({ androidx.compose.ui.geometry.Rect(260f, 200f, 300f, 230f) }, { progress }, prewarm = true))) {
                drawRect(androidx.compose.ui.graphics.Color(0xFF47657B))
                drawRect(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.geometry.Offset(148f, 0f), androidx.compose.ui.geometry.Size(24f, 240f))
            }
        }
        fun render(value: Float): org.jetbrains.skia.Bitmap {
            progress = value; Snapshot.sendApplyNotifications(); frame.performFrame((value * 1e9).toLong()); scene.measureAndLayout()
            surface.canvas.clear(0xFF000000.toInt()); scene.draw(surface.canvas.asComposeCanvas())
            return surface.makeImageSnapshot().use { org.jetbrains.skia.Bitmap.makeFromImage(it) }
        }
        render(0f).use { check(it.getColor(280, 220) == 0xFF000000.toInt()) }
        render(.5f).use { bitmap ->
            fun left(y: Int) = (0..319).first { bitmap.getColor(it, y) != 0xFF000000.toInt() }
            fun stripe(y: Int) = (0..319).first { bitmap.getColor(it, y) == 0xFFFFFFFF.toInt() }
            check(left(220) > left(115) + 35 && stripe(220) > stripe(115) + 25) { "Genie silhouette or texture did not bend" }
        }
        render(1f).use { for (x in listOf(0, 319)) for (y in listOf(0, 239)) check(it.getColor(x, y) == 0xFF47657B.toInt()) }
        println("PASS: genie shader bends silhouette and texture and ends at the exact viewport")
    } finally { scene.close(); frame.close(); surface.close(); genie.close() }
}

private fun skiaLayer(view: ComposeWindow): org.jetbrains.skiko.SkiaLayer {
    fun layers(component: java.awt.Component): List<org.jetbrains.skiko.SkiaLayer> =
        if (component is org.jetbrains.skiko.SkiaLayer) listOf(component)
        else (component as? java.awt.Container)?.components?.flatMap(::layers).orEmpty()
    return layers(view).single()
}
private fun described(node: androidx.compose.ui.semantics.SemanticsNode, label: String) =
    node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) && label in node.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
private fun visibleNodes(view: ComposeWindow): List<androidx.compose.ui.semantics.SemanticsNode> {
    fun tree(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
    return view.semanticsOwners.flatMap { tree(it.rootSemanticsNode) }.filter { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
}
private fun hasControl(view: ComposeWindow, label: String) = visibleNodes(view).any { described(it, label) }

@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
private fun checkActivePageInput(control: MvController) = edt {
    val failures = ConcurrentLinkedQueue<Throwable>()
    val frame = androidx.compose.ui.platform.FrameRecomposer(kotlinx.coroutines.Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> failures.add(error) })
    val scene = androidx.compose.ui.scene.CanvasLayersComposeScene(frameRecomposer = frame,
        density = androidx.compose.ui.unit.Density(1f), size = androidx.compose.ui.unit.IntSize(1120, 760))
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(1120, 760)
    val originalAutoHide = control.page.autoHide.value
    control.page.autoHide.value = false
    var originalPageClicks = 0
    try {
        scene.setContent { NativeUi.AppTheme { CompositionLocalProvider(MvHostWindow.windowLocal provides WindowState()) { MvPageHook(control.page) { true }.Layer {
            Box(NativeUi.clickable(androidx.compose.ui.Modifier.fillMaxSize().zIndex(Float.MAX_VALUE),
                androidx.compose.ui.semantics.Role.Button) { originalPageClicks++ })
        } } } }
        var time = 0L
        fun frames(count: Int) { repeat(count) {
            val loop = java.awt.Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
            javax.swing.Timer(16) { loop.exit() }.apply { isRepeats = false; start() }; loop.enter()
            Snapshot.sendApplyNotifications(); time += 20_000_000; frame.performFrame(time)
            scene.measureAndLayout(); scene.draw(surface.canvas.asComposeCanvas())
            failures.poll()?.let { throw it }
        } }
        fun click(label: String) {
            fun tree(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
            val mainOwner = scene.javaClass.getDeclaredField("mainOwner").apply { isAccessible = true }.get(scene)
            val owner = mainOwner.javaClass.getMethod("getOwner").invoke(mainOwner) as androidx.compose.ui.node.Owner
            val all = tree(owner.semanticsOwner.rootSemanticsNode)
            val point = (all.firstOrNull { described(it, label) }
                ?: error("Missing header $label: ${all.map { it.config.toString() }}")).boundsInRoot.center
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, point)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, point,
                buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            frames(1)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, point,
                buttons = androidx.compose.ui.input.pointer.PointerButtons(), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            frames(2)
        }
        frames(45)
        val mainOwner = scene.javaClass.getDeclaredField("mainOwner").apply { isAccessible = true }.get(scene)
        val owner = mainOwner.javaClass.getMethod("getOwner").invoke(mainOwner) as androidx.compose.ui.node.Owner
        fun tree(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        val more = tree(owner.semanticsOwner.rootSemanticsNode).first { described(it, "更多设置") }.boundsInRoot.center
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, more)
        frames(80)
        println("PASS: native MV tooltip renders without an inherited popup haze provider")
        repeat(6) {
            click("更多设置"); check(control.page.panel.value == MvPanel.More) { "Full-page pointer handling cancelled More click $it" }
            frames(8)
            click("更多设置"); check(control.page.panel.value == MvPanel.None) { "Full-page pointer handling cancelled More dismissal $it" }
            frames(8)
        }
        check(originalPageClicks == 0) { "MV control click leaked into the original song page" }
        click("返回歌曲")
        check(!control.page.isOpen) { "Full-page pointer handling cancelled Back" }
        println("PASS: full MV overlay accepts repeated native More and Back pointer clicks above the original page input shield")
    } finally { control.page.autoHide.value = originalAutoHide; scene.close(); frame.close(); surface.close() }
}

private fun checkLinkPromptThemes(view: ComposeWindow) {
    val themeProperty = com.xuncorp.voxzen.util.AppConfig::class.java.getDeclaredField("lightDarkTheme\$delegate").apply { isAccessible = true }.get(null)
    @Suppress("UNCHECKED_CAST")
    val themeState = themeProperty.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(themeProperty) as MutableState<Any>
    val original = edt { themeState.value }
    try {
        for (dark in listOf(false, true)) {
            edt { themeState.value = Class.forName("com.xuncorp.voxzen.ui.theme.LightDarkTheme").enumConstants.single {
                (it as Enum<*>).name == if (dark) "Dark" else "Light"
            } }
            Thread.sleep(350)
            edt {
                val body = visibleNodes(view).first { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.PaneTitle)
                    && it.config[androidx.compose.ui.semantics.SemanticsProperties.PaneTitle] == "确认 MV 链接" }.boundsInRoot
                skiaLayer(view).screenshot()!!.use { bitmap ->
                    val color = bitmap.getColor((body.left + 3).toInt(), (body.top + 3).toInt())
                    val brightness = listOf(color shr 16 and 255, color shr 8 and 255, color and 255).average()
                    check(if (dark) brightness < 130 else brightness > 180) { "Native link dialog did not follow SPW theme: dark=$dark; color=$color; bounds=$body" }
                }
                saveNative(view, if (dark) "mv-link-confirmation-dark" else "mv-link-confirmation-light")
                saveNative(view, "mv-link-confirmation")
            }
        }
        edt {
            val cancel = visibleNodes(view).first { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)
                && it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { text -> text.text == "暂不链接" }
                && it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) }
            check(cancel.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!.invoke())
        }
        println("PASS: native SPW link dialog follows light and dark themes and native cancel preserves the song")
    } finally { edt { themeState.value = original } }
}
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
private fun checkClosedPageInput(page: MvPage) = edt {
    val frame = androidx.compose.ui.platform.FrameRecomposer(kotlinx.coroutines.Dispatchers.Unconfined)
    val scene = androidx.compose.ui.scene.CanvasLayersComposeScene(frameRecomposer = frame,
        density = androidx.compose.ui.unit.Density(1f), size = androidx.compose.ui.unit.IntSize(320, 240))
    var clicks = 0
    try {
        scene.setContent { NativeUi.Theme {
            Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                PlayerButton("原页面入口", MvIcon.Play) { clicks++ }
                page.Overlay()
            }
        } }
        frame.performFrame(1L); scene.measureAndLayout()
        val point = androidx.compose.ui.geometry.Offset(160f, 120f)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, point,
            buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, point,
            buttons = androidx.compose.ui.input.pointer.PointerButtons(), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
        check(clicks == 1) { "Closed MV page intercepts the original page input" }
        println("PASS: retained closed MV page does not block the original entry")
    } finally { scene.close() }
}
private fun saveNative(view: ComposeWindow, name: String) {
    skiaLayer(view).screenshot()!!.use { bitmap -> org.jetbrains.skia.Image.makeFromBitmap(bitmap).use { image ->
        image.encodeToData()!!.use { Files.write(Path.of("build/$name.png"), it.bytes) }
    } }
}
private fun captureOpening(view: ComposeWindow, page: MvPage, source: androidx.compose.ui.geometry.Rect) {
    edt { page.hide() }
    await("preview returns before replaying the expansion") { !page.isVisible }
    val finished = edt {
        val result = CompletableFuture<Unit>()
        HostBridge.captureEntry(source); page.show()
        for ((delay, name) in listOf(140 to "mv-opening-early", 400 to "mv-opening-middle", 950 to "mv-opening-end")) {
            javax.swing.Timer(delay) { try { saveNative(view, name) } catch (error: Throwable) { result.completeExceptionally(error) } }
                .apply { isRepeats = false; start() }
        }
        javax.swing.Timer(1150) { result.complete(Unit) }.apply { isRepeats = false; start() }
        result
    }
    finished.get(5, TimeUnit.SECONDS)
    println("PASS: expansion previews captured separately from motion timing")
}
private fun observeOpening(view: ComposeWindow, page: MvPage): CompletableFuture<Unit> = edt {
    val finished = CompletableFuture<Unit>()
    // Keep this isolated host visible while measuring its native renderer.
    val onTop = view.isAlwaysOnTop
    view.isAlwaysOnTop = true; view.toFront()
    val layer = skiaLayer(view); val delegate = layer.renderDelegate!!
    val bounds = view.bounds; val windows = java.awt.Window.getWindows().count { it.isShowing }
    val durations = mutableListOf<Double>(); val intervals = mutableListOf<Double>()
    val gaps = mutableListOf<String>()
    val activeIntervals = mutableListOf<Double>()
    var lastActive = false
    var start = 0L; var last = 0L; var resizes = 0
    view.addComponentListener(object : java.awt.event.ComponentAdapter() {
        override fun componentResized(event: java.awt.event.ComponentEvent) { if (start != 0L && !finished.isDone) resizes++ }
    })
    layer.renderDelegate = object : org.jetbrains.skiko.SkikoRenderDelegate {
        override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) {
            val before = System.nanoTime()
            delegate.onRender(canvas, width, height, nanoTime)
            if (page.isOpen && start == 0L) {
                start = before
                javax.swing.Timer(50) { event ->
                    if (!page.motionReady && System.nanoTime() - start < 6_000_000_000L) return@Timer
                    (event.source as javax.swing.Timer).stop()
                    try {
                        check(page.motionReady) { "First expansion did not finish" }
                        check(resizes == 0 && bounds == view.bounds)
                        check(java.awt.Window.getWindows().count { it.isShowing } == windows)
                        layer.screenshot()!!.use { bitmap ->
                            for (x in listOf(0, bitmap.width - 1)) for (y in listOf(0, bitmap.height - 1))
                                check(bitmap.getColor(x, y) == 0xFF000000.toInt()) {
                                    saveNative(view, "mv-motion-gap")
                                    "Root page leaves a gap at $x,$y: ${Integer.toHexString(bitmap.getColor(x, y))}; root=${page.rootBounds}"
                                }
                        }
                        fun p95(values: List<Double>) = values.sorted().let { it.getOrElse(((it.size - 1) * .95).toInt().coerceAtLeast(0)) { 0.0 } }
                        println("MOTION_PAGE: api=${view.renderApi}; samples=${durations.size}; nativeResizes=$resizes; newWindows=0; recordP95Ms=${p95(durations)}; recordMaxMs=${durations.maxOrNull()}; callbackP95Ms=${p95(intervals)}; callbackMaxMs=${intervals.maxOrNull()}")
                        println("MOTION_GAPS: ${gaps.joinToString()}")
                        println("MOTION_TWEEN: samples=${activeIntervals.size}; callbackP95Ms=${p95(activeIntervals)}; callbackMaxMs=${activeIntervals.maxOrNull()}")
                        println("PASS: button container expands in the host renderer without another window or native resize")
                        layer.renderDelegate = delegate
                        finished.complete(Unit)
                    } catch (error: Throwable) { finished.completeExceptionally(error) }
                    finally { view.isAlwaysOnTop = onTop }
                }.apply { initialDelay = 1450; start() }
            }
            if (start != 0L && !finished.isDone && before - start < 1_250_000_000L) {
                durations += (System.nanoTime() - before) / 1e6
                if (last != 0L) {
                    val interval = (before - last) / 1e6
                    intervals += interval
                    if (interval > 30) gaps += "at ${(before - start) / 1e6}ms: ${interval}ms"
                    if (lastActive && page.motionRunning) activeIntervals += interval
                }
                last = before
                lastActive = page.motionRunning
            }
        }
    }
    finished
}

@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
private fun render(control: MvController) = edt {
    val frame = androidx.compose.ui.platform.FrameRecomposer(kotlinx.coroutines.Dispatchers.Unconfined)
    val scene = androidx.compose.ui.scene.CanvasLayersComposeScene(frameRecomposer = frame,
        density = androidx.compose.ui.unit.Density(1f), size = androidx.compose.ui.unit.IntSize(1120, 760))
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(1120, 760)
    try {
        scene.setContent { NativeUi.Theme { NativeUi.Dark { control.page.Content(control.screen.value) } } }
        control.toggle()
        var time = 0L
        fun drawFrames(count: Int = 4) { repeat(count) {
            val loop = java.awt.Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
            javax.swing.Timer(20) { loop.exit() }.apply { isRepeats = false; start() }; loop.enter()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            time += 20_000_000; frame.performFrame(time); scene.measureAndLayout()
            surface.canvas.clear(0xFF000000.toInt()); scene.draw(surface.canvas.asComposeCanvas())
        } }
        fun settle(label: String, test: () -> Boolean) {
            repeat(50) { if (test()) return; drawFrames(2) }
            error("UI did not settle: $label; native volume=${control.screen.value.playback.volume}, control volume=${control.screen.value.volume}")
        }
        repeat(16) { index ->
            val loop = java.awt.Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
            javax.swing.Timer(20) { loop.exit() }.apply { isRepeats = false; start() }; loop.enter()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            frame.performFrame((index + 1L) * 32_000_000)
            scene.measureAndLayout()
            scene.draw(surface.canvas.asComposeCanvas())
            if (index == 1) surface.makeImageSnapshot().use { image ->
                val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
                bitmap.use { check(it.getColor(140, 300) == 0xFF000000.toInt()) { "First video texture appeared before its reveal curtain" } }
            }
        }
        time = 16L * 32_000_000
        surface.makeImageSnapshot().use { image -> org.jetbrains.skia.Bitmap.makeFromImage(image).use {
            check(it.getColor(140, 300) != 0xFF000000.toInt()) { "Video reveal never reached its visible state" }
        } }
        println("PASS: reveal starts black, then exposes the real decoded frame")
        settle("pause for deterministic UI checks") { control.screen.value.playback.paused }
        Files.write(Path.of("build/mv-window.png"), surface.makeImageSnapshot().use { it.encodeToData()!!.use { it.bytes } })
        val mainOwner = scene.javaClass.getDeclaredField("mainOwner").apply { isAccessible = true }.get(scene)
        val owner = mainOwner.javaClass.getMethod("getOwner").invoke(mainOwner) as androidx.compose.ui.node.Owner
        fun nodes(): List<androidx.compose.ui.semantics.SemanticsNode> {
            fun tree(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
            return tree(owner.semanticsOwner.rootSemanticsNode)
        }
        fun described(label: String) = nodes().first { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) && label in it.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription] }
        fun click(point: androidx.compose.ui.geometry.Offset) {
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, point,
                buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, point,
                buttons = androidx.compose.ui.input.pointer.PointerButtons(), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
        }
        fun drag(from: androidx.compose.ui.geometry.Offset, to: androidx.compose.ui.geometry.Offset) {
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, from,
                buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, to,
                buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true))
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, to,
                buttons = androidx.compose.ui.input.pointer.PointerButtons(), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
        }
        fun save(name: String) { Files.write(Path.of("build/$name.png"), surface.makeImageSnapshot().use { it.encodeToData()!!.use { it.bytes } }) }
        val play = described("播放").boundsInRoot
        surface.makeImageSnapshot().use { image -> org.jetbrains.skia.Bitmap.makeFromImage(image).use { bitmap ->
            val color = bitmap.getColor((play.center.x + 21).toInt(), play.center.y.toInt())
            check((color shr 16 and 255) < 50 && (color shr 8 and 255) < 50 && (color and 255) < 50) { "Play button still has a painted fill" }
        } }
        check(kotlin.math.abs(play.center.x - 560f) < 1f)
        check(kotlin.math.abs(described("播放顺序").boundsInRoot.center.y - play.center.y) < 2f) { "Native order button must align with transport controls" }
        check(described("上一首").boundsInRoot.right < play.left && described("下一首").boundsInRoot.left > play.right)
        check(described("全屏").boundsInRoot.right >= 1089f)
        check(described("MV 播放列表").boundsInRoot.right <= described("全屏").boundsInRoot.left)
        check(described("播放进度").boundsInRoot.height <= 32f)
        check(described("返回歌曲").boundsInRoot.left < 80f && described("更多设置").boundsInRoot.right > 1080f)
        check(nodes().none { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) &&
            it.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].any { value -> value in listOf("最小化", "最大化 / 还原") } })
        check(described("播放顺序").boundsInRoot.right < 200f)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        control.dispatch { entered.countDown(); check(release.await(8, TimeUnit.SECONDS)) }
        settle("transport worker is held for immediate-feedback regression") { entered.count == 0L }
        try {
            val bar = described("播放进度").boundsInRoot
            val destination = androidx.compose.ui.geometry.Offset(bar.left + 6 + (bar.width - 12) * .76f, bar.center.y)
            drag(bar.center, destination)
            check(abs(control.screen.value.playback.positionSeconds / control.screen.value.playback.durationSeconds - .76) < .001)
            repeat(14) {
                drawFrames(1)
                val shown = described("播放进度").config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
                check(abs(shown - .76f) < .001f) { "Timeline recoiled after releasing its thumb: $shown" }
            }
            click(described("播放").boundsInRoot.center)
            check(!control.screen.value.playback.paused) { "Play feedback waited for the worker" }
            drawFrames(1); check(described("暂停").boundsInRoot.width > 0)
            click(described("暂停").boundsInRoot.center)
            check(control.screen.value.playback.paused) { "Pause feedback waited for the worker" }
            drawFrames(1)
        } finally { release.countDown() }
        val drained = CompletableFuture<Unit>(); control.dispatch { drained.complete(Unit) }
        settle("queued transport commands reach backend") { drained.isDone }
        val actual = (MvController::class.java.getDeclaredField("player").apply { isAccessible = true }.get(control) as Player).state()
        check(actual.paused && abs(actual.positionSeconds - actual.durationSeconds * .76) < .1)
        println("PASS: pause/play feedback is immediate and released timeline never recoils while backend is delayed")
        control.toggle(); control.seek(1.5)
        drawFrames(50)
        check(control.screen.value.playback.positionSeconds in 1.9..3.5) { "Playing seek froze at its optimistic target or jumped to stale position" }
        control.toggle(); drawFrames(8)
        println("PASS: backward seek rejoins real playing progress without freezing at the target")
        fun choose(label: String) = nodes().first { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) &&
            it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { text -> text.text == label } && it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) }
        click(described("更多设置").boundsInRoot.center); drawFrames(14)
        val menuBounds = nodes().first { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.PaneTitle) &&
            it.config[androidx.compose.ui.semantics.SemanticsProperties.PaneTitle] == "更多设置" }.boundsInRoot
        surface.makeImageSnapshot().use { image -> org.jetbrains.skia.Bitmap.makeFromImage(image).use { bitmap ->
            val color = bitmap.getColor(menuBounds.center.x.toInt(), (menuBounds.top + 5).toInt())
            val channels = listOf(color shr 16 and 255, color shr 8 and 255, color and 255)
            check(channels.max() - channels.min() < 3 && channels.max() < 100) { "Settings surface lets the video colors bleed through" }
        } }
        save("mv-more")
        check(runCatching { choose("自动隐藏控件") }.isFailure) { "More must not expose every setting in one long list" }
        click(choose("画面").boundsInRoot.center); drawFrames(14)
        click(choose("裁剪铺满").boundsInRoot.center); drawFrames()
        check(control.page.fillVideo.value)
        settle("fill preference persists") { control.store.videoFill() }
        click(described("返回更多设置").boundsInRoot.center); drawFrames(14)
        click(choose("播放").boundsInRoot.center); drawFrames(14)
        save("mv-playback-settings")
        click(choose("自动隐藏控件").boundsInRoot.center); drawFrames()
        check(!control.page.autoHide.value)
        settle("auto-hide preference persists") { !control.store.controlsAutoHide() }
        click(choose("自动隐藏控件").boundsInRoot.center); drawFrames()
        click(described("返回更多设置").boundsInRoot.center); drawFrames(14)
        click(choose("画面").boundsInRoot.center); drawFrames(14)
        click(choose("画面适应").boundsInRoot.center); drawFrames()
        click(described("更多设置").boundsInRoot.center); drawFrames(14)
        click(described("播放顺序").boundsInRoot.center); drawFrames(14)
        save("mv-order")
        click(choose("随机播放").boundsInRoot.center); drawFrames(14)
        settle("order updates the host mode") { control.screen.value.order == "Random" }
        control.order("Circle"); drawFrames(4)
        println("PASS: back/more replace native window buttons, order and saved display settings are interactive")
        println("PASS: neutral unfilled transport and compact settings submenus are interactive")
        println("PASS: centered transport, thin seek hit area and rightmost fullscreen layout")
        click(described("播放速度").boundsInRoot.center); drawFrames(10)
        check(control.page.panel.value == MvPanel.Speed)
        save("mv-speed")
        click(described("播放").boundsInRoot.center); drawFrames()
        settle("toolbar plays while a menu is open") { !control.screen.value.playback.paused }
        click(described("暂停").boundsInRoot.center); drawFrames()
        settle("toolbar pauses while a menu is open") { control.screen.value.playback.paused }
        check(control.page.panel.value == MvPanel.Speed)
        val speed = nodes().first { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)
            && it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { text -> text.text == "2.0×" }
            && it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) }
        click(speed.boundsInRoot.center); drawFrames(12)
        check(control.page.panel.value == MvPanel.None)
        click(described("音量").boundsInRoot.center); drawFrames(12)
        val volume = nodes().first { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)
            && it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
            && "音量" in it.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription] }.boundsInRoot
        check(volume.height > volume.width * 2)
        drag(volume.center, androidx.compose.ui.geometry.Offset(volume.center.x, volume.top + volume.height / 4)); drawFrames()
        check(control.screen.value.volume in 65.0..85.0) { "Rotated SPW slider must increase upwards" }
        val volumeNode = nodes().first { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)
            && it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
            && "音量" in it.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription] }
        check(volumeNode.config[androidx.compose.ui.semantics.SemanticsActions.SetProgress].action!!(.75f))
        drawFrames()
        settle("volume drag") { control.screen.value.playback.volume == 75.0 && control.screen.value.volume == 75.0 }
        save("mv-volume")
        click(described("静音 / 恢复音量").boundsInRoot.center); drawFrames()
        settle("mute") { control.screen.value.playback.volume == 0.0 && control.screen.value.volume == 0.0 }
        click(androidx.compose.ui.geometry.Offset(100f, 100f)); drawFrames(12)
        click(described("音量").boundsInRoot.center); drawFrames(12)
        click(described("静音 / 恢复音量").boundsInRoot.center); drawFrames()
        settle("unmute across panel reopen") { control.screen.value.playback.volume == 75.0 && control.screen.value.volume == 75.0 }
        println("PASS: thin vertical volume slider preserves continuous drag, progress semantics and mute restore")
        click(androidx.compose.ui.geometry.Offset(100f, 100f)); drawFrames(12)
        click(described("MV 播放列表").boundsInRoot.center); drawFrames(10)
        check(control.page.panel.value == MvPanel.Playlist && control.screen.value.queue.size == 2)
        save("mv-playlist")
        click(described("收起播放列表").boundsInRoot.center); drawFrames(12)
        check(control.page.panel.value == MvPanel.None)
        scene.size = androidx.compose.ui.unit.IntSize(640, 360); drawFrames()
        val compactPlay = described("播放").boundsInRoot
        check(kotlin.math.abs(compactPlay.center.x - 320f) < 1f)
        check(described("下一首").boundsInRoot.right < described("播放速度").boundsInRoot.left)
        click(described("播放速度").boundsInRoot.center); drawFrames(12)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Scroll, androidx.compose.ui.geometry.Offset(410f, 150f),
            scrollDelta = androidx.compose.ui.geometry.Offset(0f, 24f))
        drawFrames(8)
        val lastSpeed = nodes().first { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text)
            && it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { text -> text.text == "3.0×" }
            && it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) }.boundsInRoot
        check(lastSpeed.height >= 30f && lastSpeed.top >= 0f && lastSpeed.bottom <= 260f) { "Small-window speed menu must scroll to 3x" }
        click(lastSpeed.center); drawFrames(12)
        check(control.page.panel.value == MvPanel.None)
        control.speed(1.0)
        println("PASS: speed menu selection and small-window scrolling, vertical volume drag, persistent unmute and MV playlist open/close")
    } finally { scene.close(); frame.close(); surface.close() }
}
