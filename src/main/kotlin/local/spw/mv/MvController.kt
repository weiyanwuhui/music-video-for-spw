@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.mv

import androidx.compose.runtime.mutableStateOf
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi
import dev.gaboron.spwmedia.protocol.PlayerOptions
import dev.gaboron.spwmedia.protocol.PlayerState
import dev.gaboron.spwmedia.protocol.Protocol
import dev.gaboron.spwmedia.sdk.*
import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.*

data class MvQueueEntry(val file: Path, val track: MediaItem? = null, val suggested: MediaItem? = null, val online: BiliLink? = null) {
    val displayTrack get() = track ?: suggested
    val title get() = displayTrack?.title ?: file.fileName.toString().substringBeforeLast('.')
    val detail get() = displayTrack?.artist?.takeIf { it.isNotBlank() } ?: "本地视频"
}
data class MvLinkRequest(val track: MediaItem, val candidates: List<Path>)
data class MvBrowseRequest(val track: MediaItem, val playAfter: Boolean)
data class MvTextRequest(val kind: String, val track: MediaItem? = null, val initial: String = "")
data class MvDirectoryRequest(val path: Path, val folders: List<Path> = emptyList(), val busy: Boolean = true, val error: String = "")
private data class MvPauseIntent(val paused: Boolean, val restart: Boolean)
private data class MvSeekIntent(val target: Double, val from: Double)
data class MvScreenState(
    val track: MediaItem? = null, val folder: String = "", val videos: List<Path> = emptyList(),
    val match: MvMatch = MvMatch(message = "请先播放歌曲"), val autoOpen: Boolean = false,
    val video: Path? = null, val frames: SharedBuffer? = null, val playback: PlayerState = PlayerState(),
    val message: String = "", val busy: Boolean = false, val queue: List<MvQueueEntry> = emptyList(), val boost: Double? = null,
    val volumeBeforeMute: Double = 80.0, val volume: Double = 80.0, val order: String = "Circle", val linkRequest: MvLinkRequest? = null,
    val browser: MvBrowseRequest? = null, val directoryBrowser: MvDirectoryRequest? = null,
    val textRequest: MvTextRequest? = null, val login: BiliLoginState? = null, val online: BiliVideo? = null, val quality: Int = 0,
    val danmaku: List<BiliDanmaku> = emptyList(), val danmakuMessage: String = "", val seekGeneration: Long = 0, val danmakuRevision: Long = 0
)

fun <T> edt(action: () -> T): T {
    if (EventQueue.isDispatchThread()) return action()
    val task = FutureTask(Callable(action)); EventQueue.invokeAndWait(task); return task.get()
}
fun sameSong(a: MediaItem?, b: MediaItem?) = a != null && b != null && localFile(a.path) == localFile(b.path)

/** Audio ownership has one restore point and never restores over a different song. */
class SongHandoff(val track: MediaItem, val position: Long, val wasPlaying: Boolean) {
    fun restore(current: MediaItem?, seek: (Long) -> Unit, play: () -> Unit) {
        if (sameSong(track, current)) { seek(position); if (wasPlaying) play() }
    }
}

class MvController(private val pluginId: String, val store: MvStore,
    private val settings: com.xuncorp.spw.workshop.api.config.ConfigHelper? = null,
    private val pickVideo: ((MediaItem) -> Path?)? = null,
    hostWindow: () -> androidx.compose.ui.awt.ComposeWindow? = HostBridge::hostWindow,
    private val clock: () -> Pair<Long, Boolean> = HostBridge::clock) : AutoCloseable {
    val screen = mutableStateOf(MvScreenState(folder = store.folder()?.toString().orEmpty(), autoOpen = store.autoOpen(), order = store.playbackOrder()))
    val sidebarVisible = mutableStateOf(settings?.get("show_sidebar", true) ?: true)
    val page = MvPage(this, hostWindow)
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "SPW MV actions").apply { isDaemon = true } }
    private val directories = Executors.newSingleThreadExecutor { Thread(it, "SPW MV folder browser").apply { isDaemon = true } }
    private val directoryGeneration = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var closed = false
    private val openingGeneration = java.util.concurrent.atomic.AtomicLong()
    private val playbackChangeQueued = java.util.concurrent.atomic.AtomicBoolean()
    private var runtime: MediaRuntime? = null
    private var player: Player? = null
    private var frames: SharedBuffer? = null
    private var handoff: SongHandoff? = null
    private var videos = emptyList<Path>()
    private var artistsByTitle = emptyMap<String, Set<String>>()
    private var library = emptyList<MediaItem>()
    private var inferredSongs = emptyMap<Path, MediaItem?>()
    val onlineStore = MvOnlineStore(store.onlineFile())
    private val account: BiliLogin = BiliLogin(store.onlineFile().resolveSibling("bilibili-account.dat")) { value -> update { it.copy(login = value) }; if (value?.finished == true) dispatch { bili.clear() } }
    private val bili: BiliApi = BiliApi { account.cookie() }
    private val network = Executors.newSingleThreadExecutor { Thread(it, "SPW MV danmaku").apply { isDaemon = true } }
    private val danmakuPending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val danmakuCache = java.util.concurrent.ConcurrentHashMap<String, List<BiliDanmaku>>()
    private val danmakuPublishLock = Any()
    private var publishedDanmakuCid = 0L
    private var publishedDanmakuSegment = -1
    private var publishedDanmakuPackets = emptyList<List<BiliDanmaku>?>()
    private var lastScan = 0L
    val artwork = MvArtwork(pluginId, store.cacheDirectory())
    private var track: MediaItem? = null
    private var match = MvMatch(message = "请先播放歌曲")
    private var lastTrackPoll = 0L
    private var dismissedTrack: String? = null
    private var pendingAuto: Pair<Path, MediaItem>? = null
    private var volume = 80.0
    private var speed = 1.0
    private var boost: Double? = null
    private val requestedVolume = java.util.concurrent.atomic.AtomicReference<Double?>()
    private val requestedSeek = java.util.concurrent.atomic.AtomicReference<MvSeekIntent?>()
    private val requestedPause = java.util.concurrent.atomic.AtomicReference<MvPauseIntent?>()
    private var pausePreview: Pair<Boolean, Long>? = null
    private var seekPreview: Pair<MvSeekIntent, Long>? = null
    private var endHandled = false
    private var userPaused = false
    init {
        dispatch { store.backupPath()?.let { onlineStore.setMirror(it, initialize = false) }; store.folder()?.let { scan(it) }; updateTrack(); refreshMatch(false); rebuildPlaylist() }
        worker.scheduleWithFixedDelay({ if (!closed) try { tick() } catch (error: Exception) { failed(error) } }, 500, 300, TimeUnit.MILLISECONDS)
    }
    private fun update(change: (MvScreenState) -> MvScreenState) = edt { if (!closed) screen.value = change(screen.value) }
    fun dispatch(action: () -> Unit) {
        if (closed) return
        try { worker.execute { if (!closed) try { action() } catch (error: Exception) { failed(error) } catch (error: LinkageError) { failed(error) } } }
        catch (_: RejectedExecutionException) { }
    }
    private fun failed(error: Throwable) {
        val detail = MediaRuntimeException.unwrap(error)?.detail
        val message = if (detail != null) "${detail.code}：${detail.message}" else
            generateSequence(error) { it.cause }.last().let { it.message ?: it.javaClass.simpleName }
        update { it.copy(message = message, busy = false) }
        edt { runCatching { WorkshopApi.ui.toast(message, WorkshopApi.Ui.ToastType.Error) } }
        System.err.println("SPW MV: $message")
    }
    fun textAction(kind: String) = dispatch {
        update { it.copy(textRequest = MvTextRequest(kind, current()), message = "") }
        edt { page.panel.value = MvPanel.None }
    }
    fun submitText(request: MvTextRequest, text: String) = dispatch {
        if (edt { screen.value.textRequest !== request }) return@dispatch
        when (request.kind) {
            "words" -> { store.setDanmakuSetting("words",text.trim()); update { it.copy(textRequest = null) } }
            "link" -> {
                val song = requireNotNull(request.track) { "请先选择歌曲" }
                check(sameSong(song, current())) { "歌曲已切换，请重新添加链接" }
                update { it.copy(busy = true, message = "正在解析 B 站链接…") }
                val video = bili.resolve(bili.link(text))
                if (edt { screen.value.textRequest !== request } || !sameSong(song,current())) {
                    update { if (it.textRequest === request) it.copy(textRequest = null, busy = false) else it }
                    return@dispatch
                }
                onlineStore.bind(song, video.link)
                update { it.copy(textRequest = null) }; rebuildPlaylist()
                play(video.link.key, song)
            }
            "backup" -> {
                val path = text.trim().takeIf { it.isNotEmpty() }?.let(::localFile)
                require(path == null || !path.startsWith(store.onlineFile().parent)) { "请选择插件数据目录外的备份文件" }
                onlineStore.setMirror(path); store.setBackupPath(path)
                update { it.copy(textRequest = null, message = "自动备份已设置") }
            }
            "export" -> { onlineStore.export(localFile(text.trim())); update { it.copy(textRequest = null, message = "已导出 ${onlineStore.all().size} 条链接") } }
            "import" -> {
                val count = onlineStore.import(localFile(text.trim()))
                rebuildPlaylist(); updateTrack(false); refreshMatch(false)
                update { it.copy(textRequest = null, message = "已合并导入 $count 条链接") }
            }
        }
    }
    fun login() { account.start() }
    fun cancelLogin() { account.cancel() }
    fun logout() = dispatch { account.logout(); bili.clear(); update { it.copy(message = "已退出 B 站登录") } }
    fun quality(id: Int) = dispatch {
        val state = screen.value
        require(state.online?.qualities?.any { it.id == id } == true) { "画质不可用" }
        play(requireNotNull(state.video), current(), preferred = id, start = state.playback.positionSeconds, paused = state.playback.paused)
    }
    fun danmakuSetting(key: String, value: String) = dispatch {
        store.setDanmakuSetting(key, value)
        update { it.copy(danmakuMessage = "", danmakuRevision = it.danmakuRevision + 1) }
    }
    private fun loadDanmaku(position: Double) {
        val video = screen.value.online ?: return
        if (!danmakuOptions(store).enabled) return
        val index = (position / 360).toInt() + 1
        val last = kotlin.math.ceil(video.duration / 360).toInt().coerceAtLeast(1)
        for (segment in listOf(index, index - 1, index + 1).filter { it in 1..last }) {
            val key = "${video.cid}:$segment"
            if (!danmakuPending.add(key)) continue
            network.execute {
                try {
                    val current = edt { screen.value }
                    if (current.online?.cid != video.cid || kotlin.math.abs((current.playback.positionSeconds / 360).toInt() + 1 - segment) > 1) {
                        danmakuPending.remove(key)
                        return@execute
                    }
                    val comments = bili.danmaku(video.cid, segment)
                    if (edt { screen.value.online?.cid } != video.cid) { danmakuPending.remove(key); return@execute }
                    danmakuCache[key] = comments
                    while (danmakuCache.size > 8) {
                        val removed = danmakuCache.keys.maxByOrNull { kotlin.math.abs((it.substringAfter(':').toIntOrNull() ?: 0) - index) } ?: break
                        danmakuCache.remove(removed); danmakuPending.remove(removed)
                    }
                    publishDanmaku(video.cid)
                } catch (_: Exception) {
                    update { state -> if (state.online?.cid != video.cid) state else state.copy(danmakuMessage = "弹幕加载失败，可在弹幕菜单重试") }
                }
            }
        }
        publishDanmaku(video.cid)
    }
    private fun publishDanmaku(cid: Long) {
        val state = edt { screen.value }
        if (state.online?.cid != cid) return
        val segment = (state.playback.positionSeconds / 360).toInt() + 1
        val packets = (segment - 1..segment + 1).map { danmakuCache["$cid:$it"] }
        val comments = synchronized(danmakuPublishLock) {
            if (publishedDanmakuCid == cid && publishedDanmakuSegment == segment &&
                packets.indices.all { publishedDanmakuPackets.getOrNull(it) === packets[it] }) return
            publishedDanmakuCid = cid; publishedDanmakuSegment = segment; publishedDanmakuPackets = packets
            packets.filterNotNull().flatten().distinctBy { it.id }.sortedBy { it.time }
        }
        update { latest ->
            if (latest.online?.cid != cid || (latest.playback.positionSeconds / 360).toInt() + 1 != segment || latest.danmaku == comments) latest
            else latest.copy(danmaku = comments)
        }
    }
    private fun clearDanmaku() {
        danmakuPending.clear(); danmakuCache.clear()
        synchronized(danmakuPublishLock) { publishedDanmakuCid = 0L; publishedDanmakuSegment = -1; publishedDanmakuPackets = emptyList() }
    }
    fun retryDanmaku() = dispatch { clearDanmaku(); loadDanmaku(screen.value.playback.positionSeconds) }
    fun open() {
        val request = openingGeneration.get()
        dispatch {
        if (request != openingGeneration.get()) return@dispatch
        lastScan = 0L
        refreshVideos()
        updateTrack(false); refreshMatch(false)
        val song = requireNotNull(track) { "请先选择歌曲" }
        onlineStore.bound(song)?.let { play(it.link.key, song, request); return@dispatch }
        if (store.blocked(song)) {
            update { it.copy(linkRequest = MvLinkRequest(song, emptyList())) }; return@dispatch
        }
        if (match.file == null && match.candidates.isNotEmpty() && !store.blocked(song)) {
            update { it.copy(linkRequest = MvLinkRequest(song, match.candidates)) }; return@dispatch
        }
        val file = match.file ?: run {
            update { it.copy(linkRequest = MvLinkRequest(song, emptyList())) }
            return@dispatch
        }
        if (store.bound(song) != file) { saveBinding(file, song, automatic = file == match.file); refreshMatch(false) }
        play(file, song, request)
        }
    }
    private fun chooseVideo(song: MediaItem, playAfter: Boolean): Path? {
        if (pickVideo != null) return pickVideo.invoke(song)
        if (store.folder() != null) {
            refreshVideos(); update { it.copy(browser = MvBrowseRequest(song, playAfter), linkRequest = null) }
            return null
        }
        return edt { MvFiles.video(song, null) }
    }
    fun chooseFolder() = browseDirectory(store.folder() ?: Path.of(System.getProperty("user.home")))
    fun chooseBackupDirectory(current: String) {
        edt { screen.value.textRequest?.let { screen.value = screen.value.copy(textRequest = it.copy(initial = current)) } }
        val initial = current.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(it).toAbsolutePath().normalize().parent }.getOrNull() }
        browseDirectory(initial ?: Path.of(System.getProperty("user.home")))
    }
    fun browseDirectory(path: Path) {
        if (closed) return
        val normalized = path.toAbsolutePath().normalize()
        val generation = directoryGeneration.incrementAndGet()
        update { it.copy(directoryBrowser = MvDirectoryRequest(normalized)) }
        try { directories.execute {
            val result = runCatching { directoryFolders(normalized) }
            if (directoryGeneration.get() == generation) update { it.copy(directoryBrowser = MvDirectoryRequest(normalized,
                result.getOrDefault(emptyList()), false, result.exceptionOrNull()?.message.orEmpty())) }
        } } catch (_: RejectedExecutionException) { }
    }
    fun cancelDirectory() { directoryGeneration.incrementAndGet(); update { it.copy(directoryBrowser = null) } }
    fun confirmDirectory() {
        val request = edt { screen.value.directoryBrowser } ?: return
        if (request.busy || request.error.isNotEmpty()) return
        val text = edt { screen.value.textRequest }
        if (text?.kind in setOf("export", "backup")) {
            val name = runCatching { Path.of(text!!.initial).fileName?.toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "Music Video for SPW backup.json"
            directoryGeneration.incrementAndGet()
            update { it.copy(directoryBrowser = null, textRequest = text!!.copy(initial = request.path.resolve(name).toString())) }
        } else { cancelDirectory(); folder(request.path) }
    }
    fun selectVideo(playAfter: Boolean) = dispatch {
        val song = requireNotNull(current()) { "请先选择歌曲" }
        val file = chooseVideo(song, playAfter) ?: return@dispatch
        bind(file, song, playAfter)
    }
    fun addOnlineFromPrompt(request: MvLinkRequest, text: String) = dispatch {
        if (edt { screen.value.linkRequest !== request }) return@dispatch
        val song = request.track
        check(sameSong(song, current())) { "歌曲已切换，请重新添加链接" }
        update { it.copy(busy = true, message = "正在解析 B 站链接…") }
        val video = bili.resolve(bili.link(text))
        if (edt { screen.value.linkRequest !== request } || !sameSong(song, current())) return@dispatch
        onlineStore.bind(song, video.link)
        update { it.copy(linkRequest = null, busy = false, message = "") }
        rebuildPlaylist()
        play(video.link.key, song)
    }
    fun unbindCurrent() = dispatch { current()?.let { onlineStore.bind(it, null); bind(null, it, false) } ?: error("请先选择歌曲") }
    fun bindToCurrent(file: Path) = dispatch {
        val song = requireNotNull(current()) { "请先选择要链接的歌曲" }
        val online = screen.value.queue.firstOrNull { it.file == file }?.online
        if (online != null) { onlineStore.bind(song, online); rebuildPlaylist() } else bind(file, song, false)
    }
    fun unlink(entry: MvQueueEntry) = dispatch {
        if (entry.online != null) { entry.track?.let { onlineStore.bind(it, null) }; rebuildPlaylist(); return@dispatch }
        entry.track?.let { store.bind(it, null) }
        refreshMatch(false); rebuildPlaylist()
    }
    fun resumeMatching() = dispatch {
        val song = requireNotNull(current()) { "请先选择歌曲" }
        store.enableMatching(song); refreshVideos(); updateTrack(false); refreshMatch(false)
    }
    fun cancelLink() = edt { screen.value = screen.value.let { it.copy(linkRequest = null, browser = null, textRequest = null,
        busy = if (it.textRequest != null || it.linkRequest != null) false else it.busy) }; page.panel.value = MvPanel.None }
    fun confirmBrowser(file: Path) {
        val request = edt { screen.value.browser } ?: return
        require(file in edt { screen.value.videos }) { "MV 目录已变化，请刷新后重新选择" }
        cancelLink(); bind(file, request.track, request.playAfter)
    }
    fun confirmLink(file: Path) {
        val request = edt { screen.value.linkRequest } ?: return
        require(file in request.candidates)
        cancelLink(); bind(file, request.track, true)
    }
    fun replaceLink() = dispatch {
        val song = requireNotNull(current()) { "请先选择歌曲" }
        refreshVideos()
        if (pickVideo == null && store.folder() != null) update { it.copy(browser = MvBrowseRequest(song, true), linkRequest = null) }
        else selectVideo(true)
    }
    fun refresh() = dispatch { store.backupPath()?.let { onlineStore.setMirror(it, initialize = false) }; store.folder()?.let { scan(it) }; updateTrack(); refreshMatch(false); rebuildPlaylist() }
    fun playbackChanged() {
        if (closed || !playbackChangeQueued.compareAndSet(false, true)) return
        dispatch { playbackChangeQueued.set(false); if (!updateTrack()) tick() }
    }
    fun refreshPlaylist() = dispatch {
        refreshVideos()
        rebuildPlaylist()
    }
    private fun rebuildPlaylist() {
        val owners = store.bindings()
        val associations = library.associateBy(::trackKey)
        val songs = owners.entries.associate { it.value to associations[it.key] }
        val files = (videos + listOfNotNull(screen.value.video?.takeIf { it.isAbsolute })).distinct()
        val entries = files.map { file -> MvQueueEntry(file, songs[file], inferredSongs[file]) } + onlineStore.all().map { record -> MvQueueEntry(record.link.key, record.song, online = record.link) }
        update { it.copy(queue = entries) }
    }
    fun order(value: String) = dispatch {
        require(value in listOf("Circle", "CircleEnd", "RepeatOne", "Random"))
        store.setPlaybackOrder(value)
        update { it.copy(order = value) }
    }
    fun navigate(previous: Boolean) {
        val request = openingGeneration.get()
        dispatch {
            if (request != openingGeneration.get()) return@dispatch
            refreshVideos(); rebuildPlaylist()
            nextMv(screen.value.queue.map { it.file }, screen.value.video, previous, store.playbackOrder())?.let { play(it, current(), request) }
        }
    }
    fun select(entry: MvQueueEntry) {
        val request = openingGeneration.get()
        dispatch {
        if (request != openingGeneration.get()) return@dispatch
        refreshVideos(); rebuildPlaylist()
        check(entry.file in screen.value.queue.map { it.file } && (entry.online != null || java.nio.file.Files.isRegularFile(entry.file))) { "MV 已移走，请刷新列表" }
        updateTrack(false)
        play(entry.file, current(), request)
        }
    }
    private fun scan(folder: Path) {
        update { it.copy(busy = true, message = "正在扫描 MV 文件夹…") }
        val scanned = scanVideos(folder)
        library = WorkshopApi.library.getAllTracks().toCompletableFuture().get(15, TimeUnit.SECONDS)
        videos = scanned
        indexSongs()
        update { it.copy(folder = folder.toString(), videos = scanned, busy = false, message = "已扫描 ${scanned.size} 个 MV") }
        rebuildPlaylist()
    }
    private fun refreshVideos() {
        if (System.nanoTime() - lastScan < TimeUnit.SECONDS.toNanos(2)) return
        lastScan = System.nanoTime()
        val folder = store.folder() ?: return
        val scanned = scanVideos(folder)
        if (scanned != videos) {
            videos = scanned
            indexSongs()
            update { it.copy(folder = folder.toString(), videos = scanned) }
            rebuildPlaylist()
        }
    }
    private fun indexSongs() {
        artistsByTitle = library.groupBy { mvName(songTitle(it.title)) }.mapValues { (_, songs) -> songs.flatMap(::songArtists).toSet() }
        inferredSongs = videos.associateWith { videoSong(it, library, artistsByTitle) }
        for (song in library) {
            val file = store.bound(song) ?: continue
            val correct = inferredSongs[file]
            if (store.automatic(song) && (artistConflict(song, file, artistsByTitle[mvName(songTitle(song.title))].orEmpty()) ||
                    (correct != null && !sameSong(correct,song)))) {
                store.forgetAutomatic(song)
                if (correct != null && !store.blocked(correct) && store.bound(correct) == null) store.bind(correct, file, automatic = true)
            }
        }
    }
    fun folder(path: Path) = dispatch {
        // Finish validation before replacing the saved folder or its current index.
        scan(path)
        store.saveFolder(path)
        dismissedTrack = null
        updateTrack(false); refreshMatch(false)
        edt { runCatching { WorkshopApi.ui.toast("MV 文件夹已设置，找到 ${videos.size} 个视频", WorkshopApi.Ui.ToastType.Success) } }
    }
    fun autoOpen(value: Boolean) = dispatch {
        settings?.let { it.set("auto_open", value); check(it.save()) { "无法保存 MV 配置" } }
        store.setAutoOpen(value); update { it.copy(autoOpen = value) }
        if (!value) pendingAuto = null
        if (value) { dismissedTrack = null; refreshMatch(true) }
    }
    private fun current() = WorkshopApi.playback.getCurrentMediaItem()
    private fun updateTrack(autoplay: Boolean = true): Boolean {
        val now = current()
        if (sameSong(now, track) || (now == null && track == null)) {
            if (now != null) { track = now; update { it.copy(track = now) } }
            return false
        }
        releasePlayer(restore = false, closePage = !store.autoOpen())
        pendingAuto = null
        track = now; dismissedTrack = null
        refreshVideos()
        update { it.copy(track = now, message = "", linkRequest = null, browser = null) }
        refreshMatch(autoplay)
        return true
    }
    private fun refreshMatch(autoplay: Boolean) {
        val song = track
        match = if (song == null) MvMatch(message = "请先在 SPW 中选择一首歌曲")
            else if (store.blocked(song) && store.bound(song) == null) MvMatch(message = "本曲已停用自动匹配，点击 MV 可恢复")
            else matchMv(song, videos.filterNot { file -> store.claimedElsewhere(song, file) || inferredSongs[file]?.let { !sameSong(it,song) } == true }, store.bound(song)?.takeUnless { store.claimedElsewhere(song, it) }, artistsByTitle[mvName(songTitle(song.title))].orEmpty())
        update { it.copy(match = match) }
        if (pendingAuto != null) pendingAuto = if (song != null && match.file != null && store.autoOpen()) match.file!! to song else null
        if (autoplay && song != null && store.autoOpen() && dismissedTrack != trackKey(song) && onlineStore.bound(song) != null) {
            play(onlineStore.bound(song)!!.link.key, song); return
        }
        if (autoplay && song != null && store.autoOpen() && dismissedTrack != trackKey(song) && match.file != null) {
            if (store.bound(song) != match.file) saveBinding(match.file, song, automatic = true)
            if (MediaRuntime.isInstalled()) play(match.file!!, song)
            else {
                pendingAuto = match.file!! to song
                update { it.copy(message = "等待 SPW Media Runtime 启用；连接就绪后自动打开 MV") }
            }
        }
    }
    private fun saveBinding(path: Path?, expected: MediaItem, automatic: Boolean = false) {
        check(sameSong(expected, current())) { "歌曲已切换，请为当前歌曲重新绑定" }
        val previousOnline = if (path != null) onlineStore.bound(expected) else null
        if (previousOnline != null) onlineStore.bind(expected, null)
        try { store.bind(expected, path, automatic) }
        catch (failure: Exception) {
            if (previousOnline != null) try { onlineStore.bind(previousOnline.song, previousOnline.link) }
            catch (rollback: Exception) {
                val report = IllegalStateException("本地关联失败且原在线链接恢复失败，请从备份导入")
                report.addSuppressed(failure); report.addSuppressed(rollback)
                throw report
            }
            throw failure
        }
        rebuildPlaylist()
        check(sameSong(expected, current())) { "原歌曲的绑定已保存，但歌曲已切换，已取消 MV 打开" }
    }
    fun bind(path: Path?, expected: MediaItem, playAfter: Boolean = true) {
        val request = openingGeneration.get()
        dispatch {
        saveBinding(path, expected)
        track = expected; dismissedTrack = null
        if (playAfter && player != null) releasePlayer(restore = true)
        refreshMatch(false)
        if (playAfter && path != null) play(path, requireNotNull(track), request)
        update { it.copy(message = if (path == null) "已解除链接，本曲不再自动匹配" else "已保存链接；下次直接播放此 MV") }
        if (!playAfter) edt { runCatching { WorkshopApi.ui.toast(if (path == null) "已解除 ${expected.title} 的手动绑定" else "已为 ${expected.title} 绑定 ${path.fileName}", WorkshopApi.Ui.ToastType.Success) } }
        }
    }
    private fun play(file: Path, song: MediaItem?, generation: Long = openingGeneration.get(), preferred: Int = 0, start: Double = 0.0, paused: Boolean = false) {
        pendingAuto = null
        fun stillOpening() = !closed && openingGeneration.get() == generation && (sameSong(song, current()) || (song == null && current() == null))
        if (!stillOpening()) return
        if (preferred == 0 && screen.value.video == file && player != null) {
            edt { if (!closed && generation == openingGeneration.get()) page.show() }
            return
        }
        val keepHandoff = edt { page.isOpen } && sameSong(handoff?.track, song)
        releasePlayer(restore = true, closePage = false, retainHandoff = keepHandoff)
        update { it.copy(busy = true, video = file, message = "正在打开 MV…") }
        edt { if (!closed && generation == openingGeneration.get()) page.show() }
        try {
            check(MediaRuntime.isInstalled()) { "请先导入并启用安装套件中的 SPW Media Runtime 0.2.0，再点击打开匹配 MV" }
            val media = runtime ?: MediaRuntime.connect(pluginId).also { runtime = it }
            check(media.capabilities().any { it.id == "playback" && it.available }) { "视频能力不可用，请启用 SPW Media Runtime 0.2.0" }
            val link = onlineStore.all().firstOrNull { it.link.key == file }?.link
            val online = link?.let { bili.resolve(it) }
            clearDanmaku()
            val quality = online?.qualities?.firstOrNull { it.id == preferred } ?: online?.qualities?.firstOrNull()
            val streamOptions = if (quality != null) buildMap {
                put("referrer", "https://www.bilibili.com/"); put("user-agent", BiliApi.USER_AGENT)
                quality.audio?.let { put("audio-files", it) }
                put("cache", "yes"); put("demuxer-readahead-secs", "8")
            } else emptyMap()
            update { it.copy(online = online, quality = quality?.id ?: 0, danmaku = emptyList(), danmakuMessage = "") }
            val task = media.playback.open(PlayerOptions(frameWidth = 1280, frameHeight = 720,
                options = mapOf("pause" to "yes", "keep-open" to "yes", "volume" to volume.toString(), "speed" to speed.toString(), "start" to start.toString()) + streamOptions))
            val created = try { task.result.toCompletableFuture().get(20, TimeUnit.SECONDS) }
                catch (error: Exception) { task.cancel(); throw error }
            player = created
            check(stillOpening()) { "已取消 MV 打开" }
            created.load(quality?.video ?: file.toString()).toCompletableFuture().get(20, TimeUnit.SECONDS)
            applyAspect(created, store.aspect())
            val buffer = created.frames(); frames = buffer
            val waitSeconds = if (online == null) 8L else 25L
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds)
            while (buffer.latest() == null || (online != null && created.state().durationSeconds <= 0)) {
                check(stillOpening()) { "歌曲已切换或已取消 MV 打开" }
                val snapshot = created.snapshot()
                snapshot.error?.let { error("${it.code}：${it.message}") }
                check(System.nanoTime() < deadline) { "${waitSeconds} 秒内未收到画面，请检查视频或网络" }
                Thread.sleep(40)
            }
            check(stillOpening()) { "歌曲已切换或已取消 MV 打开" }
            if (song != null && handoff == null) {
                val original = edt { clock() }
                handoff = SongHandoff(song, original.first, original.second)
            }
            if (song != null) edt { check(sameSong(song, current())) { "歌曲已切换，已取消 MV 打开" }; WorkshopApi.playback.pause() }
            val pauseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (song != null && edt { clock().second }) {
                check(stillOpening()) { "歌曲已切换或已取消 MV 打开" }
                check(System.nanoTime() < pauseDeadline) { "SPW 未暂停，已取消 MV 播放以避免双重声音" }
                Thread.sleep(25)
            }
            if (!paused) created.play().toCompletableFuture().get(5, TimeUnit.SECONDS)
            check(stillOpening()) { "已取消 MV 打开" }
            dismissedTrack = null
            update { it.copy(video = file, frames = buffer, playback = created.state(), busy = false, message = "正在播放 MV 原声；关闭后恢复歌曲") }
            rebuildPlaylist()
        } catch (error: Throwable) {
            releasePlayer(restore = true)
            // A removed/restarted Runtime must negotiate a fresh connection on the next attempt.
            runCatching { runtime?.close() }; runtime = null
            if (generation != openingGeneration.get()) return
            throw error
        }
    }
    private fun tick() {
        if (System.nanoTime() - lastTrackPoll > TimeUnit.MILLISECONDS.toNanos(1200)) {
                settings?.let { config ->
                    if (config.reload()) {
                        val visible = config.get("show_sidebar", true)
                        if (visible != sidebarVisible.value) edt { sidebarVisible.value = visible }
                        val enabled = config.get("auto_open", store.autoOpen())
                        if (enabled != store.autoOpen()) { store.setAutoOpen(enabled); pendingAuto = null; update { it.copy(autoOpen = enabled) } }
                    }
                }
            lastTrackPoll = System.nanoTime()
            if ((store.folder() != null || onlineStore.all().isNotEmpty() || store.autoOpen() || edt { page.isOpen }) && updateTrack()) return
        }
        pendingAuto?.takeIf { MediaRuntime.isInstalled() }?.let { (file, song) -> pendingAuto = null; play(file, song); return }
        val playing = player ?: return
        try {
            val snapshot = playing.snapshot()
            check(snapshot.error == null) { "MV 播放器发生错误，请重新打开 MV" }
            val raw = Protocol.json.decodeFromJsonElement(PlayerState.serializer(), snapshot.values)
            loadDanmaku(raw.positionSeconds)
            val mode = store.playbackOrder()
            val preview = seekPreview
            val confirmed = preview == null || kotlin.math.abs(raw.positionSeconds - preview.first.target) <= .08 ||
                (raw.positionSeconds >= preview.first.target && raw.positionSeconds - preview.first.target <= 1.0 &&
                    (preview.first.target >= preview.first.from || raw.positionSeconds < preview.first.from - .08) &&
                    (!raw.paused || (raw.positionSeconds >= raw.durationSeconds - .1 && System.nanoTime() >= preview.second - 1_750_000_000L)))
            var state = if (preview != null && System.nanoTime() < preview.second && !confirmed)
                raw.copy(positionSeconds = preview.first.target) else raw.also { seekPreview = null }
            pausePreview?.let { intent ->
                if (raw.paused == intent.first || System.nanoTime() > intent.second) pausePreview = null
                else state = state.copy(paused = intent.first)
            }
            if (handoff != null && edt { clock().second }) {
                val owned = edt {
                    if (sameSong(handoff?.track, current())) { WorkshopApi.playback.pause(); true } else false
                }
                if (owned) playing.play().toCompletableFuture().get(5, TimeUnit.SECONDS)
            }
            update {
                val seek = requestedSeek.get()?.target
                val pause = requestedPause.get()?.paused
                it.copy(playback = state.copy(positionSeconds = seek ?: state.positionSeconds, paused = pause ?: state.paused), order = mode)
            }
            if (raw.positionSeconds < raw.durationSeconds - .5) endHandled = false
            // shortcut: Runtime 0.2.0 omits EOF events; use keep-open's terminal pause until its SDK exposes EOF.
            if (!endHandled && !userPaused && requestedPause.get() == null && requestedSeek.get() == null && raw.paused && raw.durationSeconds > 0 && raw.positionSeconds >= raw.durationSeconds - .1) {
                endHandled = true
                if (mode == "RepeatOne") {
                    playing.seek(0.0).toCompletableFuture().get(5, TimeUnit.SECONDS)
                    playing.play().toCompletableFuture().get(5, TimeUnit.SECONDS)
                } else if (mode != "CircleEnd" || screen.value.video != screen.value.queue.lastOrNull()?.file) navigate(false)
            }
        } catch (error: Exception) { releasePlayer(restore = true); throw error }
    }
    fun toggle() {
        if (closed || edt { screen.value.frames == null }) return
        edt {
            val state = screen.value.playback
            val restart = state.durationSeconds > 0 && state.positionSeconds >= state.durationSeconds - .1
            val pause = !state.paused && !restart
            requestedPause.set(MvPauseIntent(pause, restart))
            screen.value = screen.value.copy(playback = state.copy(paused = pause, positionSeconds = if (restart) 0.0 else state.positionSeconds),
                seekGeneration = screen.value.seekGeneration + if (restart) 1 else 0)
        }
        dispatch {
            val intent = requestedPause.getAndSet(null) ?: return@dispatch
            val pause = intent.paused
            val active = player ?: return@dispatch
            userPaused = pause; endHandled = false
            pausePreview = pause to (System.nanoTime() + TimeUnit.SECONDS.toNanos(3))
            if (pause) active.pause().toCompletableFuture().get(5, TimeUnit.SECONDS)
            else {
                if (intent.restart) {
                    seekPreview = MvSeekIntent(0.0, edt { screen.value.playback.durationSeconds }) to (System.nanoTime() + TimeUnit.SECONDS.toNanos(2))
                    active.seek(0.0).toCompletableFuture().get(5, TimeUnit.SECONDS)
                }
                active.play().toCompletableFuture().get(5, TimeUnit.SECONDS)
            }
        }
    }
    fun seekBy(seconds: Double) = seek(edt { screen.value.playback.positionSeconds } + seconds)
    fun seek(seconds: Double) {
        if (closed) return
        edt {
            val target = seconds.coerceIn(0.0, screen.value.playback.durationSeconds.coerceAtLeast(0.0))
            requestedSeek.set(MvSeekIntent(target, screen.value.playback.positionSeconds))
            screen.value = screen.value.copy(playback = screen.value.playback.copy(positionSeconds = target), seekGeneration = screen.value.seekGeneration + 1)
            target
        }
        dispatch {
            val value = requestedSeek.getAndSet(null) ?: return@dispatch
            val active = player ?: return@dispatch
            seekPreview = value to (System.nanoTime() + TimeUnit.SECONDS.toNanos(2)); endHandled = false
            active.seek(value.target).toCompletableFuture().get(5, TimeUnit.SECONDS)
        }
    }
    fun speed(value: Double) = dispatch { require(value in 0.25..3.0); player?.setSpeed(boost ?: value)?.toCompletableFuture()?.get(5, TimeUnit.SECONDS); speed = value }
    fun boost(value: Double?) = dispatch {
        require(value == null || value in 0.25..3.0)
        player?.setSpeed(value ?: speed)?.toCompletableFuture()?.get(5, TimeUnit.SECONDS)
        boost = value; update { it.copy(boost = value) }
    }
    fun volume(value: Double) {
        require(value in 0.0..100.0)
        requestedVolume.set(value)
        update { it.copy(volume = value, volumeBeforeMute = value.takeIf { it > 0 } ?: it.volumeBeforeMute) }
        dispatch {
            val latest = requestedVolume.getAndSet(null) ?: return@dispatch
            player?.setVolume(latest)?.toCompletableFuture()?.get(5, TimeUnit.SECONDS); volume = latest
        }
    }
    private fun applyAspect(player: Player, aspect: MvAspect) {
        player.setOption("video-aspect-override", aspect.ratio).toCompletableFuture().get(5, TimeUnit.SECONDS)
        player.setOption("keepaspect", if (aspect == MvAspect.Stretch) "no" else "yes").toCompletableFuture().get(5, TimeUnit.SECONDS)
    }
    fun aspect(value: MvAspect) = dispatch {
        player?.let { applyAspect(it, value) }; store.setAspect(value)
        edt { page.aspect.value = value; page.fillVideo.value = value == MvAspect.Fill }
    }
    fun closeVideo() {
        openingGeneration.incrementAndGet()
        edt { page.hide() }
        dispatch { player?.pause(); updateTrack(false); pendingAuto = null; dismissedTrack = track?.let(::trackKey); releasePlayer(restore = true) }
    }
    fun stopVideo() {
        openingGeneration.incrementAndGet()
        edt { page.dispose() }
        dispatch { pendingAuto = null; dismissedTrack = track?.let(::trackKey); releasePlayer(restore = false) }
    }
    private fun releasePlayer(restore: Boolean, closePage: Boolean = true, retainHandoff: Boolean = false) {
        boost = null
        requestedSeek.set(null); requestedPause.set(null); seekPreview = null; pausePreview = null; endHandled = false; userPaused = false
        if (closePage) edt { page.hide(); page.awaitHidden() }.get(3, TimeUnit.SECONDS)
        val detached = edt {
            if (closePage) page.hide()
            screen.value = screen.value.copy(frames = null, video = null, playback = PlayerState(), boost = null, online = null, danmaku = emptyList())
            page.awaitDetached(frames)
        }
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) { try { action() } catch (e: Exception) { if (failure == null) failure = e else failure!!.addSuppressed(e) } }
        // Disposing the surface must complete before unmapping frames or terminating their producer.
        detached.get(5, TimeUnit.SECONDS)
        attempt { frames?.close(); frames = null }
        attempt { player?.close(); player = null }
        val previous = handoff.takeUnless { retainHandoff }; if (!retainHandoff) handoff = null
        if (restore && previous != null) attempt {
            edt { previous.restore(current(), WorkshopApi.playback::seekTo, WorkshopApi.playback::play) }
        }
        failure?.let { throw it }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        directoryGeneration.incrementAndGet(); directories.shutdownNow(); network.shutdownNow(); account.close()
        openingGeneration.incrementAndGet()
        edt { page.dispose(); screen.value = screen.value.copy(frames = null) }
        worker.execute {
            try { releasePlayer(restore = true) }
            catch (error: Exception) { System.err.println("SPW MV cleanup: ${error.message}") }
            finally { try { runtime?.close() } finally { runtime = null } }
        }
        worker.shutdown()
    }
}
