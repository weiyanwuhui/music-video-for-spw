@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.mv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.xuncorp.spw.workshop.api.hook.*
import com.xuncorp.voxzen.ui.navigation.Navigator
import com.xuncorp.voxzen.ui.navigation.ScreenRoute
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy

/** Reuse SPW's serializable route and sidebar layout; do not introduce another window or navigation stack. */
class MvNavigationHooks(private val controller: MvController, private val active: () -> Boolean) : AutoCloseable {
    companion object {
        val route = ScreenRoute.MusicVideos("local.spw.mv.library")
        val sidebar = HostBridge.resolver.facade("Sidebar").declaredMethods.single {
            it.parameterCount == 3 && it.parameterTypes.last() == LazyListScope::class.java
        }.apply { isAccessible = true }
        val sidebarParameters = sidebar.parameterTypes.map { it.name }
        val navigation = HostBridge.resolver.composable("AppNavigation", 2, Composer::class.java)
        val navigationCaller = HostBridge.resolver.caller(navigation)
        @Suppress("UNCHECKED_CAST")
        val navigatorLocal = Class.forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt").getMethod("getLocalNavigator").invoke(null) as ProvidableCompositionLocal<Navigator>
    }
    private var hostNavigator: WeakReference<Navigator>? = null
    val sidebarHook = object : MethodHook() {
        override fun before(call: MethodHookParam) {
            if (active()) {
                call.args[2] = sidebarScope(call.args[2] as LazyListScope)
                call.setResult(call.invokeOriginal())
            }
        }
    }
    fun sidebarScope(scope: LazyListScope): LazyListScope {
        var inserted = false
        return Proxy.newProxyInstance(LazyListScope::class.java.classLoader, arrayOf(LazyListScope::class.java)) { _, method, arguments ->
            val args = arguments ?: emptyArray()
            val key = args.getOrNull(1)
            val invokeKey = kotlin.jvm.functions.Function1::class.java.getMethod("invoke", Any::class.java)
            // The host starts with an invisible divider; locate Songs instead of assuming it is item zero.
            val split = if (method.name == "items" && !inserted && key != null)
                (0 until args[0] as Int).firstOrNull { invokeKey.invoke(key, it) == "Track" }?.plus(1) else null
            if (split != null) {
                inserted = true
                val count = args[0] as Int
                method.invoke(scope, split, key, args[2], args[3])
                appendSidebar(scope)
                if (count > split) {
                    val shiftedKey: (Int) -> Any? = { invokeKey.invoke(key, it + split) }
                    val shiftedType: (Int) -> Any? = { args[2]?.let { factory -> invokeKey.invoke(factory, it + split) } }
                    val body = requireNotNull(args[3])
                    val invokeBody = body.javaClass.getMethod("invoke", Any::class.java, Any::class.java, Composer::class.java, Int::class.javaPrimitiveType)
                    val shiftedBody: @Composable LazyItemScope.(Int) -> Unit = { index ->
                        HostBridge.invoke(invokeBody, body, this, index + split, currentComposer, 0)
                    }
                    method.invoke(scope, count - split, shiftedKey, shiftedType, shiftedBody)
                }
                null
            } else method.invoke(scope, *args)
        } as LazyListScope
    }
    private fun appendSidebar(scope: LazyListScope) {
            val body: @Composable LazyItemScope.() -> Unit = {
                val navigator = navigatorLocal.current
                if (controller.sidebarVisible.value) NativeUi.SidebarMv(navigator.navBackStack.lastOrNull() == route) {
                    hostNavigator = WeakReference(navigator)
                    navigator.navigate(route)
                }
            }
            LazyListScope::class.java.methods.single { it.name == "item" && it.parameterCount == 3 }
                .invoke(scope, "local.spw.mv.library", "local-mv", body)
    }
    val navigationHook = object : ComposableHook() {
        @Composable override fun Content(call: UiHookCall) {
            val navigator = navigatorLocal.current
            SideEffect { hostNavigator = WeakReference(navigator) }
            val visible = controller.sidebarVisible.value
            LaunchedEffect(visible) { if (!visible) navigator.navBackStack.removeAll { it == route } }
            if (active() && navigator.navBackStack.lastOrNull() == route) MvLibraryPage(controller)
            else HostBridge.invoke(navigation, call.thisObject, currentComposer, 0)
        }
    }
    override fun close() { edt {
        hostNavigator?.get()?.let { navigator -> navigator.navBackStack.removeAll { it == route } }
        hostNavigator = null
    } }
}

