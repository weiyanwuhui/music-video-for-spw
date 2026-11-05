@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.runtime.tooling.ComposeToolingApi::class)
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package local.spw.mv

import com.xuncorp.spw.workshop.api.*
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import dev.gaboron.spwmedia.core.*
import dev.gaboron.spwmedia.playback.PlaybackProvider
import dev.gaboron.spwmedia.processing.ProcessingProvider
import dev.gaboron.spwmedia.metadata.MetadataProvider
import dev.gaboron.spwmedia.compose.MediaSurface
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.node.Owner
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Surface
import java.lang.reflect.Proxy
import java.nio.file.*
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.*

/** Real libmpv and real pointer dispatch in the complete retained root; no physical monitor is required. */
fun main(args: Array<String>) {
    val root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "mv-redesign-")
    val runtimeRoot = Path.of(System.getProperty("spw.test.runtime", "build/runtime")).toAbsolutePath()
    val assets = NativeAssets(runtimeRoot.resolve("native"), root.resolve("native"))
    val workerClasspath = Files.list(runtimeRoot.resolve("lib")).use { files -> files.filter { it.toString().endsWith(".jar") }.map { it.toString() }.toList() }
        .plus(Path.of(kotlin.Unit::class.java.protectionDomain.codeSource.location.toURI()).toString()).joinToString(java.io.File.pathSeparator)
    val engine = RuntimeEngine(listOf(ProcessingProvider(), PlaybackProvider(), MetadataProvider()), assets, root.resolve("runtime-data"), emptyMap(), workerCommand = { mode ->
        if (mode == "playback") WorkerCommands.playback(assets, workerClasspath, Path.of(System.getProperty("spw.test.javaHome"))) else null
    })
    val folder = Files.createDirectory(root.resolve("MV"))
    val a = Files.copy(Path.of("build/synthetic-mv.mp4"), folder.resolve("01 - Justin Bieber - Baby.mp4"))
    val b = Files.copy(a, folder.resolve("02 - Baby – Clean Bandit Ft. Marina & Luis Fonsi 中英字幕.mp4"))
    val c = Files.copy(a, folder.resolve("03 - Unlinked Clip.mp4"))
    val audio = Files.copy(Path.of("build/synthetic-artwork.mp3"),root.resolve("A.mp3"))
    val songA = MediaItem("Baby", "Justin Bieber/Ludacris", "Baby", "", audio.toString(), id = "a", duration = 216000)
    val songB = MediaItem("Baby (feat. MARINA & Luis Fonsi)", "Clean Bandit/MARINA/Luis Fonsi", "What Is Love?", "", root.resolve("B.mp3").toString(), id = "b", duration = 205000)
    val current = AtomicReference<MediaItem?>(songA)
    val playing = AtomicBoolean(true)
    val position = AtomicLong(3456)
    val resumes = AtomicInteger()
    val playback = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader, arrayOf(WorkshopApi.Playback::class.java)) { _, method, args -> when (method.name) {
        "getCurrentMediaItem" -> current.get()
        "pause" -> { playing.set(false); null }
        "play" -> { playing.set(true); resumes.incrementAndGet(); null }
        "seekTo" -> { position.set(args[0] as Long); null }
        "next", "previous" -> error("MV navigation touched the SPW song queue")
        else -> error("Unexpected playback call $method")
    } } as WorkshopApi.Playback
    val library = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader, arrayOf(WorkshopApi.Library::class.java)) { _, _, _ -> CompletableFuture.completedFuture(listOf(songA, songB)) } as WorkshopApi.Library
    val ui = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader, arrayOf(WorkshopApi.Ui::class.java)) { _, _, _ -> null } as WorkshopApi.Ui
    WorkshopApi.instance = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader, arrayOf(WorkshopApi::class.java)) { _, method, _ -> when (method.name) {
        "getPlayback" -> playback; "getLibrary" -> library; "getUi" -> ui; else -> error("Unexpected API call $method")
    } } as WorkshopApi
    val store = MvStore(root.resolve("config/mv.properties"))
    store.saveFolder(folder); store.setControlsAutoHide(false); store.bind(songA,b,automatic = true)
    val host = edt { ComposeWindow() }
    val sidebarPreference = AtomicBoolean(true)
    val settings = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader, arrayOf(com.xuncorp.spw.workshop.api.config.ConfigHelper::class.java)) { _, method, arguments ->
        when (method.name) {
            "get" -> if (arguments[0] == "show_sidebar") sidebarPreference.get() else arguments[1]
            "reload" -> true
            else -> error("Unexpected settings call $method")
        }
    } as com.xuncorp.spw.workshop.api.config.ConfigHelper
    val control = MvController("local.spw.mv.redesign.test", store, settings = settings, hostWindow = { host }) { position.get() to playing.get() }
    val endpoint = RuntimeEndpoint(engine) { "local.spw.mv.redesign.test" }
    val frame = FrameRecomposer(Dispatchers.Unconfined)
    val bench = "--danmaku-benchmark" in args
    val width = if (bench) 2560 else 1000
    val height = if (bench) 1440 else 700
    val scene = CanvasLayersComposeScene(frameRecomposer = frame, density = Density(1f), size = IntSize(width, height))
    val surface = Surface.makeRasterN32Premul(width, height)
    val hostState = WindowState(size = DpSize(width.dp, height.dp))
    var originalClicks = 0
    var originalMounts = 0
    var originalDisposals = 0
    fun draw() = edt { Snapshot.sendApplyNotifications(); frame.performFrame(System.nanoTime()); scene.measureAndLayout(); surface.canvas.clear(0xFF000000.toInt()); scene.draw(surface.canvas.asComposeCanvas()) }
    fun await(label: String, timeout: Long = 15000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        while (!condition()) { check(System.nanoTime() < deadline) { "Timed out: $label; ${control.screen.value.message}" }; draw(); Thread.sleep(15) }
        draw(); println("PASS: $label")
    }
    fun settle(ms: Long = 240) { val end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms); do { draw(); Thread.sleep(15) } while (System.nanoTime()<end) }
    fun nodes(unmerged: Boolean = false): List<SemanticsNode> = edt {
        val ownerObject = scene.javaClass.getDeclaredField("mainOwner").apply { isAccessible = true }.get(scene)
        val owner = ownerObject.javaClass.getMethod("getOwner").invoke(ownerObject) as Owner
        fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
        all(if (unmerged) owner.semanticsOwner.unmergedRootSemanticsNode else owner.semanticsOwner.rootSemanticsNode)
    }
    fun described(label: String) = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
    fun button(label: String) = nodes().first { it.config.contains(SemanticsActions.OnClick) &&
        (it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true || it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true) }
    fun event(type: PointerEventType, point: Offset, pressed: Boolean) = edt { scene.sendPointerEvent(type, point,
        buttons = PointerButtons(isPrimaryPressed = pressed), button = if (type == PointerEventType.Move) null else PointerButton.Primary) }
    fun click(point: Offset) { event(PointerEventType.Press,point,true); event(PointerEventType.Release,point,false); draw() }
    fun click(label: String) = click(button(label).boundsInRoot.center)
    fun save(name: String) = edt { surface.makeImageSnapshot().use { it.encodeToData()!!.use { Files.write(Path.of("build/$name.png"), it.bytes) } } }
    fun rootContent(content: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(Color(0xFFE6EDF0))) {
            Box(Modifier.fillMaxSize().zIndex(Float.MAX_VALUE).clickable { originalClicks++ })
        }
    }) = edt { scene.setContent { NativeUi.Theme { CompositionLocalProvider(MvHostWindow.windowLocal provides hostState) {
        MvPageHook(control.page) { true }.Layer {
            DisposableEffect(Unit) { originalMounts++; onDispose { originalDisposals++ } }
            content()
        }
    } } } }
    try {
        if ("--danmaku-benchmark" in args) {
            val requested = System.getenv("MV_TEST_BILI_URL")?.takeIf { it.isNotBlank() }
            val biliApi = BiliApi()
            val onlineVideo = requested?.let { biliApi.resolve(biliApi.link(it)) }
            val comments = onlineVideo?.let { biliApi.danmaku(it.cid,1) } ?: (0 until 3000).map { i -> BiliDanmaku(i.toLong(), i * .05, 1, 25, 0xffffff,
                "弹幕性能测试 ${i % 97} ABC测试文字", "", 5) }
            val video = onlineVideo ?: BiliVideo(BiliLink("BV1GJ411x7h7"),1,"测试",150.0,emptyList())
            store.setDanmakuSetting("area", ".25"); store.setDanmakuSetting("scale", ".75")
            store.setDanmakuSetting("duration", "12"); store.setDanmakuSetting("opacity", ".8")
            if (onlineVideo != null) {
                println("BILIBILI_BENCH: ${comments.size} real comments, anonymous stream, matched local display settings")
            }
            val playback = dev.gaboron.spwmedia.protocol.PlayerState(paused=false,positionSeconds=20.0)
            edt { scene.setContent { MvDanmaku(MvScreenState(online=video,danmaku=comments,playback=playback),control) } }
            if (System.getenv("MV_TEST_WINDOW_ONLY") != "1") {
                settle(500)
                val samples = LongArray(120) {
                    val start = System.nanoTime(); draw(); System.nanoTime() - start
                }.sorted()
                println("BENCH: ${comments.size} comments, software raster ${width}x${height}, 120 draw frames; median=${samples[60]/1e6}ms p95=${samples[114]/1e6}ms over16.7ms=${samples.count { it > 16_700_000 }}")
            }
            rootContent(); settle()
            await("benchmark local queue is ready") { control.screen.value.queue.size == 3 }
            if (onlineVideo != null && System.getenv("MV_TEST_LOCAL_VIDEO") != "1") { control.onlineStore.bind(songA,onlineVideo.link); control.refreshPlaylist(); await("benchmark online link is ready") { control.screen.value.queue.size == 4 } }
            control.open()
            await("benchmark video opens",30000) { control.screen.value.frames != null && !control.screen.value.playback.paused }
            fun measureCombined(overlay: Boolean, layered: Boolean = false) {
                edt { scene.setContent { Box(Modifier.fillMaxSize()) {
                    val live = control.screen.value
                    val layer = if (layered) Modifier.graphicsLayer() else Modifier
                    live.frames?.let { MediaSurface(it, layer.fillMaxSize()) }
                    if (overlay) Box(layer.fillMaxSize()) { MvDanmaku(live.copy(online=video, danmaku=comments),control) }
                } } }
                settle(500)
                val elapsed = LongArray(180)
                val intervals = LongArray(179)
                var previous = System.nanoTime()
                repeat(elapsed.size) { i ->
                    val start = System.nanoTime()
                    if (i > 0) intervals[i-1] = start - previous
                    previous = start
                    draw()
                    elapsed[i] = System.nanoTime() - start
                    val remaining = 16_666_667L - (System.nanoTime() - start)
                    if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
                }
                elapsed.sort(); intervals.sort()
                println("VIDEO_BENCH: overlay=$overlay layered=$layered; draw median=${elapsed[90]/1e6}ms p95=${elapsed[171]/1e6}ms; frame interval p95=${intervals[170]/1e6}ms over25ms=${intervals.count { it>25_000_000 }}")
            }
            if (System.getenv("MV_TEST_WINDOW_ONLY") != "1") {
                measureCombined(false)
                measureCombined(true)
                measureCombined(true, true)
            }
            val frameTimes = java.util.Collections.synchronizedList(mutableListOf<Long>())
            val showOverlay = mutableStateOf(false)
            edt {
                val display = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
                host.setSize(display.width,display.height)
                host.setLocationRelativeTo(null)
                host.setContent { NativeUi.Theme { CompositionLocalProvider(MvHostWindow.windowLocal provides hostState) {
                    Box(Modifier.fillMaxSize()) {
                        MvPageHook(control.page) { true }.Layer { MvLibraryPage(control) }
                        val live = control.screen.value
                        if (showOverlay.value) MvDanmaku(live.copy(online=video, danmaku=comments), control)
                    }
                } } }
                host.isVisible = true
                fun layer(component: java.awt.Component): org.jetbrains.skiko.SkiaLayer? = if (component is org.jetbrains.skiko.SkiaLayer) component
                    else (component as? java.awt.Container)?.components?.firstNotNullOfOrNull(::layer)
                val native = requireNotNull(layer(host))
                val delegate = requireNotNull(native.renderDelegate)
                native.renderDelegate = object : org.jetbrains.skiko.SkikoRenderDelegate {
                    override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) {
                        delegate.onRender(canvas,width,height,nanoTime)
                        frameTimes.add(System.nanoTime())
                    }
                }
            }
            fun measureWindow(overlay: Boolean) {
                edt { showOverlay.value = overlay }
                Thread.sleep(1000); frameTimes.clear()
                val bean = java.lang.management.ManagementFactory.getThreadMXBean()
                val renderThreads = Thread.getAllStackTraces().keys.filter { it.name.startsWith("skiko-dispatcher") }.map { it.threadId() }
                val beforeCpu = renderThreads.associateWith { bean.getThreadCpuTime(it) }
                Thread.sleep(4000)
                val renderCpu = renderThreads.sumOf { (bean.getThreadCpuTime(it)-beforeCpu.getValue(it)).coerceAtLeast(0) } / 1e6
                val observed = synchronized(frameTimes) { frameTimes.toList() }
                val gaps = observed.zipWithNext { a,b -> b-a }.sorted()
                if (gaps.isEmpty()) error("Visible window produced no measured frames")
                println("WINDOW_BENCH: overlay=$overlay renderApi=${edt { host.renderApi }} frames=${observed.size} renderThreadCpu=${renderCpu}ms/4000ms interval median=${gaps[gaps.size/2]/1e6}ms p95=${gaps[(gaps.size*.95).toInt().coerceAtMost(gaps.lastIndex)]/1e6}ms over8ms=${gaps.count { it>8_000_000 }} over16ms=${gaps.count { it>16_000_000 }}")
            }
            measureWindow(false)
            measureWindow(true)
            if (System.getenv("MV_TEST_WINDOW_ONLY") == "1") measureWindow(true)
            return
        }
        if ("--artwork-only" in args) {
            kotlinx.coroutines.runBlocking {
                val bytes = control.artwork.load(MvQueueEntry(a,songA)) ?: error("Embedded cover was empty")
                check(javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)).width <= 256)
                val fallback = control.artwork.load(MvQueueEntry(c)) ?: error("Video frame was empty")
                check(javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(fallback)).width <= 256)
            }
            edt { scene.setContent { NativeUi.Theme { MvLibraryPage(control) } } }
            await("library rows display real cached cover images",30000) { nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith("MV 封面 ") } == true } == 3 }
            save("mv-0.7.2-library")
            edt { scene.setContent { NativeUi.Theme { NativeUi.Light { MvLibraryPage(control) } } } }
            await("light SPW library renders all cover rows",30000) { nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith("MV 封面 ") } == true } == 3 }
            save("mv-0.7.2-library-light")
        }
        else if ("--sidebar-only" !in args) {
        rootContent(); settle()
        await("local queue is ready for online identity regression") { control.screen.value.queue.size==3 }
        val onlineLink=BiliLink("BV1GJ411x7h7")
        control.onlineStore.bind(songA,onlineLink)
        control.bind(folder.resolve("missing-video.mp4"),songA,playAfter=false)
        await("failed local binding reports an error") { control.screen.value.message.contains("请选择可读的视频文件") }
        check(control.onlineStore.bound(songA)?.link == onlineLink)
        println("PASS: failed local binding restores the previous online link")
        edt { control.screen.value=control.screen.value.copy(video=onlineLink.key) }
        control.refreshPlaylist()
        await("online source is not duplicated as a local file in MV queue") { control.screen.value.queue.size==4 && control.screen.value.queue.count { it.file==onlineLink.key }==1 }
        check(control.screen.value.queue.single { it.file==onlineLink.key }.online==onlineLink)
        control.onlineStore.bind(songA,null)
        edt { control.screen.value=control.screen.value.copy(video=null) }
        control.refreshPlaylist()
        await("online queue fixture leaves local associations unchanged") { control.screen.value.queue.size==3 }
        control.textAction("link")
        await("native Bilibili link prompt mounts above retained input shield") { control.screen.value.textRequest != null }
        click("取消")
        check(control.screen.value.textRequest == null)
        println("PASS: native Bilibili link prompt cancel is clickable without a network request")
        val beforeExport = control.onlineStore.all()
        control.textAction("export")
        await("backup export prompt opens") { control.screen.value.textRequest?.kind == "export" }
        click("使用默认位置"); settle()
        check(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains("Music Video for SPW backup.json") == true })
        click("选择文件夹")
        await("backup folder browser opens") { control.screen.value.directoryBrowser?.busy == false }
        val backupFolder = Files.createDirectory(root.resolve("备份目录"))
        control.browseDirectory(root)
        await("backup browser lists the selected parent") { control.screen.value.directoryBrowser?.folders?.contains(backupFolder) == true }
        click("打开文件夹 ${backupFolder.fileName}")
        await("backup browser enters selected directory") { control.screen.value.directoryBrowser?.let { !it.busy && it.path == backupFolder } == true }
        click("选择此文件夹")
        await("backup destination returns to export prompt") { control.screen.value.directoryBrowser == null && control.screen.value.textRequest?.initial?.startsWith(backupFolder.toString()) == true }
        val backupFile = backupFolder.resolve("Music Video for SPW backup.json")
        check(!Files.exists(backupFile))
        click("确认")
        await("backup exports to the chosen folder") { Files.isRegularFile(backupFile) && control.screen.value.textRequest == null }
        check(MvOnlineStore.read(backupFile) == beforeExport)
        control.textAction("export")
        await("backup export prompt can reopen") { control.screen.value.textRequest?.kind == "export" }
        click("取消"); settle()
        check(control.screen.value.textRequest == null && control.onlineStore.all() == beforeExport)
        println("PASS: backup export suggests a path and cancel leaves data unchanged")
        val qr = com.google.zxing.qrcode.QRCodeWriter().encode("https://www.bilibili.com/",com.google.zxing.BarcodeFormat.QR_CODE,240,240)
        val image = java.awt.image.BufferedImage(240,240,java.awt.image.BufferedImage.TYPE_INT_RGB)
        for(y in 0 until 240) for(x in 0 until 240) image.setRGB(x,y,if(qr[x,y])0 else 0xffffff)
        val qrBytes=java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image,"png",it) }.toByteArray()
        edt { control.screen.value=control.screen.value.copy(login=BiliLoginState(qrBytes,"测试二维码，不会登录")) }
        settle(); save("mv-0.8.0-login")
        click("关闭")
        check(control.screen.value.login==null)
        println("PASS: native QR login modal renders and closes above original page")
        val fakeVideo=BiliVideo(BiliLink("BV1GJ411x7h7"),1,"测试",100.0,emptyList())
        val dm=BiliDanmaku(1,0.0,1,25,0xffffff,"弹幕渲染测试", "",5)
        edt { scene.setContent { NativeUi.Dark { Box(Modifier.fillMaxSize().background(Color.Black)) {
            MvDanmaku(MvScreenState(online=fakeVideo,danmaku=listOf(dm,dm.copy(id=2,mode=5),dm.copy(id=3,mode=4),dm.copy(id=4,mode=7,text="[{},{},{},{},{}]")),playback=dev.gaboron.spwmedia.protocol.PlayerState(paused=true,positionSeconds=2.0)),control)
        } } } }
        settle(); save("mv-0.8.0-danmaku")
        println("PASS: native danmaku renderer draws scrolling top and bottom comments on a paused media clock")
        val qualityVideo = fakeVideo.copy(qualities = listOf(BiliQuality(80,"1080P 高清","",null), BiliQuality(64,"720P 高清","",null)))
        edt { scene.setContent { NativeUi.Dark { MvPanels(control.page,control,control.screen.value.copy(online=qualityVideo,quality=80)) } }; control.page.panel.value = MvPanel.Quality }
        settle()
        val qualityPane = nodes().first { it.config.getOrNull(SemanticsProperties.PaneTitle) == "画质" }.boundsInRoot
        check(qualityPane.top > height / 2 && qualityPane.right <= width)
        println("PASS: quality choices open beside the bottom playback toolbar")
        edt { control.page.panel.value = MvPanel.Danmaku }; settle(1000)
        save("mv-0.8.9-danmaku-settings")
        check(nodes().none { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.contains("弹幕密度") } == true })
        check(nodes().none { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.contains("避开底部字幕") } == true })
        click("显示弹幕")
        await("danmaku master switch turns off") { !danmakuOptions(store).enabled }
        click("显示弹幕")
        await("danmaku master switch turns on") { danmakuOptions(store).enabled }
        check(nodes().none { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text in setOf("滚动弹幕 · 2", "逆向弹幕", "基础高级弹幕") } == true })
        click("滚动弹幕")
        await("rolling switch applies to all scrolling modes") { !danmakuOptions(store).modes.contains(1) }
        val areaControl = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("显示区域") == true && it.config.contains(SemanticsActions.SetProgress) }
        val areaBounds = areaControl.boundsInRoot
        event(PointerEventType.Press,Offset(areaBounds.left + areaBounds.width*.25f,areaBounds.center.y),true)
        event(PointerEventType.Move,Offset(areaBounds.left + areaBounds.width*.7f,areaBounds.center.y),true); draw()
        event(PointerEventType.Release,Offset(areaBounds.left + areaBounds.width*.7f,areaBounds.center.y),false)
        await("area slider follows a pointer drag and commits its chosen value") { kotlin.math.abs(danmakuOptions(store).area - .775f) < .04f }
        control.danmakuSetting("mode1","true")
        edt { control.page.panel.value = MvPanel.None }
        rootContent(); settle()
        await("folder MV queue includes unlinked videos") { control.screen.value.queue.size == 3 }
        await("same-title artist discrimination works through controller library matching") { control.screen.value.match.file == a && store.bound(songA) == null }
        check(store.bound(songB) == b && control.screen.value.queue.first { it.file == b }.displayTrack == songB)
        println("PASS: real Baby featuring title repairs the old automatic link and assigns the credited artist")
        val retainedMounts = originalMounts
        val retainedDisposals = originalDisposals
        control.open()
        await("real MV opens in the retained root") { control.screen.value.frames != null && control.page.motionReady }
        check(control.page.coversHost && originalMounts == retainedMounts && originalDisposals == retainedDisposals)
        println("PASS: fully expanded MV suppresses original page drawing while retaining its composition")
        await("initial playback acknowledges native play") { !control.screen.value.playback.paused }
        check(!playing.get())
        click("暂停"); check(control.screen.value.playback.paused)
        await("pause feedback reaches real libmpv") { control.screen.value.playback.paused }
        val playBounds = button("播放").boundsInRoot
        check(playBounds.height <= 44 && playBounds.width <= 44)
        check(described("播放进度").boundsInRoot.height <= 22)
        println("PASS: compact toolbar uses small transport controls and thin timeline")
        click("更多设置"); settle(); click("画面"); settle()
        for (aspect in MvAspect.entries) {
            click(aspect.label)
            await("libmpv accepts and persists aspect ${aspect.label}") { store.aspect() == aspect }
        }
        save("mv-0.7.2-aspects")
        click("返回更多设置"); settle(); click("收起设置"); settle()
        click("音量"); settle()
        val slider = nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("音量") == true && it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }.boundsInRoot
        val x=slider.center.x
        event(PointerEventType.Press, Offset(x,slider.center.y),true)
        for (fraction in listOf(.8f,.6f,.3f,.1f)) { event(PointerEventType.Move, Offset(x,slider.top+slider.height*fraction),true); draw() }
        check(control.screen.value.volume > 85) { "Volume did not follow upward drag" }
        for (fraction in listOf(.3f,.5f,.7f)) { event(PointerEventType.Move, Offset(x,slider.top+slider.height*fraction),true); draw() }
        check(control.screen.value.volume in 20.0..40.0) { "Volume did not follow reverse drag while held" }
        event(PointerEventType.Release,Offset(x,slider.top+slider.height*.7f),false)
        await("dragged volume reaches libmpv") { control.screen.value.playback.volume in 20.0..40.0 }
        save("mv-0.7.2-volume")
        click("静音 / 恢复音量"); check(control.screen.value.volume == 0.0)
        click("静音 / 恢复音量"); check(control.screen.value.volume in 20.0..40.0)
        println("PASS: volume follows continuous forward and reverse pointer drags and native backend without recoil")
        click("音量"); settle()
        click("下一首")
        await("MV Next follows folder order without changing the SPW song") { control.screen.value.video == b && control.screen.value.frames != null }
        check(current.get() == songA && resumes.get() == 0 && !playing.get())
        click("下一首")
        await("unlinked MV participates in its own playlist") { control.screen.value.video == c && control.screen.value.frames != null }
        click("上一首")
        await("MV Previous follows MV list") { control.screen.value.video == b && control.screen.value.frames != null }
        check(current.get() == songA && resumes.get() == 0)
        control.order("CircleEnd"); await("MV order persists independently") { store.playbackOrder() == "CircleEnd" }
        control.select(control.screen.value.queue.first { it.file == c })
        await("select last MV for EOF check") { control.screen.value.video == c && control.screen.value.frames != null }
        control.seek(11.8)
        await("sequential MV playback stops at its own list end") { control.screen.value.playback.paused && control.screen.value.playback.positionSeconds > 11.5 }
        check(current.get() == songA)
        control.order("Circle"); control.toggle()
        await("MV loop wraps without touching song order", 40000) { control.screen.value.video == a && control.screen.value.frames != null }
        println("PASS: MV playlist navigation and EOF order never invoke song Next or Previous")
        await("opening finishes before background gesture checks") { control.page.motionReady }
        await("looped player acknowledges native play before gestures") { !control.screen.value.playback.paused }
        control.toggle(); await("gesture fixture pauses the native player") { control.screen.value.playback.paused }; settle(500)
        click(Offset(500f,280f))
        await("background single click plays", 2500) { !control.screen.value.playback.paused }; settle(500)
        click(Offset(500f,280f))
        await("background single click pauses", 2500) { control.screen.value.playback.paused }; settle(500)
        val paused = control.screen.value.playback.paused
        click(Offset(500f,280f)); Thread.sleep(70); click(Offset(500f,280f)); settle(500)
        check(control.page.full.value && control.screen.value.playback.paused == paused)
        click(Offset(500f,280f)); Thread.sleep(70); click(Offset(500f,280f)); settle(500)
        check(!control.page.full.value && control.screen.value.playback.paused == paused)
        println("PASS: single-click play/pause and double-click fullscreen do not trigger each other")
        click("MV 播放列表"); settle(); save("mv-0.7.2-playlist")
        click("收起播放列表"); settle()
        val windowCount = java.awt.Window.getWindows().size
        val windowBounds = host.bounds
        val placement = hostState.placement
        click("更多设置"); settle(); click("MV 文件夹"); settle(); click("选择文件夹")
        await("in-app directory browser loads without a native dialog") { control.screen.value.directoryBrowser?.busy == false }
        check(java.awt.Window.getWindows().size == windowCount && host.bounds == windowBounds && hostState.placement == placement)
        val child = Files.createDirectory(folder.resolve("子目录"))
        control.browseDirectory(folder)
        await("directory navigation lists the new child") { control.screen.value.directoryBrowser?.folders?.contains(child) == true }
        click("打开文件夹 ${child.fileName}")
        await("directory pointer navigation opens child") { control.screen.value.directoryBrowser?.let { !it.busy && it.path == child } == true }
        click("上一级文件夹")
        await("directory Up returns to the configured folder") { control.screen.value.directoryBrowser?.let { !it.busy && it.path == folder } == true }
        save("mv-0.7.2-directory")
        click("取消"); settle()
        check(control.screen.value.directoryBrowser == null && store.folder() == folder && control.page.isOpen)
        control.chooseFolder()
        await("folder picker can reopen while video remains mounted") { control.screen.value.directoryBrowser?.busy == false && control.screen.value.frames != null }
        click("选择此文件夹")
        await("in-app folder confirmation updates the index") { control.screen.value.directoryBrowser == null && control.screen.value.videos.size == 3 }
        check(java.awt.Window.getWindows().size == windowCount && hostState.placement == placement && host.bounds == windowBounds)
        println("PASS: in-app folder navigation cancel and confirm preserve native window placement and mounted playback")
        click("返回更多设置"); settle(); click("收起设置"); settle()
        control.order("RepeatOne")
        edt { control.page.autoHide.value = true }
        if (control.screen.value.playback.paused) control.toggle()
        settle(3800)
        check(nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("更多设置") == true })
        event(PointerEventType.Move,Offset(510f,280f),false); settle(300)
        check(button("更多设置").boundsInRoot.height > 0)
        println("PASS: immersive controls disappear and pointer movement restores them")
        check(originalClicks == 0) { "MV input leaked to the retained song page" }
        click("全屏"); settle(80)
        check(hostState.placement == WindowPlacement.Fullscreen)
        click("返回歌曲")
        check(hostState.placement == WindowPlacement.Floating && control.page.isVisible) { "Return revealed the original page before restoring placement" }
        await("return restores the one original song handoff") { !control.page.isVisible && playing.get() && resumes.get() == 1 && position.get() == 3456L }
        check(!control.page.coversHost && originalMounts == retainedMounts && originalDisposals == retainedDisposals)
        println("PASS: return restores the retained page without remounting or losing its state")
        println("PASS: fullscreen return restores host placement under the opaque MV before the fold reveals the song page")
        println("PASS: complete root More, transport and Back remain clickable above original input shield")
        edt { control.screen.value = control.screen.value.copy(linkRequest = MvLinkRequest(songA,listOf(a,b))) }
        settle()
        val candidate = described("选择 MV ${b.fileName}")
        click(Offset(candidate.boundsInRoot.left+24,candidate.boundsInRoot.center.y)); settle()
        check(described("选择 MV ${b.fileName}").config[SemanticsProperties.Selected])
        save("mv-0.7.2-confirm")
        click("暂不链接"); settle()
        check(control.screen.value.linkRequest == null && playing.get())
        check(originalClicks == 0)
        println("PASS: version candidate and cancel accept real pointer clicks in complete root")
        store.bind(songB,b)
        control.selectVideo(false)
        await("manual binding opens the in-app folder browser") { control.screen.value.browser != null }
        val search = described("搜索 MV")
        check(search.config[SemanticsActions.SetText].action!!(AnnotatedString("Luis Fonsi")))
        settle()
        check(nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith("选择 MV ") } == true } == 1)
        click("选择 MV ${b.fileName}"); settle(); save("mv-0.7.2-browser")
        click("保存链接")
        await("browser selection reassigns the video and clears the old owner") { store.bound(songA) == b && store.bound(songB) == null && control.screen.value.browser == null }
        check(!control.page.isOpen && playing.get())
        check(!store.claimedElsewhere(songA,b) && store.claimedElsewhere(songB,b))
        println("PASS: in-app search, pointer selection and confirmation persist exclusive MV ownership")
        rootContent { Box(Modifier.fillMaxSize().background(Color(0xFF1D2024))) { MvLibraryPage(control) } }
        await("library rows display real cached cover images", 30000) { nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith("MV 封面 ") } == true } == 3 }
        settle(); save("mv-0.7.2-library")
        val coverBytes = kotlinx.coroutines.runBlocking { control.artwork.load(MvQueueEntry(a,songA)) }!!
        val cover = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(coverBytes))
        val pixel = java.awt.Color(cover.getRGB(100,100))
        check(cover.width <= 256 && cover.height <= 256 && kotlin.math.abs(pixel.red - 34) <= 4 && kotlin.math.abs(pixel.green - 102) <= 4 && kotlin.math.abs(pixel.blue - 170) <= 4)
        val frameBytes = kotlinx.coroutines.runBlocking { control.artwork.load(MvQueueEntry(c)) }!!
        check(javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(frameBytes)).width <= 256)
        println("PASS: artwork prefers embedded song covers, falls back to video frames and caches bounded thumbnails")
        rootContent { NativeUi.Light { Box(Modifier.fillMaxSize().background(Color(0xFFE6EDF0))) { MvLibraryPage(control) } } }
        await("light SPW library renders all cover rows",30000) { nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith("MV 封面 ") } == true } == 3 }
        save("mv-0.7.2-library-light")
        check(nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("搜索 MV") == true })
        click("平铺视图"); settle(700)
        await("grid layout persists") { store.libraryLayout() == "Grid" }
        await("grid visible cover images finish loading") { nodes().count { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith("MV 封面 ") } == true } == 3 }
        val covers = nodes(true).filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith("MV 封面 ") } == true }.map { it.boundsInRoot }
        save("mv-0.7.2-grid-light")
        check(covers.size == 3 && covers.all { kotlin.math.abs(it.width - it.height) < 1f && it.width > 160f }) { "Grid cover bounds $covers" }
        check(covers.zipWithNext().all { (left,right) -> right.left-left.right >= 35f && kotlin.math.abs(left.top-right.top) < 1f })
        save("mv-0.7.2-grid-light")
        val idle = edt { surface.makeImageSnapshot().use { it.encodeToData()!!.use { data -> data.bytes } } }
        event(PointerEventType.Move,covers[0].center + Offset(55f,-45f),false); settle(700)
        val hover = edt { surface.makeImageSnapshot().use { it.encodeToData()!!.use { data -> data.bytes } } }
        check(!idle.contentEquals(hover)) { "Foil cover did not react to pointer hover" }
        save("mv-0.7.2-grid-foil")
        click(covers[0].center); settle()
        await("one pointer click on a grid cover opens and plays MV") { control.page.isOpen && control.screen.value.frames != null && !control.screen.value.playback.paused }
        check(nodes().none { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "播放 MV" } == true })
        settle(800); click("返回歌曲")
        await("grid playback returns without losing the library") { !control.page.isOpen && control.screen.value.video == null }
        check(nodes().any { it.config.getOrNull(SemanticsProperties.Selected) == true })
        event(PointerEventType.Move,Offset(5f,5f),false); settle(1000)
        val settled = edt { surface.makeImageSnapshot().use { it.encodeToData()!!.use { data -> data.bytes } } }
        val timings = LongArray(40) { val start=System.nanoTime(); draw(); (System.nanoTime()-start)/1000 }
        val settledAgain = edt { surface.makeImageSnapshot().use { it.encodeToData()!!.use { data -> data.bytes } } }
        check(settled.contentEquals(settledAgain)) { "Idle cards kept animating after pointer exit" }
        println("FOIL_RENDER: raster 1000x700 idle p95_us=${timings.sorted()[37]}; no physical GPU claim")
        println("PASS: foil animation settles after pointer exit and idle frames remain pixel-stable")
        click("列表视图"); settle(600)
        await("list layout persists") { store.libraryLayout() == "List" }
        println("PASS: list and grid share pointer-reactive foil covers with spaced square thumbnails and persistent layout switching")
        click("搜索视频"); settle()
        val librarySearch = described("搜索 MV")
        librarySearch.config[SemanticsActions.SetText].action!!(AnnotatedString("Unlinked")); settle()
        check(described("选择 MV ${c.fileName}").boundsInRoot.height > 0)
        click("选择 MV ${c.fileName}"); settle()
        await("one pointer click on a list row opens and plays that MV") { control.page.isOpen && control.screen.value.video == c && control.screen.value.frames != null && !control.screen.value.playback.paused }
        settle(800); click("返回歌曲")
        await("list playback returns without a second play button") { !control.page.isOpen && control.screen.value.video == null }
        println("PASS: library browser lists and searches videos with pointer-selectable actions")
        }
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        val initial = com.xuncorp.voxzen.ui.navigation.ScreenRoute.Track
        val stack = androidx.navigation3.runtime.NavBackStack<androidx.navigation3.runtime.NavKey>(initial)
        val navigator = com.xuncorp.voxzen.ui.navigation.Navigator(initial, setOf(initial), stack,
            unsafe.allocateInstance(com.xuncorp.voxzen.ui.component.PlayerSheetState::class.java) as com.xuncorp.voxzen.ui.component.PlayerSheetState)
        val hooks = MvNavigationHooks(control) { true }
        val call = object : com.xuncorp.spw.workshop.api.hook.UiHookCall() {
            override val thisObject: Any? = null
            override val arguments: List<Any?> = emptyList()
            override fun getArgument(name: String): Any? = null
        }
        var sidebarRow: Any? = null
        val sidebarKeys = mutableListOf<Any?>()
        val scope = Proxy.newProxyInstance(LazyListScope::class.java.classLoader, arrayOf(LazyListScope::class.java)) { _, method, args ->
            when (method.name) {
                "item" -> { sidebarKeys += args[0]; sidebarRow = args[2] }
                "items" -> repeat(args[0] as Int) { index -> sidebarKeys += kotlin.jvm.functions.Function1::class.java.getMethod("invoke",Any::class.java).invoke(args[1],index) }
                else -> error("Unexpected sidebar scope method $method")
            }
            null
        } as LazyListScope
        val nativeItems = HostBridge.resolver.type("com.xuncorp.voxzen.ui.main.sidebar.SidebarViewModel").declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type) }
            .map { it.isAccessible = true; it.get(null) as List<*> }.single { it.size > 2 }
        check(nativeItems.size == 14 && HostBridge.resolver.type("com.xuncorp.voxzen.ui.main.sidebar.SidebarItem\$DividerItem").isInstance(nativeItems.first()))
        val nativeKey = HostBridge.resolver.type("com.xuncorp.voxzen.ui.main.sidebar.SidebarItem").declaredMethods.single { it.returnType == Any::class.java && it.parameterCount == 0 }
        val originalKeys = nativeItems.map { nativeKey.invoke(it) }
        val success = HostBridge.resolver.type("com.xuncorp.voxzen.ui.main.sidebar.SidebarUiState\$Success").constructors.single().newInstance(nativeItems)
        val navState = unsafe.allocateInstance(MvNavigationHooks.sidebar.parameterTypes[1])
        MvNavigationHooks.sidebar.invoke(null,success,navState,hooks.sidebarScope(scope))
        val expectedKeys = originalKeys.toMutableList().apply { add(indexOf("Track") + 1,"local.spw.mv.library") }
        check(sidebarKeys == expectedKeys) { "Wrong native sidebar order $sidebarKeys expected=$expectedKeys" }
        println("PASS: complete native sidebar defaults retain the leading divider and place MV immediately after Songs")
        println("PASS: actual native sidebar builder inserts MV between Songs and Genre while preserving native item keys")
        check(sidebarRow != null)
        val dummyItem = Proxy.newProxyInstance(LazyItemScope::class.java.classLoader, arrayOf(LazyItemScope::class.java)) { _, method, _ -> error("Unexpected LazyItemScope call $method") } as LazyItemScope
        val styleFacade = HostBridge.resolver.facade("SidebarItemLayout")
        @Suppress("UNCHECKED_CAST")
        val styleLocal = styleFacade.declaredMethods.single { it.parameterCount == 0 && it.returnType == ProvidableCompositionLocal::class.java }.invoke(null) as ProvidableCompositionLocal<Any>
        val styleMethod = HostBridge.resolver.function(styleFacade,"sidebarItemStyle") { it.parameterCount == 1 && it.parameterTypes[0] == Composer::class.java }
        edt { scene.setContent { NativeUi.Theme { CompositionLocalProvider(MvNavigationHooks.navigatorLocal provides navigator) {
            if (stack.lastOrNull() == MvNavigationHooks.route) hooks.navigationHook.Content(call)
            else {
                val style = HostBridge.invoke(styleMethod,null,currentComposer)
                CompositionLocalProvider(styleLocal provides requireNotNull(style)) {
                    Column(Modifier.width(300.dp)) {
                        HostBridge.invoke(sidebarRow!!.javaClass.getMethod("invoke",Any::class.java,Composer::class.java,Int::class.javaPrimitiveType),sidebarRow,dummyItem,currentComposer,0)
                    }
                }
            }
        } } } }
        settle(); save("mv-0.7.2-sidebar")
        click("MV"); settle()
        check(stack.last() == MvNavigationHooks.route && button("搜索视频").boundsInRoot.height > 0)
        sidebarPreference.set(false)
        await("configuration hides the native MV entry and leaves its library route without a restart") {
            !control.sidebarVisible.value && stack.toList() == listOf(initial) &&
                nodes().none { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "MV" } == true }
        }
        sidebarPreference.set(true)
        await("configuration restores the clickable native MV entry without a restart") {
            control.sidebarVisible.value && nodes().any { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "MV" } == true }
        }
        click("MV"); settle()
        check(stack.last() == MvNavigationHooks.route)
        hooks.close(); settle()
        check(stack.toList() == listOf(initial))
        println("PASS: native sidebar content hook and pointer navigation open the MV library and remove its route on disable")
        if ("--sidebar-only" !in args && "--artwork-only" !in args) println("PASS: redesigned MV backend and full-root pointer regression; physical monitor fullscreen and OS cursor are not tested")
    } finally {
        edt { control.page.dispose() }
        if ("--danmaku-benchmark" in args) {
            edt { host.isVisible = false; host.setContent { } }
            Thread.sleep(100)
        }
        control.close(); settle(300)
        edt { scene.close(); frame.close(); surface.close(); host.dispose() }
        endpoint.close(); assets.close()
    }
    System.exit(0)
}
