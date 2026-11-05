@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.spw.mv

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.sun.jna.*
import com.sun.jna.platform.win32.*
import kotlinx.coroutines.*
import local.spw.host.HostResolver
import java.awt.EventQueue
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** The outer SaltWindow validates caption geometry; testing WindowsSaltWindowFrame alone misses it. */
object NativeWindowProbe {
    @JvmStatic fun run(properties: Any, page: Any) {
        val resolver = HostResolver(WorkshopApi::class.java)
        val saltWindow = resolver.composable("SaltWindow", 20, kotlin.jvm.functions.Function0::class.java)
        val theme = resolver.composable("AppTheme", 5, null)
        val layerType = Class.forName("local.spw.mv.MvPageHook", true, page.javaClass.classLoader)
        val layer = layerType.constructors.single().newInstance(page, { true })
        val drawLayer = layerType.declaredMethods.single { it.name == "Layer" }
        val state = WindowState(size = DpSize(640.dp, 400.dp))
        val ready = CompletableFuture<ComposeWindow>()
        val error = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, failure ->
            failure.printStackTrace(); error.set(failure)
        })
        val application = scope.launchApplication {
            val body: @Composable FrameWindowScope.() -> Unit = {
                val original: @Composable () -> Unit = { Box(Modifier.fillMaxSize()) }
                drawLayer.invoke(layer, original, currentComposer, 0)
            }
            val init: (ComposeWindow) -> Unit = { ready.complete(it) }
            val key: (KeyEvent) -> Boolean = { false }
            val content: @Composable () -> Unit = {
                saltWindow.invoke(null, {}, state, true, "SPW native MV regression", null, WindowDecoration.SystemDefault,
                    false, true, true, true, false, properties, key, key, init, body, currentComposer, 0, 0, 0)
            }
            theme.invoke(null, false, content, currentComposer, 0, 1)
        }
        val window = ready.get(15, TimeUnit.SECONDS)
        fun onEdt(action: () -> Unit) = EventQueue.invokeAndWait(action)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!window.isShowing) {
                error.get()?.let { throw AssertionError("SaltWindow failed before showing", it) }
                check(System.nanoTime() < deadline) { "SaltWindow never became visible" }
                Thread.sleep(25)
            }
            onEdt { }
            val bounds = window.bounds
            val user = User32.INSTANCE
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            val procedure = user.GetWindowLongPtr(hwnd, -4).toLong()
            fun assertWindow() {
                error.get()?.let { throw AssertionError("SaltWindow composition failed", it) }
                onEdt { check(window.isDisplayable && window.isShowing && window.bounds == bounds) {
                    "MV host displayable=${window.isDisplayable}, showing=${window.isShowing}, bounds=${window.bounds}, expected=$bounds"
                } }
                check(user.IsWindowVisible(hwnd) && user.GetWindowLong(hwnd, -20) and 0x80 == 0) { "MV removed its host from the taskbar" }
                check(user.GetWindowLongPtr(hwnd, -4).toLong() == procedure) { "MV replaced native window callbacks" }
                Memory(128).use { text ->
                    check(user.SendMessage(hwnd, 13, WinDef.WPARAM(64), WinDef.LPARAM(Pointer.nativeValue(text))).toLong() > 0)
                    check(text.getWideString(0) == "SPW native MV regression")
                }
            }
            assertWindow()
            repeat(2) {
                onEdt { page.javaClass.getMethod("show").invoke(page) }
                Thread.sleep(800)
                System.gc(); assertWindow()
                check(page.javaClass.getMethod("isVisible").invoke(page) == true)
                onEdt { state.size = DpSize(900.dp, 540.dp) }
                val resizeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (window.bounds == bounds) {
                    check(System.nanoTime() < resizeDeadline) { "Native resize fixture never applied" }
                    Thread.sleep(10)
                }
                onEdt { page.javaClass.getMethod("hide").invoke(page) }
                val backDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (page.javaClass.getMethod("isVisible").invoke(page) == true) {
                    onEdt {
                        if (page.javaClass.getMethod("isOpen").invoke(page) == false)
                            check(window.bounds == bounds) { "Return fold exposed the retained page at the resized bounds" }
                    }
                    check(System.nanoTime() < backDeadline) { "Native return never completed" }
                    Thread.sleep(10)
                }
                println("PASS: native resized host restores original bounds before the return fold reveals the retained page")
                assertWindow()
            }
            println("PASS: actual outer SaltWindow survives repeated MV entry and return with package getter hooks and native taskbar presence")
        } finally {
            onEdt { page.javaClass.getMethod("dispose").invoke(page) }
            application.cancel(); scope.cancel()
        }
    }
}
