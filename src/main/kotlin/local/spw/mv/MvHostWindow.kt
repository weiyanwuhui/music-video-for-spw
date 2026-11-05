@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.mv

import androidx.compose.runtime.*
import androidx.compose.ui.window.*
import java.lang.reflect.ParameterizedType

/** Change the host's source of truth, retaining its pre-fullscreen floating size and position. */
data class MvWindowSnapshot(val placement: WindowPlacement, val position: WindowPosition, val size: androidx.compose.ui.unit.DpSize) {
    constructor(state: WindowState) : this(state.placement, state.position, state.size)
    fun restore(state: WindowState) { state.position = position; state.size = size; state.placement = placement }
}

object MvHostWindow {
    private val propertyType = HostBridge.resolver.type("com.moriafly.salt.ui.window.SaltWindowProperties")
    // SaltWindow requires button height <= bar height even when its caption buttons are hidden.
    val captionOverrides = listOf<Pair<String, Any>>("captionBarHeight" to 0f, "captionButtonHeight" to 0f, "captionButtonsVisible" to false, "moveable" to false)
        .map { (name, value) -> HostBridge.resolver.getter(propertyType, name) to value }
    private val local = HostBridge.resolver.facade("LocalWindowState").declaredMethods.single {
        it.parameterCount == 0 && it.returnType == ProvidableCompositionLocal::class.java &&
            (it.genericReturnType as? ParameterizedType)?.actualTypeArguments?.toList() == listOf(WindowState::class.java)
    }.apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val windowLocal = HostBridge.invoke(local, null) as ProvidableCompositionLocal<WindowState>
}
