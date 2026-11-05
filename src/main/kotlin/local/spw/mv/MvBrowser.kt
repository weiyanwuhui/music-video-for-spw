@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package local.spw.mv

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import java.nio.file.Path
import androidx.compose.ui.res.loadImageBitmap
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

fun filterMv(entries: List<MvQueueEntry>, query: String): List<MvQueueEntry> {
    val words = query.trim().split(Regex("\\s+")).map(::mvName).filter { it.isNotEmpty() }
    return entries.filter { entry ->
        val raw = "${entry.title} ${entry.detail} ${entry.file.fileName}"
        val text = mvName(raw)
        val tokens = raw.split(Regex("[\\p{P}\\p{Z}\\p{S}\\s]+")).map(::mvName)
        words.all { if (it.length == 1 && it[0] in 'a'..'z') it in tokens else it in text }
    }
}

@Composable fun MvSearch(value: String, change: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = NativeUi.palette()
    Row(modifier.fillMaxWidth().height(42.dp).clip(playerShape(8.dp)).background(colors.text.copy(alpha = .055f))
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PlayerIcon(MvIcon.Search, Modifier.size(18.dp), colors.muted)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) PlayerText("搜索歌名、歌手或文件名", size = 13, color = colors.muted)
            NativeUi.SearchInput(value, change, Modifier.fillMaxWidth().semantics { contentDescription = "搜索 MV" },
                TextStyle(color = colors.text, fontSize = 14.sp))
        }
        if (value.isNotEmpty()) NativeUi.IconControl("清空搜索", Modifier.size(28.dp), {
            PlayerIcon(MvIcon.Close, Modifier.size(14.dp), colors.muted)
        }) { change("") }
    }
}

@Composable fun MvCover(entry: MvQueueEntry, controller: MvController?, modifier: Modifier) {
    val colors = NativeUi.palette()
    var bitmap by remember(entry.file, entry.displayTrack?.path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(entry.file, entry.displayTrack?.path) {
        try { controller?.artwork?.load(entry)?.let { data -> bitmap = runInterruptible(Dispatchers.IO) { loadImageBitmap(ByteArrayInputStream(data)) } } }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { System.err.println("SPW MV cover ${entry.file.fileName}: ${error.message}") }
    }
    Box(modifier.mvFoil().clip(playerShape(7.dp)).background(colors.text.copy(alpha = .06f)), contentAlignment = Alignment.Center) {
        if (bitmap != null) NativeUi.Image(bitmap!!, "MV 封面 ${entry.file.fileName}", Modifier.fillMaxSize())
        else PlayerIcon(MvIcon.Video, Modifier.size(24.dp), colors.muted.copy(alpha = .45f))
    }
}

@Composable fun MvFileRow(entry: MvQueueEntry, selected: Boolean, controller: MvController? = null, library: Boolean = false, click: () -> Unit) {
    val colors = NativeUi.palette()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val albumColumn = library && maxWidth >= 760.dp
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(playerShape(8.dp))
        .background(if (selected) colors.text.copy(alpha = .08f) else Color.Transparent)
        .clickable(role = if (library) Role.Button else Role.RadioButton, onClick = click).semantics { this.selected = selected; contentDescription = "选择 MV ${entry.file.fileName}" }
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        MvCover(entry, controller, Modifier.size(48.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            PlayerText(entry.title, size = 14, bold = false)
            PlayerText(entry.detail, size = 11, color = colors.muted)
        }
        if (albumColumn) PlayerText(entry.displayTrack?.album.orEmpty(), Modifier.weight(.8f), size = 12, color = colors.muted)
        if (library && entry.displayTrack?.duration?.let { it > 0 } == true) PlayerText(displayTime(entry.displayTrack!!.duration / 1000.0), Modifier.padding(end = 10.dp), size = 12, color = colors.muted)
        if (selected && !library) PlayerIcon(MvIcon.Check, Modifier.size(18.dp), colors.text)
    }
    }
}

/** The modal is in the host root, above retained-page input shields; it never opens another OS window. */
@Composable fun MvModal(title: String, description: String, dismiss: () -> Unit, confirm: () -> Unit,
    confirmLabel: String, enabled: Boolean = true, dismissLabel: String = "暂不链接", content: @Composable () -> Unit) {
    val colors = NativeUi.palette()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BoxWithConstraints(NativeUi.focusable(Modifier.fillMaxSize().zIndex(Float.MAX_VALUE).focusRequester(focus))
        .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { dismiss(); true } else false }
        .semantics { paneTitle = title }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .32f)).clickable(onClick = dismiss))
        Column(Modifier.align(Alignment.Center).widthIn(max = 680.dp).fillMaxWidth(.92f).heightIn(max = (maxHeight - 24.dp).coerceAtLeast(100.dp))
            .chrome(14.dp).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NativeUi.Text(title, Modifier.weight(1f), 20, colors.text, bold = true)
                NativeUi.IconControl("关闭选择窗口", Modifier.size(32.dp), { PlayerIcon(MvIcon.Close, color = colors.muted) }, dismiss)
            }
            NativeUi.Text(description, Modifier.padding(top = 10.dp), 13, colors.muted)
            Column(Modifier.weight(1f, fill = false).padding(vertical = 16.dp)) { content() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NativeUi.Button(dismissLabel, Modifier.weight(1f), quiet = true, click = dismiss)
                NativeUi.Button(confirmLabel, Modifier.weight(1f), enabled = enabled, click = confirm)
            }
        }
    }
}

@Composable fun MvBrowser(controller: MvController) {
    val state = controller.screen.value
    val request = state.browser ?: return
    var query by remember(request) { mutableStateOf("") }
    var selected by remember(request, state.folder) { mutableStateOf<Path?>(null) }
    val entries = remember(state.videos, state.queue) { state.videos.map { file -> state.queue.firstOrNull { it.file == file } ?: MvQueueEntry(file) } }
    val filtered = remember(entries, query) { filterMv(entries, query) }
    MvModal("为歌曲选择 MV", "${request.track.title} · ${request.track.artist}", controller::cancelLink,
        { selected?.let(controller::confirmBrowser) }, if (request.playAfter) "链接并播放" else "保存链接", selected != null) {
        MvSearch(query, { query = it })
        PlayerText("${filtered.size} 个视频 · ${state.folder}", Modifier.padding(vertical = 10.dp), size = 11)
        if (filtered.isEmpty()) PlayerText("没有找到视频，试试歌名或歌手名", Modifier.padding(16.dp), size = 13)
        NativeUi.LazyRows(Modifier.fillMaxWidth().heightIn(max = 300.dp), filtered.size) { index ->
            val entry = filtered[index]
            MvFileRow(entry, selected == entry.file, controller) { selected = entry.file }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NativeUi.Button("刷新目录", quiet = true, click = controller::refresh)
            NativeUi.Button("更换文件夹", quiet = true, click = controller::chooseFolder)
        }
    }
}

@Composable fun MvLibraryPage(controller: MvController) {
    val state = controller.screen.value
    val colors = NativeUi.palette()
    var query by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<MvQueueEntry?>(null) }
    val playEntry: (MvQueueEntry) -> Unit = { chosen = it; controller.select(it) }
    var grid by remember { mutableStateOf(controller.store.libraryLayout() == "Grid") }
    var searching by remember { mutableStateOf(false) }
    var descending by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val entries = remember(state.queue, query, descending) {
        filterMv(state.queue.filter { it.online != null || it.file in state.videos }, query).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }).let { if (descending) it.reversed() else it }
    }
    LaunchedEffect(Unit) { controller.refreshPlaylist() }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 104.dp).semantics { paneTitle = "MV 媒体库" }) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NativeUi.Text("MV", size = 24, color = colors.text, bold = true)
            PlayerText("${entries.size}", size = 13, color = colors.muted)
            Spacer(Modifier.weight(1f))
            NativeUi.IconControl("名称排序", Modifier.size(36.dp), { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PlayerIcon(MvIcon.Order, Modifier.size(20.dp), colors.text) } }) { descending = !descending }
            NativeUi.IconControl("搜索视频", Modifier.size(36.dp), { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PlayerIcon(MvIcon.Search, Modifier.size(20.dp), colors.text) } }) { searching = !searching; if (!searching) query = "" }
            NativeUi.IconControl("MV 媒体库设置", Modifier.size(36.dp), { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PlayerIcon(MvIcon.More, Modifier.size(20.dp), colors.text) } }) { menu = !menu }
        }
        if (searching) MvSearch(query, { query = it }, Modifier.padding(top = 8.dp, bottom = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NativeUi.Button(orderNames[state.order] ?: "列表循环", quiet = false) { val modes = orderNames.keys.toList(); controller.order(modes[(modes.indexOf(state.order) + 1) % modes.size]) }
            NativeUi.IconControl("播放全部 MV", Modifier.size(36.dp), { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PlayerIcon(MvIcon.Play, Modifier.size(18.dp), colors.text) } }) { entries.firstOrNull()?.let(controller::select) }
            NativeUi.IconControl("随机播放 MV", Modifier.size(36.dp), { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PlayerIcon(MvIcon.Shuffle, Modifier.size(20.dp), colors.text) } }) { controller.order("Random"); entries.randomOrNull()?.let(controller::select) }
            Spacer(Modifier.weight(1f))
            NativeUi.IconControl(if (grid) "列表视图" else "平铺视图", Modifier.size(36.dp), {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PlayerIcon(if (grid) MvIcon.List else MvIcon.Grid, Modifier.size(20.dp), colors.text) }
            }) { grid = !grid; val value = if (grid) "Grid" else "List"; controller.dispatch { controller.store.setLibraryLayout(value) } }
        }
        if (entries.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PlayerIcon(MvIcon.Video, Modifier.size(46.dp), colors.muted)
                PlayerText(if (query.isNotBlank()) "没有找到匹配的视频" else "把 MV 放进指定文件夹，然后点击刷新", color = colors.muted)
            }
        }
        else if (grid) MvGrid(entries, chosen, controller, Modifier.weight(1f).fillMaxWidth(), playEntry)
        else NativeUi.LazyRows(Modifier.weight(1f).fillMaxWidth(), entries.size) { index ->
            val entry = entries[index]
            MvFileRow(entry, chosen?.file == entry.file, controller, library = true) { playEntry(entry) }
        }
        state.queue.firstOrNull { it.file == chosen?.file && (it.online != null || it.file in state.videos) }?.let { entry ->
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayerText(entry.title, Modifier.weight(1f), size = 13)
                if (entry.track != null) NativeUi.Button("解除链接", quiet = true) { controller.unlink(entry) }
                NativeUi.Button("链接当前歌曲", quiet = true) { controller.bindToCurrent(entry.file) }
            }
        }
    }
    if (menu) {
        Box(Modifier.fillMaxSize().clickable { menu = false })
        Column(Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 12.dp).width(240.dp).chrome(12.dp).padding(8.dp)) {
            Choice("添加 B 站链接", icon = MvIcon.Link) { menu = false; controller.textAction("link") }
            Choice("选择 MV 文件夹", icon = MvIcon.Folder) { menu = false; controller.chooseFolder() }
            Choice("刷新目录", icon = MvIcon.Refresh) { menu = false; controller.refresh() }
            Choice("为当前歌曲关联", icon = MvIcon.Link) { menu = false; controller.selectVideo(false) }
        }
    }
    }
}
