package local.spw.mv

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import java.nio.file.*
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.Properties

data class MvMatch(val file: Path? = null, val message: String, val candidates: List<Path> = emptyList())

enum class MvAspect(val label: String, val ratio: String = "-1") {
    Fit("画面适应"), Fill("裁剪铺满"), Stretch("拉伸铺满"), Wide("16:9", "16:9"), Classic("4:3", "4:3"), Cinema("21:9", "21:9"), Square("1:1", "1:1")
}

fun nextMv(videos: List<Path>, current: Path?, previous: Boolean, order: String): Path? {
    if (videos.isEmpty()) return null
    val index = videos.indexOf(current)
    if (order == "Random" && videos.size > 1) {
        val choices = videos.filter { it != current }
        return choices.random()
    }
    val next = if (index < 0) 0 else index + if (previous) -1 else 1
    return if (order == "CircleEnd") videos.getOrNull(next) else videos[Math.floorMod(next, videos.size)]
}

fun artistNames(value: String) = (listOf(value) + value.split(Regex("(?i)\\s+(?:feat\\.?|ft\\.?|featuring|with|x|×)\\s+|[、,，;&/＆]+")))
    .map(::mvName).filter { it.isNotEmpty() }.toSet()

fun songTitle(value: String): String = value.replace(Regex("(?i)[（(\\[]\\s*(?:feat\\.?|ft\\.?|featuring)\\s+[^）)\\]]*[）)\\]]"), "")
    .replace(Regex("[（(\\[]([^）)\\]]*)[）)\\]]\\s*$")) {
        if (Regex("(?i)live|现场|演唱会|remaster|remix|version|acoustic|伴奏|翻唱|cover|demo|版|official|music video|^mv$|^\\d{3,4}p$|^[248]k$").containsMatchIn(it.groupValues[1])) "" else it.value
    }.replace(Regex("(?i)\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.+$"), "").trim()
fun songArtists(track: MediaItem): Set<String> = artistNames(track.artist) +
    Regex("(?i)(?:feat\\.?|ft\\.?|featuring)\\s+([^）)\\]]+)").findAll(track.title).flatMap { artistNames(it.groupValues[1]) }.toSet()
private fun containsArtist(raw: String, key: String): Boolean {
    val name = mvName(raw)
    if (key.length == 1 && key[0] in 'a'..'z') return raw.split(Regex("[\\p{P}\\p{Z}\\p{S}\\s]+")).any { mvName(it) == key }
    if (key.any { it !in 'a'..'z' } && name.contains(key)) return true
    val latin = raw.split(Regex("[^\\p{IsLatin}0-9]+" )).map(::mvName).filter { it.isNotEmpty() }.take(128)
    if ((1..minOf(6,latin.size)).any { count -> latin.windowed(count).any { it.joinToString("") == key } }) return true
    return key.length >= 7 && key.all { it in 'a'..'z' } && (key.length - 1..key.length + 1).any { size ->
        name.windowed(size).any { oneEditApart(key, it) }
    }
}
fun artistConflict(track: MediaItem, file: Path, peers: Set<String>): Boolean {
    val keys = songArtists(track)
    val raw = artistContext(file)
    return keys.none { containsArtist(raw, it) } && peers.flatMap(::artistNames).filter { it !in keys }.any { containsArtist(raw, it) }
}
private fun artistContext(file: Path) = "${file.parent?.fileName?.toString().orEmpty()} ${file.fileName.toString().substringBeforeLast('.')}"
fun videoSong(file: Path, songs: List<MediaItem>, artistsByTitle: Map<String, Set<String>> =
    songs.groupBy { mvName(songTitle(it.title)) }.mapValues { (_, group) -> group.flatMap(::songArtists).toSet() }): MediaItem? {
    val ranked = songs.mapNotNull { song ->
        rankMv(song, listOf(file), artistsByTitle[mvName(songTitle(song.title))].orEmpty()).firstOrNull()?.takeIf { it.second >= 90 }
            ?.let { song to it.second }
    }.sortedByDescending { it.second }
    return ranked.firstOrNull()?.takeIf { ranked.size == 1 || it.second > ranked[1].second }?.first
}

fun localFile(value: String): Path = (if (value.startsWith("file:", true)) Path.of(java.net.URI(value)) else Path.of(value))
    .toAbsolutePath().normalize()
fun trackKey(track: MediaItem): String = MessageDigest.getInstance("SHA-256")
    .digest(localFile(track.path).toString().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
fun mvName(value: String): String = Normalizer.normalize(
    Normalizer.normalize(Normalizer.normalize(value, Normalizer.Form.NFKC), Normalizer.Form.NFD)
        .replace(Regex("(?<=[A-Za-z])\\p{M}+"), ""), Normalizer.Form.NFC)
    .lowercase(Locale.ROOT).replace(Regex("[\\p{P}\\p{Z}\\p{S}\\p{Cf}\\s]+"), "")
fun videoFile(path: Path) = path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT) in
    setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv")

fun scanVideos(folder: Path): List<Path> {
    require(Files.isDirectory(folder)) { "MV 文件夹不存在或不可读：$folder" }
    val found = mutableListOf<Path>()
    // shortcut: scan at most 100000 directory entries and 20 levels, use a persistent index for larger libraries.
    Files.walk(folder, 20).use { stream ->
        val iterator = stream.iterator()
        var count = 0
        while (iterator.hasNext()) {
            check(!Thread.currentThread().isInterrupted) { "扫描已取消" }
            check(++count <= 100_000) { "文件夹超过十万项，请选择更具体的 MV 文件夹" }
            val path = iterator.next()
            if (videoFile(path) && Files.isRegularFile(path) && Files.isReadable(path)) found.add(path.toAbsolutePath().normalize())
        }
    }
    return found.sortedBy { it.toString().lowercase(Locale.ROOT) }
}

private fun oneEditApart(a: String, b: String): Boolean {
    if (kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0; var j = 0; var edits = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) { i++; j++; continue }
        if (++edits > 1) return false
        if (a.length >= b.length) i++
        if (b.length >= a.length) j++
    }
    return edits + (a.length - i) + (b.length - j) <= 1
}

private fun rankMv(track: MediaItem, videos: List<Path>, sameTitleArtists: Set<String>): List<Triple<Path, Int, Int>> {
    val stem = mvName(localFile(track.path).fileName.toString().substringBeforeLast('.'))
    val baseTitle = songTitle(track.title)
    val title = mvName(baseTitle)
    val fullTitle = mvName(track.title)
    val artist = mvName(track.artist)
    val artistKeys = songArtists(track)
    val rivals = sameTitleArtists.flatMap(::artistNames).filter { it !in artistKeys }
    val live = Regex("(?i)(?<![a-z])live(?![a-z])|现场|演唱会")
    val songLive = live.containsMatchIn(track.title)
    val years = Regex("(?:19|20)\\d{2}").findAll(track.title).map { it.value }.toSet()
    val words = baseTitle.split(Regex("[^a-zA-Z0-9]+" )).filter { it.isNotEmpty() }
    val ranked = videos.mapNotNull { file ->
        val raw = file.fileName.toString().substringBeforeLast('.')
        val name = mvName(raw)
        val context = artistContext(file)
        val hasArtist = artistKeys.any { containsArtist(context, it) }
        if (!hasArtist && rivals.any { containsArtist(context, it) }) return@mapNotNull null
        val parts = raw.split(Regex("[\\p{P}\\p{Z}\\p{S}\\s]+" )).map(::mvName)
        val quoted = Regex("[《「『【“\"]([^》」』】”\"]+)[》」』】”\"]").findAll(raw).map { mvName(it.groupValues[1]) }.toSet()
        val latin = raw.split(Regex("[^a-zA-Z0-9]+" )).filter { it.isNotEmpty() }.take(128)
        val fuzzy = title.length >= 8 && title.all { it in 'a'..'z' || it.isDigit() } && words.isNotEmpty() &&
            (maxOf(1, words.size - 1)..words.size + 1).any { count -> latin.windowed(count).any { oneEditApart(title, mvName(it.joinToString(""))) } }
        val base = when {
            name.isEmpty() -> 0
            name == stem && stem.isNotEmpty() -> 120
            fullTitle.isNotEmpty() && (fullTitle == name || fullTitle in quoted) -> 112
            title.isNotEmpty() && (title in quoted || name == title) -> 108
            title.isNotEmpty() && hasArtist && name in setOf(artist + title, title + artist) -> 106
            title.isNotEmpty() && (title in parts || (words.size > 1 && latin.windowed(words.size).any { mvName(it.joinToString("")) == title })) -> 100
            title.length >= (if (title.any { it.code in 0x3400..0x9fff }) 2 else 3) && name.contains(title) &&
                (!title.all { it in 'a'..'z' } || hasArtist) ->
                80
            fuzzy -> 82
            else -> 0
        }
        if (base == 0) return@mapNotNull null
        val fileLive = live.containsMatchIn(raw)
        val fileYears = Regex("(?:19|20)\\d{2}").findAll(raw).map { it.value }.toSet()
        val primary = artistNames(track.artist.split(Regex("(?i)\\s+(?:feat\\.?|ft\\.?|featuring|with)\\s+|[、,，;&/＆]+")).first()).firstOrNull()
        val rank = base + minOf(20,title.length / 2) + (if (hasArtist) 32 else 0) + (if (!primary.isNullOrEmpty() && containsArtist(context,primary)) 16 else 0) - (if (!hasArtist && rivals.isNotEmpty()) 32 else 0) +
            (if (songLive && fileLive) 12 else if (fileLive != songLive) -70 else 0) +
            (if (years.intersect(fileYears).isNotEmpty()) 6 else if (years.isNotEmpty() && fileYears.isNotEmpty()) -8 else 0)
        val quality = when { name.contains("4k") || name.contains("2160p") -> 3; name.contains("1080p") -> 2; name.contains("720p") -> 1; else -> 0 }
        Triple(file, if (base == 80 && !hasArtist) minOf(rank,89) else rank, quality)
    }
    return ranked.sortedWith(compareByDescending<Triple<Path, Int, Int>> { it.second }.thenByDescending { it.third }.thenBy { it.first.toString() })
}
fun matchMv(track: MediaItem, videos: List<Path>, bound: Path?, sameTitleArtists: Set<String> = emptySet()): MvMatch {
    if (bound != null && Files.isRegularFile(bound) && Files.isReadable(bound)) return MvMatch(bound, "已关联")
    val sorted = rankMv(track, videos, sameTitleArtists)
    val candidates = sorted.map { it.first }
    val best = sorted.firstOrNull()
    val unique = best != null && (sorted.size == 1 || best.second != sorted[1].second || best.third != sorted[1].third)
    val automatic = best?.takeIf { unique && it.second >= 90 }?.first
    return MvMatch(automatic, when { automatic != null -> "已自动匹配"; candidates.isEmpty() -> "未找到 MV，可选择本地视频"; else -> "请确认首选 MV 版本" }, candidates)
}

/** Store associations in plugin config; never change music or video files. */
class MvStore(private val file: Path) {
    fun onlineFile(): Path = file.parent.resolve("mv-online.json")
    @Synchronized fun backupPath(): Path? = values.getProperty("backupPath")?.let(::localFile)
    @Synchronized fun setBackupPath(path: Path?) = update("backupPath", path?.toString())
    @Synchronized fun danmakuSetting(key: String, fallback: String): String = values.getProperty("danmaku.$key", fallback)
    @Synchronized fun setDanmakuSetting(key: String, value: String) = update("danmaku.$key", value)
    fun cacheDirectory(): Path = file.parent.resolve("mv-artwork")
    private var values = Properties().also { if (Files.exists(file)) Files.newInputStream(file).use(it::load) }
    @Synchronized fun folder(): Path? = values.getProperty("folder")?.takeIf { it.isNotBlank() }?.let(::localFile)
    @Synchronized fun autoOpen() = values.getProperty("autoOpen", "false").toBooleanStrict()
    @Synchronized fun controlsAutoHide() = values.getProperty("controlsAutoHide", "true").toBooleanStrict()
    @Synchronized fun videoFill() = values.getProperty("videoFill", "false").toBooleanStrict()
    @Synchronized fun aspect(): MvAspect = runCatching { MvAspect.valueOf(values.getProperty("aspect")) }.getOrDefault(if (videoFill()) MvAspect.Fill else MvAspect.Fit)
    @Synchronized fun playbackOrder() = values.getProperty("mvOrder", "Circle").takeIf { it in setOf("Circle", "CircleEnd", "RepeatOne", "Random") } ?: "Circle"
    @Synchronized fun libraryLayout() = values.getProperty("libraryLayout", "List").takeIf { it in setOf("List", "Grid") } ?: "List"
    @Synchronized fun setLibraryLayout(value: String) { require(value in setOf("List", "Grid")); update("libraryLayout", value) }
    @Synchronized fun hiddenDelay() = values.getProperty("hiddenDelay", "3").toIntOrNull()?.coerceIn(3, 8) ?: 3
    @Synchronized fun doubleClickFull() = values.getProperty("doubleClickFull", "true").toBooleanStrict()
    @Synchronized fun blocked(track: MediaItem) = values.getProperty("blocked.${trackKey(track)}", "false").toBoolean()
    @Synchronized fun enableMatching(track: MediaItem) = update("blocked.${trackKey(track)}", null)
    @Synchronized fun setHiddenDelay(value: Int) { require(value in setOf(3, 5, 8)); update("hiddenDelay", value.toString()) }
    @Synchronized fun setDoubleClickFull(value: Boolean) = update("doubleClickFull", value.toString())
    @Synchronized fun setControlsAutoHide(value: Boolean) = update("controlsAutoHide", value.toString())
    @Synchronized fun setVideoFill(value: Boolean) = update("videoFill", value.toString())
    @Synchronized fun setAspect(value: MvAspect) = update { setProperty("aspect", value.name); setProperty("videoFill", (value == MvAspect.Fill).toString()) }
    @Synchronized fun setPlaybackOrder(value: String) { require(value in setOf("Circle", "CircleEnd", "RepeatOne", "Random")); update("mvOrder", value) }
    @Synchronized fun claimedElsewhere(track: MediaItem, video: Path) = values.stringPropertyNames().any {
        it.startsWith("binding.") && it != "binding.${trackKey(track)}" && localFile(values.getProperty(it)).toString().equals(video.toAbsolutePath().normalize().toString(), true)
    }
    @Synchronized fun bindings(): Map<String, Path> = values.stringPropertyNames().filter { it.startsWith("binding.") }
        .associate { it.removePrefix("binding.") to localFile(values.getProperty(it)) }
    @Synchronized fun bound(track: MediaItem): Path? = values.getProperty("binding.${trackKey(track)}")?.let(::localFile)
    @Synchronized fun automatic(track: MediaItem) = values.getProperty("bindingSource.${trackKey(track)}") != "manual"
    @Synchronized fun forgetAutomatic(track: MediaItem) = update { remove("binding.${trackKey(track)}"); remove("bindingSource.${trackKey(track)}") }
    @Synchronized fun saveFolder(folder: Path) { require(Files.isDirectory(folder)); update("folder", folder.toString()) }
    @Synchronized fun setAutoOpen(value: Boolean) = update("autoOpen", value.toString())
    @Synchronized fun bind(track: MediaItem, video: Path?, automatic: Boolean = false) {
        require(video == null || (videoFile(video) && Files.isRegularFile(video) && Files.isReadable(video))) { "请选择可读的视频文件" }
        update {
            val key = trackKey(track)
            if (video == null) { remove("binding.$key"); remove("bindingSource.$key"); setProperty("blocked.$key", "true") }
            else {
                val path = video.toAbsolutePath().normalize().toString()
                stringPropertyNames().filter { it.startsWith("binding.") && localFile(getProperty(it)).toString().equals(path, true) }.forEach {
                    remove(it); remove("bindingSource.${it.removePrefix("binding.")}")
                }
                setProperty("binding.$key", path); remove("blocked.$key")
                setProperty("bindingSource.$key", if (automatic) "auto" else "manual")
            }
        }
    }
    private fun update(key: String, value: String?) = update { if (value == null) remove(key) else setProperty(key, value); Unit }
    private fun update(change: Properties.() -> Unit) {
        val next = Properties().also { it.putAll(values) }
        change(next)
        Files.createDirectories(file.parent)
        val stage = Files.createTempFile(file.parent, "mv-config-", ".tmp")
        try {
            Files.newOutputStream(stage).use { next.store(it, "SPW MV player") }
            // Windows readers and virus scanners can briefly deny replacement; keep the old file intact while retrying.
            for (attempt in 0..6) {
                try { Files.move(stage, file, ATOMIC_MOVE, REPLACE_EXISTING); break }
                catch (error: FileSystemException) { if (attempt == 6) throw error; Thread.sleep(minOf(80L, 5L shl attempt)) }
            }
            values = next
        } finally { Files.deleteIfExists(stage) }
    }
}
