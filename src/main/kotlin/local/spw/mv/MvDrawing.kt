package local.spw.mv

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp

// SPW obfuscates several Compose facade functions; use its existing semantic bridge.
fun playerShape(radius: Dp) = NativeUi.rounded(radius)
fun Modifier.background(color: Color, shape: Shape? = null): Modifier = (if (shape == null) this else clip(shape)).drawBehind { drawRect(color) }
fun Modifier.background(brush: Brush): Modifier = drawBehind { drawRect(brush) }
fun Modifier.clickable(role: Role = Role.Button, onClick: () -> Unit) = NativeUi.clickable(this, role, onClick)
@Composable fun PlayerCanvas(modifier: Modifier, draw: DrawScope.() -> Unit) { Spacer(modifier.drawBehind(draw)) }

fun Modifier.playerDrag(key: Any?, drag: (PointerInputChange, Offset) -> Unit): Modifier = pointerInput(key) {
    awaitPointerEventScope {
        var active: PointerId? = null
        while (true) {
            val event = awaitPointerEvent()
            val pointer = if (active == null) event.changes.firstOrNull { it.pressed && !it.previousPressed && !it.isConsumed }
                else event.changes.firstOrNull { it.id == active }
            if (pointer != null) {
                if (!pointer.pressed) active = null
                else {
                    active = pointer.id
                    val delta = pointer.position - pointer.previousPosition
                    if (delta != Offset.Zero) drag(pointer, delta)
                }
            }
        }
    }
}
