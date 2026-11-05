@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
@file:OptIn(androidx.compose.runtime.InternalComposeApi::class, com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.mv

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.*
import local.spw.host.HostResolver
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

object HostBridge {
    val resolver = HostResolver(WorkshopApi::class.java)
    fun invoke(method: Method, receiver: Any?, vararg args: Any?): Any? = try { method.invoke(receiver, *args) }
        catch (error: InvocationTargetException) { throw error.targetException }
    private val controller = resolver.type("com.xuncorp.voxzen.service.PlaybackController")
    private val service = controller.getDeclaredField("service").apply { isAccessible = true }
    private val serviceType = resolver.type("com.xuncorp.voxzen.service.PlaybackService")
    private val getPlayer = serviceType.getMethod("getPlayer")
    private val playerType = getPlayer.returnType
    private val clocks = resolver.calls(serviceType.superclass.declaredMethods.single { it.name == "updateProgress\$default" }) {
        it.declaringClass == playerType && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType
    }.also { check(it.size == 2 && it[0] != it[1]) { "SPW 播放时钟不兼容" } }
    private val playing = resolver.calls(resolver.function(controller, "playOrPause")) {
        it.declaringClass == playerType && it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
    }.single()
    fun clock(): Pair<Long, Boolean> {
        val player = getPlayer.invoke(service.get(null))
        return (clocks[0].invoke(player) as Long) to (playing.invoke(player) as Boolean)
    }
    private var mainWindow: java.lang.ref.WeakReference<androidx.compose.ui.awt.ComposeWindow>? = null
    fun captureHost(window: java.awt.Window? = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow) {
        if (window is androidx.compose.ui.awt.ComposeWindow && window.name != "spw-mv-player") mainWindow = java.lang.ref.WeakReference(window)
    }
    fun hostWindow(): androidx.compose.ui.awt.ComposeWindow? = mainWindow?.get()?.takeIf { it.isShowing } ?: java.awt.Window.getWindows()
        .filterIsInstance<androidx.compose.ui.awt.ComposeWindow>()
        .filter { it.isShowing && it.name != "spw-mv-player" }
        .maxByOrNull { it.width.toLong() * it.height }
    fun hostFullscreen() = hostWindow()?.placement == androidx.compose.ui.window.WindowPlacement.Fullscreen
    private var entry: Pair<Long, Rect>? = null
    var entryBounds: Rect? = null
        private set
    fun layoutEntry(rect: Rect) { entryBounds = rect }
    fun captureEntry(rect: Rect) { entry = System.nanoTime() to rect }
    fun consumeEntry(): Rect? = entry.also { entry = null }?.takeIf { System.nanoTime() - it.first < 15_000_000_000L }?.second
    fun validate() { check(clocks.size == 2); NativeUi.validate(); MvEntryHook.original; MvPageHook.original; MvHostWindow.captionOverrides }
}

class MvEntryHook(private val active: () -> Boolean, private val open: () -> Unit) : ComposableHook() {
    companion object {
        val original = HostBridge.resolver.composable("PlatformMiniPlayerTrailingContent", 5, null)
        val caller = HostBridge.resolver.callerIn(original, HostBridge.resolver.facade("MiniPlayer"))
        val parameters = original.parameterTypes.takeWhile { it != Composer::class.java }.map { it.name }
    }
    @Composable override fun Content(call: UiHookCall) {
        val alpha = call.arguments[0] as () -> Float
        val modifier = call.arguments[1] as? Modifier ?: Modifier
        var rect by remember { mutableStateOf(Rect.Zero) }
        // MiniPlayerMenu defaults its viewModel, so SPW skips replacements at that inner call.
        Row(modifier, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (active()) Box(Modifier.graphicsLayer { this.alpha = alpha() }.onGloballyPositioned { rect = it.boundsInRoot(); HostBridge.layoutEntry(rect) }) {
                NativeUi.MvButton { if (active()) { HostBridge.captureHost(); HostBridge.captureEntry(rect); open() } }
            }
            HostBridge.invoke(original, call.thisObject, alpha, Modifier.weight(1f), currentComposer, 0, 0)
        }
    }
}

/** Keep navigation and the MV in the same root, renderer, focus chain and native window. */
class MvPageHook(private val page: MvPage, private val active: () -> Boolean) : ComposableHook() {
    companion object {
        val original = HostBridge.resolver.composable("MainWindowContent", 4, Boolean::class.javaPrimitiveType)
        val caller = HostBridge.resolver.caller(original)
        val parameters = listOf("boolean")
    }
    @Composable override fun Content(call: UiHookCall) {
        Layer { HostBridge.invoke(original, call.thisObject, call.arguments[0], currentComposer, 0, 0) }
    }
    @Composable fun Layer(content: @Composable () -> Unit) {
        val windowState = MvHostWindow.windowLocal.current
        SideEffect { page.attachWindowState(windowState) }
        val shader = remember { MvGenie() }
        var prewarming by remember { mutableStateOf(true) }
        DisposableEffect(shader) { onDispose { shader.close() } }
        LaunchedEffect(Unit) {
            repeat(3) { withFrameNanos { } }
            prewarming = false
        }
        // Render the identity pass before the entry is used, so its GPU program is ready for the first morph.
        Box(Modifier.fillMaxSize().onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.Escape && page.dismissLink() }
            .onGloballyPositioned { page.rootBounds = it.boundsInRoot() }
            .then(if (prewarming) shader.modifier({ Rect.Zero }, { 1f }, prewarm = true) else Modifier)) {
            // Retained-page overlays (including immersive input shields) must stay below the MV page.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (page.coversHost) 0f else 1f }, propagateMinConstraints = true) { content() }
            if (active()) NativeUi.PopupContext {
                page.Overlay()
                page.LinkPrompt()
            }
        }
    }
}

/** The same semantic control bridge used by the existing SPW plugins, kept to this screen's controls. */
object NativeUi {
    private val resolver = HostBridge.resolver
    private val text = resolver.composable("com.moriafly.salt.ui.Text", 21, String::class.java)
    @Suppress("UNCHECKED_CAST")
    private val textStyleLocal = resolver.calls(text) {
        it.parameterCount == 0 && it.returnType == ProvidableCompositionLocal::class.java && it.genericReturnType.typeName.contains("TextStyle")
    }.distinct().single().let { HostBridge.invoke(it, null) as ProvidableCompositionLocal<androidx.compose.ui.text.TextStyle> }
    private val theme = resolver.composable("AppTheme", 5, null)
    private val darkTheme = resolver.composable("ChangeSaltThemeIsDark", 4, Boolean::class.javaPrimitiveType)
    private val themeColors = resolver.getter(resolver.type("com.moriafly.salt.ui.SaltTheme"), "colors")
    private val colorFields = listOf("text", "subText", "background", "subBackground", "stroke").map { resolver.getter(resolver.type("com.moriafly.salt.ui.SaltColors"), it) }
    data class Palette(val text: Color, val muted: Color, val surface: Color, val stroke: Color, val background: Color)
    @Composable fun palette(): Palette {
        val colors = HostBridge.invoke(themeColors, null, currentComposer)!!
        val values = colorFields.map { Color((it.invoke(colors) as Long).toULong()) }
        return Palette(values[0], values[1], values[3].compositeOver(values[2].copy(alpha = 1f)), values[4], values[2].copy(alpha = 1f))
    }
    private val captionButton = resolver.calls(resolver.composable("MusicVideoTitleBarControls", 9, null)) {
        it.parameterCount == 9 && it.parameterTypes.first() == kotlin.jvm.functions.Function0::class.java
    }.distinct().single()
    private val slider = resolver.composable("ItemSlider", 17, Float::class.javaPrimitiveType)
    private val textField = Class.forName("androidx.compose.foundation.text.BasicTextFieldKt").declaredMethods.single {
        it.name == "BasicTextField" && it.parameterCount == 20 && it.parameterTypes[0] == String::class.java
    }
    private val focusable = Class.forName("androidx.compose.foundation.FocusableKt").declaredMethods.single { it.name == "focusable" && it.parameterCount == 3 }
    private val taps = Class.forName("androidx.compose.foundation.gestures.TapGestureDetectorKt").declaredMethods.single { it.name == "detectTapGestures\$default" }
    private val button = resolver.composable("Button", 12, kotlin.jvm.functions.Function0::class.java)
    private val appearances = button.parameterTypes[4].enumConstants
    private val decision = resolver.composable("BlurYesNoDialog", 11, kotlin.jvm.functions.Function0::class.java)
    private val checkItem = resolver.composable("ItemCheck", 9, Boolean::class.javaPrimitiveType)
    private val item = resolver.composable("Item", 17, kotlin.jvm.functions.Function0::class.java)
    private val menuItem = resolver.composable("MenuFlyoutItemCompat", 7, String::class.java)
    private val background = Class.forName("androidx.compose.foundation.BackgroundKt").declaredMethods.single { it.name == "background-bw27NRU\$default" }
    fun validate() { check(text.parameterCount == 21 && captionButton.parameterCount == 9 && decision.parameterCount == 11 && checkItem.parameterCount == 9 && menuItem.parameterCount == 7 && textField.parameterCount == 20) }
    private val click = Class.forName("androidx.compose.foundation.ClickableKt").declaredMethods.single { it.name == "clickable-XHw0xAI" }
    private val rounded = Class.forName("androidx.compose.foundation.shape.RoundedCornerShapeKt").declaredMethods.single { it.name == "RoundedCornerShape-0680j_4" }
    private val lazyColumn = Class.forName("androidx.compose.foundation.lazy.LazyDslKt").methods.single { it.name == "LazyColumn" && it.parameterCount == 13 }
    private val image = Class.forName("androidx.compose.foundation.ImageKt").declaredMethods.single { it.name == "Image" && it.parameterCount == 10 && it.parameterTypes[0] == androidx.compose.ui.graphics.ImageBitmap::class.java }
    @Composable fun Image(bitmap: androidx.compose.ui.graphics.ImageBitmap, description: String, modifier: Modifier) {
        draw(image, currentComposer, 96, 0 to bitmap, 1 to description, 2 to modifier, 3 to androidx.compose.ui.Alignment.Center, 4 to androidx.compose.ui.layout.ContentScale.Crop)
    }
    private fun draw(method: Method, composer: Composer, defaults: Int, vararg values: Pair<Int, Any?>) {
        val args: Array<Any?> = Array(method.parameterCount) { when (method.parameterTypes[it]) {
            Int::class.javaPrimitiveType -> 0; Long::class.javaPrimitiveType -> 0L; Float::class.javaPrimitiveType -> 0f
            Boolean::class.javaPrimitiveType -> false; else -> null
        } }
        args[method.parameterTypes.indexOf(Composer::class.java)] = composer
        args[args.lastIndex] = defaults
        values.forEach { (index, value) -> args[index] = value }
        HostBridge.invoke(method, null, *args)
    }
    @Composable fun PopupContext(content: @Composable () -> Unit) {
        val haze = remember {
            resolver.facade("BlurYesNoDialog", "LocalWallpaperHazeState", "LocalPopupHazeState").declaredFields
                .filter { it.type == ProvidableCompositionLocal::class.java }.also { check(it.size == 2) }.map {
                    @Suppress("UNCHECKED_CAST")
                    (it.apply { isAccessible = true }.get(null) as ProvidableCompositionLocal<Any>).providesDefault(dev.chrisbanes.haze.HazeState())
                }.toTypedArray()
        }
        // The root Hook is outside SPW's inner providers; keep any host values and fill only missing ones.
        CompositionLocalProvider(*haze, content = content)
    }
    @Composable fun Theme(content: @Composable () -> Unit) { PopupContext { AppTheme(content) } }
    @Composable fun AppTheme(content: @Composable () -> Unit) { draw(theme, currentComposer, 1, 1 to content) }
    @Composable fun Dark(content: @Composable () -> Unit) { HostBridge.invoke(darkTheme, null, true, content, currentComposer, 0) }
    @Composable fun Light(content: @Composable () -> Unit) { HostBridge.invoke(darkTheme, null, false, content, currentComposer, 0) }
    @Composable fun Button(label: String, modifier: Modifier = Modifier, quiet: Boolean = false, enabled: Boolean = true, click: () -> Unit) {
        val appearance = appearances.single { (it as Enum<*>).name == if (quiet) "Plain" else "Subtle" }
        draw(button, currentComposer, 480, 0 to click, 1 to label, 2 to modifier, 3 to enabled, 4 to appearance)
    }
    @Composable fun Dialog(title: String, description: String, dismiss: () -> Unit, confirm: () -> Unit, content: @Composable () -> Unit) {
        draw(decision, currentComposer, 16, 0 to dismiss, 1 to confirm, 2 to title, 3 to description,
            5 to content, 6 to "暂不链接", 7 to "链接并播放")
    }
    @Composable fun Check(label: String, detail: String, selected: Boolean, change: (Boolean) -> Unit) {
        draw(checkItem, currentComposer, 0, 0 to selected, 1 to change, 2 to label, 3 to Modifier, 4 to true, 5 to detail)
    }
    @Composable fun Item(label: String, detail: String, modifier: Modifier, click: () -> Unit) {
        draw(item, currentComposer, 7920, 0 to click, 1 to label, 2 to modifier, 3 to true, 8 to detail)
    }
    @Composable fun MenuLabel(label: String, modifier: Modifier = Modifier, icon: @Composable () -> Unit) {
        draw(menuItem, currentComposer, 0, 0 to label, 1 to modifier, 2 to icon, 3 to true)
    }
    @Composable fun IconControl(label: String, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit, click: () -> Unit) {
        draw(captionButton, currentComposer, 16, 0 to click, 1 to label, 2 to modifier.semantics { contentDescription = label }, 3 to true, 5 to content)
    }
    @Composable fun SearchInput(value: String, change: (String) -> Unit, modifier: Modifier, style: androidx.compose.ui.text.TextStyle) {
        draw(textField, currentComposer, 65535 and (1 or 2 or 4 or 32 or 256 or 16384).inv(),
            0 to value, 1 to change, 2 to modifier, 5 to textStyleLocal.current.merge(style), 8 to true, 14 to androidx.compose.ui.graphics.SolidColor(palette().text))
    }
    fun focusable(modifier: Modifier) = HostBridge.invoke(focusable, null, modifier, true, null) as Modifier
    suspend fun taps(scope: androidx.compose.ui.input.pointer.PointerInputScope, doubleTap: ((androidx.compose.ui.geometry.Offset) -> Unit)?, tap: (androidx.compose.ui.geometry.Offset) -> Unit): Unit =
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
            HostBridge.invoke(taps, null, scope, doubleTap, null, null, tap, continuation, 6, null)
        }
    private val sidebarLayout by lazy { resolver.composable("SidebarItemLayout", 11, String::class.java) }
    @Composable fun SidebarMv(selected: Boolean, click: () -> Unit) {
        val icon: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("📺", size = 20, system = true) }
        }
        draw(sidebarLayout, currentComposer, 108, 0 to "MV", 1 to selected, 4 to Modifier.clickable(onClick = click), 7 to icon)
    }
    @Composable fun MvButton(click: () -> Unit) { PopupContext {
        val colors = palette()
        IconControl("播放 MV", Modifier.size(36.dp), {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { PlayerIcon(MvIcon.Video, Modifier.size(24.dp), colors.text) }
        }, click)
    } }
    @Composable fun Control(label: String, glyph: String, click: () -> Unit) {
        val body: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
            androidx.compose.foundation.layout.Box(Modifier.size(36.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(glyph, size = 14, bold = true) }
        }
        draw(captionButton, currentComposer, 16, 0 to click, 1 to label, 2 to Modifier, 3 to true, 5 to body)
    }
    @Composable fun Text(value: String, modifier: Modifier = Modifier, size: Int = 14, color: Color? = null, bold: Boolean = false,
        system: Boolean = false, singleLine: Boolean = false) {
        var defaults = 131068 and 8.inv()
        val values = mutableListOf<Pair<Int, Any?>>(0 to value, 1 to modifier, 3 to size.sp.packedValue.toLong())
        if (color != null) { defaults = defaults and 4.inv(); values += 2 to color.value.toLong() }
        if (bold) { defaults = defaults and 32.inv(); values += 5 to FontWeight.SemiBold }
        if (system) { defaults = defaults and 64.inv(); values += 6 to androidx.compose.ui.text.font.FontFamily.SansSerif }
        if (singleLine) { defaults = defaults and (2048 or 4096 or 8192).inv(); values += 11 to 2; values += 12 to false; values += 13 to 1 }
        draw(text, currentComposer, defaults, *values.toTypedArray())
    }
    fun clickable(modifier: Modifier, role: androidx.compose.ui.semantics.Role, action: () -> Unit) = HostBridge.invoke(click, null, modifier, true, null, role, action) as Modifier
    fun rounded(radius: androidx.compose.ui.unit.Dp) = HostBridge.invoke(rounded, null, radius.value) as androidx.compose.ui.graphics.Shape
    @Composable fun LazyRows(modifier: Modifier, count: Int, row: @Composable (Int) -> Unit) {
        val state = remember { androidx.compose.foundation.lazy.LazyListState(0, 0) }
        val body: @Composable androidx.compose.foundation.lazy.LazyItemScope.(Int) -> Unit = { row(it) }
        val build: (androidx.compose.foundation.lazy.LazyListScope) -> Unit = { scope ->
            androidx.compose.foundation.lazy.LazyListScope::class.java.methods.single { it.name == "items" && it.parameterCount == 4 }
                .invoke(scope, count, null, { _: Int -> null }, body)
        }
        draw(lazyColumn, currentComposer, 508, 0 to modifier, 1 to state, 9 to build)
    }
    @Composable fun Slider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true,
        finished: () -> Unit = {}, change: (Float) -> Unit) {
        draw(slider, currentComposer, 4336, 0 to value.coerceIn(range), 1 to change, 2 to label, 3 to Modifier,
            8 to enabled, 9 to range, 10 to 0, 11 to finished)
    }
    fun background(modifier: Modifier, color: Color) = HostBridge.invoke(background, null, modifier, color.value.toLong(), null, 2, null) as Modifier
}
