@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.spw.mv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.*

enum class MvIcon { Play, Pause, Previous, Next, Volume, Muted, List, Grid, Fullscreen, Restore, Close, Back, More, Repeat, RepeatOne, Shuffle, Order, Chevron, Check, Link, Unlink, Folder, Refresh, Screen, Timer, Video, Search }
enum class MvPanel { None, Speed, Volume, Playlist, More, Order, Picture, Playback, Link, Folder, Quality, Danmaku;
    val isSettings get() = when (this) { More, Picture, Playback, Link, Folder, Quality, Danmaku -> true; else -> false }
}
private val Ink = Color.White
private val Muted = Color.White.copy(alpha = .62f)
private val Accent = Color.White
@Composable fun Modifier.chrome(radius: Dp): Modifier {
    val colors = NativeUi.palette()
    return clip(playerShape(radius)).background(colors.surface).drawBehind {
        drawRoundRect(colors.stroke, cornerRadius = CornerRadius(radius.toPx()), style = Stroke(1.dp.toPx()))
    }
}

@Composable fun PlayerText(text: String, modifier: Modifier = Modifier, size: Int = 13, color: Color? = null, bold: Boolean = false) {
    NativeUi.Text(text, modifier, size, color ?: NativeUi.palette().text, bold, singleLine = true)
}

/** Geometric playback symbols keep their shape regardless of SPW's chosen text font. */
@Composable fun PlayerIcon(icon: MvIcon, modifier: Modifier = Modifier, color: Color = Ink) {
    PlayerCanvas(modifier.size(24.dp)) {
        drawContext.canvas.save()
        drawContext.canvas.scale(size.width / 24f, size.height / 24f)
        try {
            fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x, y), Offset(x2, y2), 1.8f, StrokeCap.Round)
            fun triangle(x: Float, right: Boolean = true) {
                drawPath(Path().apply { moveTo(x, 5f); lineTo(x + if (right) 11f else -11f, 12f); lineTo(x, 19f); close() }, color)
            }
            when (icon) {
                MvIcon.Play -> triangle(8f)
                MvIcon.Pause -> { drawRoundRect(color, Offset(6f, 5f), Size(4f, 14f), CornerRadius(1f)); drawRoundRect(color, Offset(14f, 5f), Size(4f, 14f), CornerRadius(1f)) }
                MvIcon.Previous -> { triangle(18f, false); line(5f, 5f, 5f, 19f) }
                MvIcon.Next -> { triangle(6f); line(19f, 5f, 19f, 19f) }
                MvIcon.Volume, MvIcon.Muted -> {
                    drawPath(Path().apply { moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close() }, color)
                    if (icon == MvIcon.Volume) { drawArc(color, -50f, 100f, false, Offset(9f, 6f), Size(10f, 12f), style = Stroke(1.7f, cap = StrokeCap.Round)); drawArc(color, -50f, 100f, false, Offset(8f, 2f), Size(16f, 20f), style = Stroke(1.7f, cap = StrokeCap.Round)) }
                    else { line(16f, 9f, 22f, 15f); line(22f, 9f, 16f, 15f) }
                }
                MvIcon.List -> { for (y in listOf(6f, 12f, 18f)) { drawCircle(color, 1f, Offset(3f, y)); line(8f, y, 21f, y) } }
                MvIcon.Fullscreen -> { line(4f, 9f, 4f, 4f); line(4f, 4f, 9f, 4f); line(15f, 4f, 20f, 4f); line(20f, 4f, 20f, 9f); line(20f, 15f, 20f, 20f); line(20f, 20f, 15f, 20f); line(9f, 20f, 4f, 20f); line(4f, 20f, 4f, 15f) }
                MvIcon.Restore -> { line(4f, 9f, 9f, 9f); line(9f, 9f, 9f, 4f); line(15f, 4f, 15f, 9f); line(15f, 9f, 20f, 9f); line(20f, 15f, 15f, 15f); line(15f, 15f, 15f, 20f); line(9f, 20f, 9f, 15f); line(9f, 15f, 4f, 15f) }
                MvIcon.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
                MvIcon.Back -> { line(14f, 5f, 7f, 12f); line(7f, 12f, 14f, 19f) }
                MvIcon.More -> for (x in listOf(5f, 12f, 19f)) drawCircle(color, 1.7f, Offset(x, 12f))
                MvIcon.Repeat, MvIcon.RepeatOne -> {
                    drawArc(color, -180f, 170f, false, Offset(4f, 5f), Size(16f, 14f), style = Stroke(1.7f, cap = StrokeCap.Round))
                    drawArc(color, 0f, 170f, false, Offset(4f, 5f), Size(16f, 14f), style = Stroke(1.7f, cap = StrokeCap.Round))
                    line(20f, 5f, 20f, 10f); line(20f, 10f, 16f, 9f)
                    line(4f, 19f, 4f, 14f); line(4f, 14f, 8f, 15f)
                    if (icon == MvIcon.RepeatOne) { line(11f, 10f, 13f, 9f); line(13f, 9f, 13f, 15f) }
                }
                MvIcon.Shuffle -> { line(4f, 6f, 8f, 6f); line(8f, 6f, 16f, 18f); line(16f, 18f, 21f, 18f); line(4f, 18f, 8f, 18f); line(8f, 18f, 16f, 6f); line(16f, 6f, 21f, 6f); line(18f, 3f, 21f, 6f); line(21f, 6f, 18f, 9f) }
                MvIcon.Order -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 16f, 12f); line(4f, 18f, 12f, 18f); line(18f, 15f, 21f, 18f); line(21f, 18f, 18f, 21f) }
                MvIcon.Chevron -> { line(6f, 9f, 12f, 15f); line(12f, 15f, 18f, 9f) }
                MvIcon.Check -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 20f, 7f) }
                MvIcon.Link, MvIcon.Unlink -> {
                    drawRoundRect(color, Offset(3f, 7f), Size(10f, 10f), CornerRadius(4f), style = Stroke(1.6f))
                    drawRoundRect(color, Offset(11f, 7f), Size(10f, 10f), CornerRadius(4f), style = Stroke(1.6f))
                    if (icon == MvIcon.Unlink) line(4f, 3f, 20f, 21f)
                }
                MvIcon.Folder -> { drawPath(Path().apply { moveTo(3f, 6f); lineTo(9f, 6f); lineTo(11f, 8f); lineTo(21f, 8f); lineTo(21f, 19f); lineTo(3f, 19f); close() }, color, style = Stroke(1.6f)) }
                MvIcon.Refresh -> { drawArc(color, -50f, 300f, false, Offset(5f, 5f), Size(14f, 14f), style = Stroke(1.7f, cap = StrokeCap.Round)); line(19f, 3f, 19f, 8f); line(19f, 8f, 14f, 8f) }
                MvIcon.Screen -> { drawRoundRect(color, Offset(3f, 5f), Size(18f, 13f), CornerRadius(2f), style = Stroke(1.6f)); line(8f, 21f, 16f, 21f); line(12f, 18f, 12f, 21f) }
                MvIcon.Timer -> { drawCircle(color, 8f, Offset(12f, 13f), style = Stroke(1.6f)); line(12f, 8f, 12f, 13f); line(12f, 13f, 16f, 15f); line(9f, 2f, 15f, 2f) }
                MvIcon.Video -> {
                    drawPath(Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRoundRect(RoundRect(Rect(2f, 4f, 22f, 20f), CornerRadius(3f)))
                        moveTo(10f, 8f); lineTo(16f, 12f); lineTo(10f, 16f); close()
                    }, color)
                }
                MvIcon.Search -> { drawCircle(color, 6.5f, Offset(10f, 10f), style = Stroke(1.7f)); line(15f, 15f, 21f, 21f) }
                MvIcon.Grid -> for (x in listOf(4f, 14f)) for (y in listOf(4f, 14f)) drawRoundRect(color, Offset(x,y), Size(6f,6f), CornerRadius(1f), style = Stroke(1.6f))
            }
        } finally { drawContext.canvas.restore() }
    }
}

@Composable fun PlayerButton(label: String, icon: MvIcon? = null, modifier: Modifier = Modifier,
    primary: Boolean = false, selected: Boolean = false, text: String? = null, click: () -> Unit) {
    NativeUi.IconControl(label, modifier.size(if (primary) 42.dp else 36.dp)
        .semantics { contentDescription = label }, {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (icon != null) PlayerIcon(icon, Modifier.size(if (primary) 26.dp else 20.dp), Ink)
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            PlayerText(text.orEmpty(), size = 13, bold = true, color = if (selected) Accent else Ink)
        }
        }
    }, click)
}

@Composable fun ThinSlider(label: String, value: () -> Float, change: (Float) -> Unit, finish: () -> Unit,
    modifier: Modifier = Modifier, vertical: Boolean = false, preview: (Float?) -> Unit = {}, cancel: () -> Unit = {}, alwaysThumb: Boolean = false) {
    var hovered by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val setValue by rememberUpdatedState(change)
    val onFinish by rememberUpdatedState(finish)
    val onCancel by rememberUpdatedState(cancel)
    val showPreview by rememberUpdatedState(preview)
    val thickness = animateFloatAsState(if (hovered || dragging) 4f else if (alwaysThumb) 3f else 2f, tween(140))
    fun ratio(position: Offset, width: Float, height: Float, inset: Float) =
        if (vertical) (1f - (position.y - inset) / (height - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
        else ((position.x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
    PlayerCanvas(modifier.onPointerEvent(PointerEventType.Enter) { hovered = true }
        .onPointerEvent(PointerEventType.Exit) { hovered = false; if (!dragging) showPreview(null) }
        .onPointerEvent(PointerEventType.Move) { event ->
            if (!vertical && !dragging) event.changes.firstOrNull()?.let { showPreview(ratio(it.position, size.width.toFloat(), size.height.toFloat(), 6.dp.toPx())) }
        }.semantics {
            contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(value().coerceIn(0f, 1f), 0f..1f)
            setProgress { setValue(it.coerceIn(0f, 1f)); onFinish(); true }
        }.pointerInput(vertical) {
            awaitPointerEventScope {
                var active: PointerId? = null
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = if (active == null) event.changes.firstOrNull { it.pressed && !it.previousPressed && !it.isConsumed }
                            else event.changes.firstOrNull { it.id == active }
                        if (pointer != null) {
                            active = pointer.id; dragging = pointer.pressed
                            val current = ratio(pointer.position, size.width.toFloat(), size.height.toFloat(), 6.dp.toPx())
                            setValue(current); if (!vertical) showPreview(current)
                            pointer.consume()
                            if (!pointer.pressed) { onFinish(); active = null; if (!hovered) showPreview(null) }
                        }
                    }
                } finally { dragging = false; showPreview(null); onCancel() }
            }
        }) {
        val inset = 6.dp.toPx()
        val start = if (vertical) Offset(size.width / 2, size.height - inset) else Offset(inset, size.height / 2)
        val end = if (vertical) Offset(size.width / 2, inset) else Offset(size.width - inset, size.height / 2)
        val thumb = start + (end - start) * value().coerceIn(0f, 1f)
        drawLine(Ink.copy(alpha = .18f), start, end, thickness.value.dp.toPx(), StrokeCap.Round)
        drawLine(Accent, start, thumb, thickness.value.dp.toPx(), StrokeCap.Round)
        if (hovered || dragging || vertical || alwaysThumb) {
            drawCircle(Color.Black.copy(alpha = .12f), 7.dp.toPx(), thumb)
            drawCircle(Ink, (if (dragging) 5f else 4f).dp.toPx(), thumb)
        }
    }
}

val orderNames = linkedMapOf("Circle" to "列表循环", "CircleEnd" to "顺序播放", "RepeatOne" to "单曲循环", "Random" to "随机播放")
fun orderIcon(order: String) = when (order) { "Random" -> MvIcon.Shuffle; "RepeatOne" -> MvIcon.RepeatOne; "CircleEnd" -> MvIcon.Order; else -> MvIcon.Repeat }

@Composable fun MvPlaybackControls(page: MvPage, controller: MvController, state: MvScreenState, modifier: Modifier = Modifier) {
    var drag by remember { mutableStateOf<Float?>(null) }
    var preview by remember { mutableStateOf<Float?>(null) }
    val duration = state.playback.durationSeconds.coerceAtLeast(1.0)
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f))))
        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp)) {
            ThinSlider("播放进度", { drag ?: (controller.screen.value.playback.positionSeconds / duration).toFloat() }, { drag = it },
                { drag?.let { controller.seek(it * duration) }; drag = null }, Modifier.fillMaxSize(),
                preview = { preview = it }, cancel = { drag = null })
            preview?.let { point ->
                val width = maxWidth
                Box(Modifier.align(Alignment.TopStart).offset(y = (-24).dp).width(72.dp).height(26.dp)
                    .graphicsLayer { translationX = (width.toPx() * point - 36.dp.toPx()).coerceIn(0f, (width - 72.dp).toPx().coerceAtLeast(0f)) }
                    .chrome(13.dp), contentAlignment = Alignment.Center) {
                    PlayerText(displayTime(point * duration), size = 11)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp)) {
            val showOrderLabel = maxWidth >= 760.dp
            Box(Modifier.align(Alignment.CenterStart)) {
            NativeUi.IconControl("播放顺序", Modifier.size(if (showOrderLabel) 128.dp else 44.dp, 40.dp)
                .semantics { contentDescription = "播放顺序" }, {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerIcon(orderIcon(state.order), Modifier.size(18.dp))
                if (showOrderLabel) PlayerText(orderNames[state.order] ?: "列表循环", size = 12, color = Muted)
                }
            }, { page.togglePanel(MvPanel.Order) })
            }
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayerButton("上一首", MvIcon.Previous) { controller.navigate(true) }
                PlayerButton(if (state.playback.paused) "播放" else "暂停", if (state.playback.paused) MvIcon.Play else MvIcon.Pause, primary = true, click = controller::toggle)
                PlayerButton("下一首", MvIcon.Next) { controller.navigate(false) }
            }
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.online != null) {
                    PlayerButton("弹幕设置", text = "弹幕", modifier = Modifier.width(58.dp), selected = danmakuOptions(controller.store).enabled) { page.togglePanel(MvPanel.Danmaku) }
                    val quality = state.online.qualities.firstOrNull { it.id == state.quality }?.label?.substringBefore(' ')
                        ?: state.online.qualities.firstOrNull()?.label?.substringBefore(' ') ?: "画质"
                    PlayerButton("画质选择", text = quality, modifier = Modifier.width(64.dp), selected = page.panel.value == MvPanel.Quality) { page.togglePanel(MvPanel.Quality) }
                }
                PlayerButton("播放速度", text = "${state.playback.speed}×", modifier = Modifier.width(62.dp), selected = page.panel.value == MvPanel.Speed) { page.togglePanel(MvPanel.Speed) }
                PlayerButton("音量", if (state.volume == 0.0) MvIcon.Muted else MvIcon.Volume, selected = page.panel.value == MvPanel.Volume) { page.togglePanel(MvPanel.Volume) }
                PlayerButton("MV 播放列表", MvIcon.List, selected = page.panel.value == MvPanel.Playlist) { page.togglePanel(MvPanel.Playlist) }
                PlayerButton(if (page.full.value) "退出全屏" else "全屏", if (page.full.value) MvIcon.Restore else MvIcon.Fullscreen, click = page::fullscreen)
            }
        }
    }
}

@Composable internal fun Choice(label: String, selected: Boolean = false, icon: MvIcon? = null,
    value: String? = null, submenu: Boolean = false, click: () -> Unit) {
    val colors = NativeUi.palette()
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(playerShape(6.dp)).clickable(role = Role.Button, onClick = click)
        .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        NativeUi.MenuLabel(label, Modifier.weight(1f)) {
            if (selected || icon != null) PlayerIcon(if (selected) MvIcon.Check else icon!!, Modifier.size(19.dp), colors.text)
        }
        if (value != null) PlayerText(value, size = 12, color = colors.muted)
        if (submenu) PlayerIcon(MvIcon.Chevron, Modifier.size(13.dp).graphicsLayer { rotationZ = -90f }, colors.muted)
    }
}

@Composable private fun VerticalVolume(value: Float, change: (Float) -> Unit) {
    ThinSlider("音量", { value }, change, {}, Modifier.width(36.dp).height(118.dp), vertical = true)
}

@Composable private fun SectionLabel(label: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        PlayerCanvas(Modifier.fillMaxWidth().height(1.dp)) { drawRect(Color.White.copy(alpha = .06f)) }
        if (label.isNotEmpty()) PlayerText(label, Modifier.padding(start = 12.dp, top = 10.dp), size = 12, color = Muted)
    }
}

@Composable private fun DanmakuSettings(controller: MvController, state: MvScreenState, modifier: Modifier = Modifier) {
    val options = remember(state.danmakuRevision) { danmakuOptions(controller.store) }
    val colors = NativeUi.palette()
    var enabled by remember { mutableStateOf(options.enabled) }
    LaunchedEffect(options.enabled) { enabled = options.enabled }
    Column(modifier.chrome(14.dp).padding(horizontal = 20.dp, vertical = 14.dp).semantics { paneTitle = "弹幕设置" }) {
        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerText("弹幕设置", Modifier.weight(1f), size = 16, bold = true, color = colors.text)
            PlayerButton("收起弹幕设置", MvIcon.Close) { controller.page.panel.value = MvPanel.None }
        }
        NativeUi.LazyRows(Modifier.fillMaxWidth().weight(1f, fill = false), 9) { index ->
            when (index) {
                0 -> Row(Modifier.fillMaxWidth().height(48.dp).clip(playerShape(8.dp))
                    .clickable(role = Role.Switch) { enabled = !enabled; controller.danmakuSetting("enabled", enabled.toString()) }
                    .semantics { toggleableState = androidx.compose.ui.state.ToggleableState(enabled) },
                    verticalAlignment = Alignment.CenterVertically) {
                    PlayerText("显示弹幕", Modifier.weight(1f), size = 14, color = colors.text)
                    PlayerCanvas(Modifier.width(38.dp).height(22.dp)) {
                        val active = enabled
                        drawRoundRect(if (active) Color(0xff1476c8) else colors.text.copy(alpha = .22f), cornerRadius = CornerRadius(size.height / 2))
                        drawCircle(Color.White, radius = size.height / 2 - 3.dp.toPx(), center = Offset(if (active) size.width - size.height / 2 else size.height / 2, size.height / 2))
                    }
                }
                1 -> Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                    PlayerText("显示类型", Modifier.padding(bottom = 10.dp), size = 12, color = colors.muted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((label, mode) in listOf("滚动弹幕" to 1, "顶部弹幕" to 5, "底部弹幕" to 4)) {
                            val selected = mode in options.modes
                            Box(Modifier.weight(1f).height(36.dp).clip(playerShape(18.dp))
                                .background(colors.text.copy(alpha = if (selected) .13f else .04f))
                                .clickable(role = Role.Checkbox) { controller.danmakuSetting("mode$mode", (!selected).toString()) }
                                .semantics { this.selected = selected }, contentAlignment = Alignment.Center) {
                                PlayerText(label, size = 12, color = if (selected) colors.text else colors.muted)
                            }
                        }
                    }
                }
                2 -> PlayerText("弹幕样式", Modifier.padding(top = 14.dp, bottom = 6.dp), size = 12, color = colors.muted)
                3 -> DanmakuSettingSlider("显示区域", options.area, .25f, 1f, "area", state.danmakuRevision, controller) { "${(it * 100).roundToInt()}%" }
                4 -> DanmakuSettingSlider("不透明度", options.opacity, .2f, 1f, "opacity", state.danmakuRevision, controller) { "${(it * 100).roundToInt()}%" }
                5 -> DanmakuSettingSlider("弹幕字号", options.scale, .5f, 2f, "scale", state.danmakuRevision, controller) { "${(it * 100).roundToInt()}%" }
                6 -> DanmakuSettingSlider("弹幕速度", (20.0 / options.duration).toFloat(), 1f/6f, 20f, "speed", state.danmakuRevision, controller, logarithmic = true) { "${(it * 100).roundToInt()}%" }
                7 -> Column(Modifier.padding(top = 12.dp)) {
                    PlayerText("过滤", Modifier.padding(bottom = 4.dp), size = 12, color = colors.muted)
                    DanmakuAction("屏蔽关键词", "${options.words.size} 项") { controller.textAction("words") }
                    DanmakuSettingSlider("智能过滤", options.weight.toFloat(), 0f, 10f, "weight", state.danmakuRevision, controller) { if (it.roundToInt() == 0) "关闭" else "${it.roundToInt()}" }
                    PlayerText("减少短时间内的重复内容", Modifier.padding(bottom = 6.dp), size = 11, color = colors.muted)
                }
                8 -> DanmakuAction("重新加载弹幕", "") { controller.retryDanmaku() }
            }
        }
    }
}

@Composable private fun DanmakuAction(label: String, value: String, click: () -> Unit) {
    val colors = NativeUi.palette()
    Row(Modifier.fillMaxWidth().height(40.dp).clip(playerShape(8.dp)).clickable(role = Role.Button, onClick = click),
        verticalAlignment = Alignment.CenterVertically) {
        PlayerText(label, Modifier.weight(1f), size = 13, color = colors.text)
        PlayerText(value, size = 12, color = colors.muted)
    }
}

@Composable private fun DanmakuSettingSlider(label: String, initial: Float, minimum: Float, maximum: Float, key: String,
    revision: Long, controller: MvController, logarithmic: Boolean = false, format: (Float) -> String) {
    var current by remember(revision) { mutableFloatStateOf(initial.coerceIn(minimum, maximum)) }
    val colors = NativeUi.palette()
    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
        PlayerText(label, Modifier.width(68.dp), size = 12, color = colors.text)
        ThinSlider(label, { if (logarithmic) ln(current / minimum) / ln(maximum / minimum) else (current - minimum) / (maximum - minimum) },
            { current = if (logarithmic) minimum * (maximum / minimum).pow(it) else minimum + it * (maximum - minimum) },
            { controller.danmakuSetting(key, current.toString()) }, Modifier.weight(1f).height(36.dp), alwaysThumb = true)
        PlayerText(format(current), Modifier.width(52.dp).padding(start = 8.dp), size = 11, color = colors.muted)
    }
}

@Composable fun MvLinkPrompt(controller: MvController) {
    val request = controller.screen.value.linkRequest ?: return
    if (request.candidates.isEmpty()) {
        var link by remember(request) { mutableStateOf("") }
        val state = controller.screen.value
        val colors = NativeUi.palette()
        MvModal("添加 MV", "${request.track.title} · ${request.track.artist}".trim(' ', '·'), controller::cancelLink,
            { controller.addOnlineFromPrompt(request, link) }, "关联并播放", enabled = link.isNotBlank() && !state.busy, dismissLabel = "关闭") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NativeUi.Text("没找到本地 MV 呢，试试 B 站链接吧！", size = 15, color = colors.text)
                Box(Modifier.fillMaxWidth().background(colors.text.copy(alpha = .055f), playerShape(8.dp)).padding(horizontal = 12.dp, vertical = 7.dp)) {
                    if (link.isEmpty()) PlayerText("粘贴 B 站视频链接、BV / av 号", size = 13, color = colors.muted)
                    NativeUi.SearchInput(link, { link = it }, Modifier.fillMaxWidth().height(30.dp).semantics { contentDescription = "B 站 MV 链接" },
                        TextStyle(color = colors.text, fontSize = 14.sp))
                }
                if (state.message.isNotEmpty()) NativeUi.Text(state.message, size = 12, color = colors.muted)
                NativeUi.Button("选择本地视频", Modifier.fillMaxWidth(), quiet = true) {
                    controller.cancelLink(); controller.selectVideo(true)
                }
            }
        }
        return
    }
    var selected by remember(request) { mutableStateOf(request.candidates.first()) }
    MvModal(if (controller.store.blocked(request.track)) "恢复自动匹配" else "选择 MV 版本",
        listOf(request.track.title, request.track.artist).filter { it.isNotBlank() }.joinToString(" · "), controller::cancelLink,
        { controller.confirmLink(selected) }, "链接并播放") {
        Column(Modifier.fillMaxWidth()) {
            NativeUi.LazyRows(Modifier.fillMaxWidth().heightIn(max = 240.dp), request.candidates.size) { index ->
                val file = request.candidates[index]
                MvFileRow(controller.screen.value.queue.firstOrNull { it.file == file } ?: MvQueueEntry(file), selected == file, controller) { selected = file }
            }
            NativeUi.Button("其他文件", Modifier.padding(top = 8.dp), quiet = true) { controller.cancelLink(); controller.selectVideo(true) }
        }
    }
}

@Composable fun MvPanels(page: MvPage, controller: MvController, state: MvScreenState) {
    val panel = page.panel.value
    var rendered by remember { mutableStateOf(MvPanel.None) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(panel) {
        if (rendered != panel && rendered != MvPanel.None) reveal.animateTo(0f, tween(90))
        rendered = panel
        if (panel != MvPanel.None) reveal.animateTo(1f, tween(120))
    }
    if (rendered == MvPanel.None) return
    val selectedPanel = rendered
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val settingsHeight = (maxHeight - 254.dp).coerceAtLeast(80.dp)
        Box(Modifier.fillMaxSize().padding(top = 64.dp, bottom = 88.dp).clickable { page.panel.value = MvPanel.None })
        val motion = Modifier.graphicsLayer {
            alpha = reveal.value
            if (selectedPanel == MvPanel.Playlist) translationX = 12.dp.toPx() * (1f - reveal.value)
            else translationY = 4.dp.toPx() * (1f - reveal.value)
        }
        when (selectedPanel) {
            MvPanel.Speed -> Column(Modifier.align(Alignment.BottomEnd).padding(end = 128.dp, bottom = 88.dp).width(148.dp)
                .then(motion).chrome(12.dp).padding(8.dp).semantics { paneTitle = "播放速度" }) {
                PlayerText("播放速度", Modifier.padding(12.dp), size = 11, color = Muted)
                val speeds = listOf(.5, .75, 1.0, 1.25, 1.5, 2.0, 3.0)
                NativeUi.LazyRows(Modifier.fillMaxWidth().heightIn(max = 294.dp), speeds.size) { index ->
                    val speed = speeds[index]
                    Choice("${speed}×", abs(state.playback.speed - speed) < .01) { controller.speed(speed); page.panel.value = MvPanel.None }
                }
            }
            MvPanel.Volume -> {
                var volume by remember { mutableFloatStateOf(state.volume.toFloat()) }
                var remembered by remember { mutableFloatStateOf(state.volumeBeforeMute.toFloat()) }
                LaunchedEffect(state.volume) { volume = state.volume.toFloat(); if (volume > 0) remembered = volume }
                Column(Modifier.align(Alignment.BottomEnd).padding(end = 88.dp, bottom = 88.dp).width(52.dp)
                    .then(motion).chrome(12.dp).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PlayerText("${volume.toInt()}%", size = 11, color = Muted)
                    VerticalVolume(volume / 100) { volume = it * 100; if (volume > 0) remembered = volume; controller.volume(volume.toDouble()) }
                    PlayerButton("静音 / 恢复音量", if (volume == 0f) MvIcon.Muted else MvIcon.Volume) {
                        volume = if (volume == 0f) remembered else 0f; controller.volume(volume.toDouble())
                    }
                }
            }
            MvPanel.Order -> Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 88.dp).width(186.dp)
                .then(motion).chrome(12.dp).padding(8.dp).semantics { paneTitle = "播放顺序" }) {
                PlayerText("播放顺序", Modifier.padding(12.dp), size = 11, color = Muted)
                for ((mode, label) in orderNames) Choice(label, state.order == mode, orderIcon(mode)) { controller.order(mode); page.panel.value = MvPanel.None }
            }
            MvPanel.Quality -> Column(Modifier.align(Alignment.BottomEnd).padding(end = 195.dp, bottom = 88.dp).width(200.dp)
                .then(motion).chrome(12.dp).padding(8.dp).semantics { paneTitle = "画质" }) {
                PlayerText("画质", Modifier.padding(12.dp), size = 12, color = Muted)
                val qualities = state.online?.qualities.orEmpty()
                if (qualities.isEmpty()) PlayerText("仅在线 MV 支持画质切换", Modifier.padding(12.dp), color = Muted)
                else NativeUi.LazyRows(Modifier.fillMaxWidth().heightIn(max = settingsHeight), qualities.size) { index ->
                    val quality = qualities[index]
                    Choice(quality.label, state.quality == quality.id) { controller.quality(quality.id); page.panel.value = MvPanel.None }
                }
            }
            MvPanel.Danmaku -> DanmakuSettings(controller, state, Modifier.align(Alignment.BottomEnd)
                .padding(end = 190.dp, bottom = 88.dp).widthIn(max = 370.dp).fillMaxWidth().heightIn(max = (maxHeight - 130.dp).coerceAtLeast(100.dp)).then(motion))
            MvPanel.More, MvPanel.Picture, MvPanel.Playback, MvPanel.Link, MvPanel.Folder -> {
                val title = when (selectedPanel) { MvPanel.Picture -> "画面"; MvPanel.Playback -> "播放"; MvPanel.Link -> "当前 MV"; MvPanel.Folder -> "MV 文件夹"; MvPanel.Quality -> "画质"; MvPanel.Danmaku -> "弹幕"; else -> "更多" }
                Column(Modifier.align(Alignment.TopEnd).padding(top = 78.dp, end = 26.dp).width(294.dp)
                    .then(motion).chrome(12.dp).padding(8.dp).semantics { paneTitle = "更多设置" }) {
                    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selectedPanel != MvPanel.More) PlayerButton("返回更多设置", MvIcon.Back, Modifier.size(32.dp)) { page.panel.value = MvPanel.More }
                        PlayerText(title, Modifier.weight(1f).padding(start = 10.dp), size = 15, bold = true)
                        PlayerButton("收起设置", MvIcon.Close, Modifier.size(32.dp)) { page.panel.value = MvPanel.None }
                    }
                    val count = when (selectedPanel) { MvPanel.More -> 6; MvPanel.Picture -> MvAspect.entries.size; MvPanel.Playback -> 6; MvPanel.Link -> 4; else -> 3 }
                    NativeUi.LazyRows(Modifier.fillMaxWidth().heightIn(max = settingsHeight), count) { index ->
                        when (selectedPanel) {
                            MvPanel.More -> when (index) {
                                0 -> Choice("画面", icon = MvIcon.Screen, value = page.aspect.value.label, submenu = true) { page.panel.value = MvPanel.Picture }
                                1 -> Choice("播放", icon = MvIcon.Play, submenu = true) { page.panel.value = MvPanel.Playback }
                                2 -> Choice("画质", value = state.online?.qualities?.firstOrNull { it.id == state.quality }?.label ?: "本地视频", submenu = true) { page.panel.value = MvPanel.Quality }
                                3 -> Choice("当前 MV", icon = MvIcon.Link, submenu = true) { page.panel.value = MvPanel.Link }
                                4 -> Choice("MV 文件夹", icon = MvIcon.Folder, submenu = true) { page.panel.value = MvPanel.Folder }
                                5 -> Choice("添加 B 站链接", icon = MvIcon.Link) { controller.textAction("link") }
                            }
                            MvPanel.Picture -> MvAspect.entries[index].let { value -> Choice(value.label, page.aspect.value == value) { page.aspect(value) } }
                            MvPanel.Playback -> when (index) {
                                0 -> Choice("自动隐藏控件", page.autoHide.value) { page.autoHide(!page.autoHide.value) }
                                1 -> if (page.autoHide.value) Choice("隐藏等待", value = "${page.hideDelay.value} 秒") { page.hideDelay(when (page.hideDelay.value) { 3 -> 5; 5 -> 8; else -> 3 }) }
                                2 -> Choice("双击画面全屏", page.doubleClickFull.value) { page.doubleClickFull(!page.doubleClickFull.value) }
                                3 -> Choice("切歌自动打开 MV", state.autoOpen) { controller.autoOpen(!state.autoOpen) }
                                4 -> SectionLabel("")
                                5 -> Choice("还原界面设置") { page.fillVideo(false); page.autoHide(true); page.hideDelay(3); page.doubleClickFull(true) }
                            }
                            MvPanel.Link -> when (index) {
                                0 -> PlayerText(state.match.file?.fileName?.toString() ?: state.match.message, Modifier.padding(12.dp), size = 12, color = Muted)
                                1 -> if (state.track?.let { controller.store.blocked(it) } == true) Choice("恢复自动匹配", icon = MvIcon.Refresh) { controller.resumeMatching() }
                                2 -> Choice("更换 MV", icon = MvIcon.Link) { controller.replaceLink() }
                                3 -> Choice("不再匹配此歌曲", icon = MvIcon.Unlink) { controller.unbindCurrent() }
                            }
                            MvPanel.Folder -> when (index) {
                                0 -> PlayerText(state.folder.ifEmpty { "尚未选择文件夹" }, Modifier.padding(12.dp), size = 12, color = Muted)
                                1 -> Choice("选择文件夹", icon = MvIcon.Folder) { controller.chooseFolder() }
                                2 -> Choice("刷新目录", icon = MvIcon.Refresh, value = "${state.videos.size} 个视频") { controller.refresh() }
                            }
                            else -> Unit
                        }
                    }
                }
            }
            MvPanel.Playlist -> Column(Modifier.align(Alignment.CenterEnd).padding(top = 76.dp, end = 20.dp, bottom = 96.dp).width(332.dp)
                .fillMaxHeight().then(motion).chrome(12.dp).padding(16.dp).semantics { paneTitle = "MV 播放列表" }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayerText("播放列表", Modifier.weight(1f), size = 15, bold = true)
                    PlayerButton("收起播放列表", MvIcon.Close) { page.panel.value = MvPanel.None }
                }
                PlayerText("${state.queue.size} 个 MV", Modifier.padding(start = 4.dp, bottom = 14.dp), size = 11, color = Muted)
                if (state.queue.isEmpty()) PlayerText(if (state.busy) "正在读取…" else "还没有匹配的 MV", Modifier.padding(8.dp), size = 12, color = Muted)
                NativeUi.LazyRows(Modifier.fillMaxWidth(), state.queue.size) { index ->
                    val entry = state.queue[index]
                    MvFileRow(entry, entry.file == state.video) {
                        controller.select(entry); page.panel.value = MvPanel.None
                    }
                }
            }
            else -> Unit
        }
    }
}
