@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.spw.mv

import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest

/** Pointer changes invalidate only this cover's layer/draw; idle cards have no running frame loop. */
@Composable fun Modifier.mvFoil(): Modifier {
    var hovered by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(Offset.Zero) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    val point = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val lift = animateFloatAsState(if (hovered) 1f else 0f, spring(stiffness = 500f, dampingRatio = .85f))
    LaunchedEffect(Unit) {
        snapshotFlow { target }.collectLatest { point.animateTo(it, spring(stiffness = 700f, dampingRatio = .88f)) }
    }
    return this.onSizeChanged { bounds = it }.onPointerEvent(PointerEventType.Enter) { hovered = true }
        .onPointerEvent(PointerEventType.Move) { event ->
            event.changes.firstOrNull()?.position?.let {
                target = Offset((it.x / bounds.width.coerceAtLeast(1) * 2 - 1).coerceIn(-1f, 1f), (it.y / bounds.height.coerceAtLeast(1) * 2 - 1).coerceIn(-1f, 1f))
            }
        }.onPointerEvent(PointerEventType.Exit) { hovered = false; target = Offset.Zero }
        .graphicsLayer {
            val amount = lift.value
            rotationX = -point.value.y * 7f * amount; rotationY = point.value.x * 7f * amount
            scaleX = 1f + .025f * amount; scaleY = scaleX
            translationY = -2.dp.toPx() * amount; shadowElevation = 9.dp.toPx() * amount
            ambientShadowColor = Color.Black.copy(alpha = .1f); spotShadowColor = Color.Black.copy(alpha = .1f)
            shape = playerShape(7.dp); clip = true; cameraDistance = 14f * density
        }.drawWithContent {
            drawContent()
            val amount = lift.value
            if (amount > .001f) {
                val center = Offset(size.width * (.5f + point.value.x * .4f), size.height * (.5f + point.value.y * .4f))
                drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .25f * amount), Color.Transparent), center, size.maxDimension * .8f))
                drawRect(Brush.linearGradient(listOf(Color(0xFF7DDCFF).copy(alpha = .1f * amount), Color.White.copy(alpha = .06f * amount), Color(0xFFFFD7A1).copy(alpha = .12f * amount)),
                    Offset(center.x - size.width, center.y - size.height), Offset(center.x + size.width, center.y + size.height)))
                drawRoundRect(Color.White.copy(alpha = .25f * amount), cornerRadius = CornerRadius(7.dp.toPx()), style = Stroke(1.dp.toPx()))
            }
        }
}
