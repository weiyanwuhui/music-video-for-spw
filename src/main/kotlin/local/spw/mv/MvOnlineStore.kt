package local.spw.mv

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import java.nio.file.*
import kotlinx.serialization.json.*

data class OnlineBinding(val path: String, val title: String, val artist: String, val album: String, val url: String) {
    val song get() = MediaItem(title, artist, album, "", path, id = path)
    val link get() = biliLink(url)
}

/** Portable text is the authoritative link ledger; no cookies or expiring media URLs. */
class MvOnlineStore(private val file: Path) {
    private var records = if (Files.exists(file)) read(file) else emptyList()
    private var mirror: Path? = null
    @Synchronized fun all() = records.toList()
    @Synchronized fun bound(track: MediaItem): OnlineBinding? = records.firstOrNull { trackKey(it.song) == trackKey(track) }
        ?: records.filter { mvName(it.title) == mvName(track.title) && songArtists(it.song) == songArtists(track) && mvName(it.album) == mvName(track.album) }.singleOrNull()
    @Synchronized fun bind(track: MediaItem, link: BiliLink?) {
        if (link == null && bound(track) == null) return
        val next = records.filterNot { trackKey(it.song) == trackKey(track) || (link != null && it.link == link) || it == bound(track) }.toMutableList()
        if (link != null) next += OnlineBinding(track.path, track.title, track.artist, track.album, link.url)
        if (next != records) commit(next)
    }
    @Synchronized fun export(path: Path) {
        val target = path.toAbsolutePath().normalize()
        require(!target.startsWith(file.toAbsolutePath().normalize().parent)) { "请选择插件数据目录外的备份位置" }
        require(!Files.exists(target) || runCatching { read(target) == records }.getOrDefault(false)) { "目标文件已有不同内容，请选择新文件名" }
        write(target, records)
    }
    @Synchronized fun setMirror(path: Path?, initialize: Boolean = true) {
        if (path != null && initialize) {
            require(!Files.exists(path) || read(path) == records) { "此文件已有不同的备份内容，请先导入或选择新的文件名" }
            export(path)
        }
        mirror = path
    }
    @Synchronized fun import(path: Path): Int {
        val incoming = read(path)
        fun identity(item: OnlineBinding) = Triple(mvName(item.title), songArtists(item.song), mvName(item.album))
        val incomingCounts = incoming.groupingBy(::identity).eachCount()
        val next = LinkedHashSet<OnlineBinding>()
        val paths = mutableMapOf<String, OnlineBinding>()
        val links = mutableMapOf<BiliLink, OnlineBinding>()
        val identities = mutableMapOf<Triple<String, Set<String>, String>, MutableSet<OnlineBinding>>()
        fun add(item: OnlineBinding) {
            next.add(item); paths[trackKey(item.song)] = item; links[item.link] = item
            identities.getOrPut(identity(item)) { linkedSetOf() }.add(item)
        }
        fun remove(item: OnlineBinding) {
            if (next.remove(item)) {
                paths.remove(trackKey(item.song)); links.remove(item.link)
                val key = identity(item)
                identities[key]?.let { it.remove(item); if (it.isEmpty()) identities.remove(key) }
            }
        }
        records.forEach(::add)
        for (item in incoming) {
            val key = identity(item)
            val byPath = paths[trackKey(item.song)]
            val byIdentity = if (byPath == null && key.second.isNotEmpty() && incomingCounts[key] == 1) identities[key]?.singleOrNull() else null
            listOfNotNull(byPath, links[item.link], byIdentity).distinct().forEach(::remove)
            add(item)
        }
        val importingMirror = mirror?.toAbsolutePath()?.normalize() == path.toAbsolutePath().normalize()
        commit(next.toList(), if (importingMirror) incoming else records)
        return incoming.size
    }
    private fun commit(next: List<OnlineBinding>, expectedMirror: List<OnlineBinding> = records) {
        // Write the external ledger first: a backup failure must not silently lose the only durable copy.
        val mirrorExisted = mirror?.let(Files::exists) == true
        mirror?.let {
            require(!Files.exists(it) || runCatching { read(it) == expectedMirror }.getOrDefault(false)) { "自动备份已被外部修改，请先导入或选择其他备份文件" }
            write(it, next)
        }
        try { write(file, next) }
        catch (failure: Exception) {
            mirror?.let { try { if (mirrorExisted) write(it, expectedMirror) else Files.deleteIfExists(it) } catch (rollback: Exception) { failure.addSuppressed(rollback) } }
            throw failure
        }
        records = next
    }
    companion object {
        fun read(path: Path): List<OnlineBinding> {
            require(Files.size(path) <= 8 * 1024 * 1024) { "备份超过 8 MB" }
            val root = Json.parseToJsonElement(Files.readString(path).removePrefix("\uFEFF")).jsonObject
            require(root["format"]?.jsonPrimitive?.content == "music-video-for-spw" && root["version"]?.jsonPrimitive?.intOrNull == 1) { "不支持的 MV 备份格式" }
            val entries = root.getValue("songs").jsonArray
            require(entries.size <= 10000) { "备份歌曲过多" }
            val records = entries.map { value ->
                val obj = value.jsonObject
                fun text(key: String) = obj.getValue(key).jsonPrimitive.content.also { require(it.length <= 8192) }
                OnlineBinding(text("path"), text("title"), text("artist"), text("album"), biliLink(text("url")).url).also { localFile(it.path); require(it.title.isNotBlank()) }
            }
            require(records.distinctBy { trackKey(it.song) }.size == records.size && records.distinctBy { it.link }.size == records.size) { "备份含重复歌曲或重复 MV，请先修正" }
            return records
        }
        fun write(path: Path, records: List<OnlineBinding>) {
            val target = path.toAbsolutePath().normalize()
            Files.createDirectories(target.parent)
            val json = buildJsonObject {
                put("format", "music-video-for-spw"); put("version", 1)
                putJsonArray("songs") { for (record in records) add(buildJsonObject {
                    put("path", record.path); put("title", record.title); put("artist", record.artist); put("album", record.album); put("url", record.url)
                }) }
            }
            val stage = Files.createTempFile(target.parent, ".mv-backup-", ".tmp")
            try {
                Files.writeString(stage, Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), json))
                try { Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(stage, target, StandardCopyOption.REPLACE_EXISTING) }
            } finally { Files.deleteIfExists(stage) }
        }
    }
}
