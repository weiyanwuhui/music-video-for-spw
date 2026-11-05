@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.spw.mv

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import dev.gaboron.spwmedia.compose.MediaSurface
import dev.gaboron.spwmedia.sdk.SharedBuffer
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.event.*
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.delay

fun displayTime(seconds: Double): String {
    val total = seconds.takeIf { it.isFinite() }?.toLong()?.coerceAtLeast(0) ?: 0
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
        else "%02d:%02d".format(total / 60, total % 60)
}

data class MvRevealShape(val rect: Rect, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/** This page lives above the retained SPW content; it never creates another native window. */
class MvPage(private val controller: MvController, private val hostWindow: () -> ComposeWindow?) {
    private var host: ComposeWindow? = null
    private var originalPlacement = WindowPlacement.Floating
    private var originalBounds = java.awt.Rectangle()
    private var beforeFullscreen = WindowPlacement.Floating
    private var floatingBounds = java.awt.Rectangle()
    private var windowState: WindowState? = null
    private var originalState: MvWindowSnapshot? = null
    private var originalViewport = Size.Zero
    var restoringForBack by mutableStateOf(false)
        private set
    private var restoreReady by mutableStateOf(false)
    private var placementTimer: javax.swing.Timer? = null
    private var hidden = CompletableFuture.completedFuture(Unit)
    private var keyboard: KeyEventDispatcher? = null
    private var keys: MvKeys? = null
    private var focusListener: WindowAdapter? = null
    private var resizeListener: ComponentAdapter? = null
    private var origin: Rect? = null
    private var visible by mutableStateOf(false)
    private var requested by mutableStateOf(false)
    private var activity by mutableLongStateOf(0)
    private val mounted = mutableMapOf<SharedBuffer, Int>()
    private val detached = mutableMapOf<SharedBuffer, CompletableFuture<Unit>>()
    var rootBounds by mutableStateOf(Rect.Zero)
    val panel = mutableStateOf(MvPanel.None)
    val full = mutableStateOf(false)
    val autoHide = mutableStateOf(controller.store.controlsAutoHide())
    val fillVideo = mutableStateOf(controller.store.videoFill())
    val aspect = mutableStateOf(controller.store.aspect())
    val hideDelay = mutableStateOf(controller.store.hiddenDelay())
    val doubleClickFull = mutableStateOf(controller.store.doubleClickFull())
    val isOpen get() = requested
    val isVisible get() = visible
    fun displayRefreshRate(): Int = runCatching { host?.graphicsConfiguration?.device?.displayMode?.refreshRate ?: 0 }.getOrDefault(0)
    var coversHost by mutableStateOf(false)
        private set
    val hasModal get() = controller.screen.value.let { it.login != null || it.textRequest != null || it.linkRequest != null || it.browser != null || it.directoryBrowser != null }
    var motionRunning = false
        private set
    var motionReady = false
        private set
    private var motionPrimed = false
    val ownsFocus get() = isOpen && KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow === host
    fun attachWindowState(state: WindowState) { windowState = state }
    fun awaitHidden(): CompletableFuture<Unit> = hidden
    @Composable fun LinkPrompt() { if (hasModal) NativeUi.AppTheme {
        if (controller.screen.value.login != null) MvLoginPrompt(controller)
        else if (controller.screen.value.directoryBrowser != null) MvDirectoryBrowser(controller)
        else if (controller.screen.value.textRequest != null) MvOnlinePrompt(controller)
        else if (controller.screen.value.browser != null) MvBrowser(controller) else MvLinkPrompt(controller)
    } }
    fun dismissLink(): Boolean {
        if (!hasModal) return false
        if (controller.screen.value.login != null) controller.cancelLogin()
        else if (controller.screen.value.directoryBrowser != null) controller.cancelDirectory() else controller.cancelLink()
        return true
    }

    fun show() {
        if (requested) return
        val view = hostWindow() ?: error("SPW 主窗口尚未就绪，请完整重启播放器")
        host = view
        originalPlacement = windowState?.placement ?: view.placement; originalBounds = view.bounds
        originalState = windowState?.let(::MvWindowSnapshot)
        originalViewport = rootBounds.size
        restoringForBack = false; restoreReady = false
        beforeFullscreen = if (originalPlacement == WindowPlacement.Fullscreen) WindowPlacement.Floating else originalPlacement
        floatingBounds = view.bounds
        full.value = (windowState?.placement ?: view.placement) == WindowPlacement.Fullscreen
        origin = HostBridge.consumeEntry() ?: HostBridge.entryBounds
        hidden = CompletableFuture(); motionReady = false; visible = true; requested = true; activity = System.nanoTime()
        val input = MvKeys(SpwShortcuts::bindings, ::action, controller::boost, ::fullscreen)
        keys = input
        val listener = object : WindowAdapter() {
            override fun windowLostFocus(event: WindowEvent) { input.cancel() }
            override fun windowClosing(event: WindowEvent) { controller.closeVideo() }
        }
        focusListener = listener
        view.addWindowFocusListener(listener); view.addWindowListener(listener)
        val resize = object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) { full.value = view.placement == WindowPlacement.Fullscreen }
        }
        resizeListener = resize; view.addComponentListener(resize)
        val dispatcher = KeyEventDispatcher { event ->
            if (!ownsFocus || hasModal || event.id !in setOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED)) false
            else input.key(event.keyCode, SpwShortcuts.mask(event), event.id == KeyEvent.KEY_PRESSED)
        }
        keyboard = dispatcher
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
    }
    fun action(name: String) {
        if (hasModal) { if (name == "BACK") dismissLink(); return }
        // Seeking and hold boosts must not flash the transport or timeline.
        if (name !in setOf("FORWARD_5S", "BACKWARD_5S")) activity = System.nanoTime()
        when (name) {
            "PLAY_PAUSE" -> controller.toggle()
            "PREVIOUS" -> controller.navigate(true)
            "NEXT" -> controller.navigate(false)
            "STOP" -> controller.stopVideo()
            "FORWARD_5S" -> controller.seekBy(5.0)
            "BACKWARD_5S" -> controller.seekBy(-5.0)
            "VOLUME_UP" -> controller.volume((controller.screen.value.volume + 10).coerceAtMost(100.0))
            "VOLUME_DOWN" -> controller.volume((controller.screen.value.volume - 10).coerceAtLeast(0.0))
            "BACK" -> if (panel.value != MvPanel.None) panel.value = MvPanel.None else if (full.value) fullscreen() else controller.closeVideo()
        }
    }
    fun togglePanel(value: MvPanel) {
        panel.value = if (panel.value == value || (value == MvPanel.More && panel.value.isSettings)) MvPanel.None else value
        if (panel.value == MvPanel.Playlist) controller.refreshPlaylist()
    }
    fun fullscreen() {
        val view = host ?: return
        windowState?.let { state ->
            val target = if (state.placement == WindowPlacement.Fullscreen) beforeFullscreen
                else WindowPlacement.Fullscreen.also { beforeFullscreen = state.placement }
            applyHostState(MvWindowSnapshot(target, state.position, state.size)) { }
            full.value = target == WindowPlacement.Fullscreen
            return
        }
        if (view.placement != WindowPlacement.Fullscreen) {
            beforeFullscreen = view.placement; floatingBounds = view.bounds
            view.placement = WindowPlacement.Fullscreen
        } else {
            view.placement = WindowPlacement.Floating
            if (beforeFullscreen == WindowPlacement.Maximized) view.placement = WindowPlacement.Maximized
            else view.bounds = floatingBounds
        }
        full.value = view.placement == WindowPlacement.Fullscreen
    }
    private fun releaseInput() {
        keys?.cancel(); keys = null
        keyboard?.let { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(it) }; keyboard = null
        host?.let { view ->
            focusListener?.let { view.removeWindowFocusListener(it); view.removeWindowListener(it) }
            resizeListener?.let { view.removeComponentListener(it) }
        }
        focusListener = null; resizeListener = null
    }
    fun hide() {
        if (!requested || restoringForBack) return
        HostBridge.entryBounds?.let { origin = it }
        motionReady = false; panel.value = MvPanel.None; releaseInput()
        val saved = originalState
        if (saved != null && windowState?.let { MvWindowSnapshot(it) != saved } == true) {
            // Keep the MV opaque while the host restores, then fold inside the original viewport.
            restoringForBack = true
            applyHostState(saved) {
                var stable = 0
                var previousLayout = Size.Zero
                val maximumHeight = if (originalBounds.width > 0)
                    maxOf(originalViewport.height, originalBounds.height * originalViewport.width / originalBounds.width) else originalViewport.height
                val deadline = System.nanoTime() + 750_000_000L
                placementTimer = javax.swing.Timer(16) {
                    val boundsReady = saved.placement != WindowPlacement.Floating || host?.bounds == originalBounds
                    // The caption stays hidden until the fold ends, so the restored content can be one caption taller.
                    val layoutReady = originalViewport == Size.Zero ||
                        (kotlin.math.abs(rootBounds.width - originalViewport.width) <= 1f && rootBounds.height <= maximumHeight + 1f && rootBounds.size == previousLayout)
                    previousLayout = rootBounds.size
                    stable = if (host?.placement == saved.placement && boundsReady && layoutReady) stable + 1 else 0
                    if (stable >= 2 || System.nanoTime() >= deadline) {
                        placementTimer?.stop(); placementTimer = null
                        full.value = saved.placement == WindowPlacement.Fullscreen
                        HostBridge.entryBounds?.let { origin = it }
                        restoreReady = true
                    }
                }.apply { start() }
            }
        } else requested = false
    }
    fun autoHide(value: Boolean) { autoHide.value = value; controller.dispatch { controller.store.setControlsAutoHide(value) } }
    fun fillVideo(value: Boolean) = aspect(if (value) MvAspect.Fill else MvAspect.Fit)
    fun aspect(value: MvAspect) = controller.aspect(value)
    fun hideDelay(value: Int) { hideDelay.value = value; controller.dispatch { controller.store.setHiddenDelay(value) } }
    fun doubleClickFull(value: Boolean) { doubleClickFull.value = value; controller.dispatch { controller.store.setDoubleClickFull(value) } }
    private fun applyHostState(saved: MvWindowSnapshot, done: () -> Unit) {
        placementTimer?.stop(); placementTimer = null
        val state = windowState ?: return done()
        val view = host
        // This host's Maximized setter does not leave native fullscreen; let Floating apply before restoring it.
        if (saved.placement == WindowPlacement.Maximized && view?.placement == WindowPlacement.Fullscreen) {
            state.placement = WindowPlacement.Floating
            val deadline = System.nanoTime() + 1_500_000_000L
            placementTimer = javax.swing.Timer(16) {
                if (view.placement != WindowPlacement.Fullscreen) {
                    placementTimer?.stop(); placementTimer = null; saved.restore(state); done()
                } else if (System.nanoTime() >= deadline) {
                    placementTimer?.stop(); placementTimer = null; done()
                    controller.dispatch { error("SPW 窗口退出全屏超时，请重新切换全屏") }
                }
            }.apply { start() }
        } else { saved.restore(state); done() }
    }
    private fun finishBack() {
        coversHost = false
        if (requested || !visible) return
        visible = false
        val saved = originalState
        if (saved != null) {
            applyHostState(saved) { host = null; originalState = null; hidden.complete(Unit) }
            return
        }
        else host?.let { view ->
            if (view.placement != originalPlacement) {
                view.placement = WindowPlacement.Floating
                if (originalPlacement == WindowPlacement.Fullscreen) view.placement = WindowPlacement.Fullscreen
                else if (originalPlacement == WindowPlacement.Maximized) view.placement = WindowPlacement.Maximized
                else view.bounds = originalBounds
            }
        }
        host = null
        originalState = null
        hidden.complete(Unit)
    }
    fun dispose() {
        placementTimer?.stop(); placementTimer = null; restoringForBack = false; restoreReady = false
        requested = false; motionReady = false; panel.value = MvPanel.None; releaseInput(); finishBack()
    }
    fun awaitDetached(buffer: SharedBuffer?): CompletableFuture<Unit> =
        if (buffer == null || (mounted[buffer] ?: 0) == 0) CompletableFuture.completedFuture(Unit)
        else detached.getOrPut(buffer) { CompletableFuture() }

    @Composable fun Overlay() {
        val transition = remember { Animatable(0f) }
        val genie = remember { MvGenie() }
        DisposableEffect(genie) { onDispose { genie.close() } }
        val open = requested
        LaunchedEffect(restoreReady) {
            if (restoreReady) {
                // Submit the restored-size shader/layer before folding; resize allocation must stay out of the visible tween.
                withFrameNanos { }; withFrameNanos { }
                restoringForBack = false; restoreReady = false; requested = false
            }
        }
        LaunchedEffect(open) {
            coversHost = false
            if (open && !motionPrimed) {
                // Submit the first real page/filter before moving it; cold glyph and GPU work must not interrupt the tween.
                transition.snapTo(.001f)
                withFrameNanos { }; withFrameNanos { }
                motionPrimed = true
            }
            if (open || transition.value > 0f) {
                motionRunning = true
                try { transition.animateTo(if (open) 1f else 0f, tween(if (open) 560 else 460, easing = CubicBezierEasing(.3f, .0f, .18f, 1f))) }
                finally { motionRunning = false }
            }
            motionReady = open
            coversHost = open
            if (!open) finishBack()
        }
        // Premeasure the retained controls while clipped out, so first-click font/layout work precedes the motion.
        Box(Modifier.fillMaxSize().semantics { if (!visible) invisibleToUser() }.graphicsLayer {
            shape = MvRevealShape(if (visible) Rect(Offset.Zero, size) else Rect.Zero, 0f); clip = true
        }.then(if (visible) Modifier.pointerInput(Unit) {
            awaitPointerEventScope { while (true) {
                val first = awaitPointerEvent(PointerEventPass.Initial)
                if (transition.value < .999f || !requested || restoringForBack) first.changes.forEach { it.consume() }
            } }
        } else Modifier)) {
            PlayerCanvas(Modifier.fillMaxSize()) { drawRect(Color.Black, alpha = if (restoringForBack) 1f else .22f * transition.value) }
            Box(Modifier.fillMaxSize().then(genie.modifier({ origin?.translate(-rootBounds.topLeft) ?: Rect(rootBounds.width / 2 - 24f, rootBounds.height - 60f, rootBounds.width / 2 + 24f, rootBounds.height - 20f) }, { if (restoringForBack) .999f else transition.value }))
                .background(Color.Black)) {
                NativeUi.Dark {
                    Content(controller.screen.value, showVideo = visible)
                }
            }
        }
    }

    @Composable fun Content(state: MvScreenState, modifier: Modifier = Modifier, showVideo: Boolean = true) {
        var lastMotion by remember { mutableLongStateOf(System.nanoTime()) }
        var controls by remember { mutableStateOf(true) }
        LaunchedEffect(lastMotion, activity, state.playback.paused, panel.value, state.busy, autoHide.value, hideDelay.value) {
            controls = true
            if (!state.playback.paused && !state.busy && panel.value == MvPanel.None && autoHide.value) { delay(hideDelay.value * 1000L); controls = false }
        }
        val opacity = animateFloatAsState(if (controls) 1f else 0f, tween(200))
        val showing by remember { derivedStateOf { controls || opacity.value > 0f || panel.value != MvPanel.None } }
        BoxWithConstraints(modifier.fillMaxSize().onPointerEvent(PointerEventType.Move) { lastMotion = System.nanoTime() }
            .onPointerEvent(PointerEventType.Press) { lastMotion = System.nanoTime() }
            .pointerHoverIcon(if (showing || !isOpen) PointerIcon.Default else hiddenPointer), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().semantics { contentDescription = "视频画面"; onClick { controller.toggle(); true } }
                .pointerInput(doubleClickFull.value) {
                    NativeUi.taps(this, if (doubleClickFull.value) ({ fullscreen() }) else null) { controller.toggle() }
                })
            val width = if (aspect.value == MvAspect.Stretch) maxWidth
                else if (aspect.value == MvAspect.Fill) maxOf(maxWidth, maxHeight * (16f / 9f)) else minOf(maxWidth, maxHeight * (16f / 9f))
            if (showVideo) state.frames?.let { frames ->
                DisposableEffect(frames) {
                    mounted[frames] = (mounted[frames] ?: 0) + 1
                    onDispose {
                        val remaining = (mounted[frames] ?: 1) - 1
                        if (remaining == 0) { mounted.remove(frames); detached.remove(frames)?.complete(Unit) }
                        else mounted[frames] = remaining
                    }
                }
                val reveal = remember(frames) { Animatable(0f) }
                LaunchedEffect(frames) {
                    withFrameNanos { }; withFrameNanos { }
                    reveal.animateTo(1f, tween(160))
                }
                Box(Modifier.size(width, if (aspect.value == MvAspect.Stretch) maxHeight else width * (9f / 16f))) {
                    MediaSurface(frames, Modifier.fillMaxSize())
                    PlayerCanvas(Modifier.fillMaxSize()) { if (reveal.value < 1f) drawRect(Color.Black, alpha = 1f - reveal.value) }
                }
            }
            MvDanmaku(state, controller)
            if (state.busy && state.frames == null) PlayerText("正在打开 MV…", color = Color.White.copy(alpha = .6f))
            if (showing) Box(Modifier.fillMaxSize().graphicsLayer { alpha = opacity.value }) {
                MvPlaybackControls(this@MvPage, controller, state, Modifier.align(Alignment.BottomCenter))
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(64.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent)))
                    .padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerButton("返回歌曲", MvIcon.Back, click = controller::closeVideo)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val entry = state.queue.firstOrNull { it.file == state.video }
                        PlayerText(entry?.title ?: state.track?.title.orEmpty(), size = 15, bold = true)
                        PlayerText(entry?.displayTrack?.artist ?: state.track?.artist.orEmpty(), size = 11, color = Color.White.copy(alpha = .5f))
                    }
                    PlayerButton("更多设置", MvIcon.More, selected = panel.value.isSettings) { togglePanel(MvPanel.More) }
                }
            }
            MvPanels(this@MvPage, controller, state)
            state.boost?.let { speed ->
                Box(Modifier.align(Alignment.TopCenter).padding(top = 90.dp).background(Color(0xD91C1C1F), playerShape(24.dp)).padding(horizontal = 20.dp, vertical = 10.dp)) {
                    PlayerText("${speed.toInt()}×  正在加速", size = 12, bold = true)
                }
            }
        }
    }
    companion object {
        private val hiddenPointer by lazy { runCatching {
            PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(
                java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(), "MV hidden"))
        }.getOrDefault(PointerIcon.Default) }
    }
}
