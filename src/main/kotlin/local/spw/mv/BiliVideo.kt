package local.spw.mv

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.serialization.json.*

data class BiliLink(val id: String, val page: Int = 1) {
    val url get() = "https://www.bilibili.com/video/$id?p=$page"
    val key get() = java.nio.file.Path.of("bilibili", "$id-$page.bili")
}
fun biliLink(text: String): BiliLink {
    val raw = text.trim()
    if (Regex("BV[0-9A-Za-z]{10}|av[0-9]+", RegexOption.IGNORE_CASE).matches(raw)) return BiliLink(raw)
    val uri = URI(raw)
    require(uri.scheme == "https" && uri.host?.lowercase() in setOf("www.bilibili.com", "bilibili.com", "m.bilibili.com")) { "请输入 B 站视频 HTTPS 链接或 BV / av 号" }
    val id = Regex("^/video/(BV[0-9A-Za-z]{10}|av[0-9]+)(?:/|$)", RegexOption.IGNORE_CASE).find(uri.path)?.groupValues?.get(1)
        ?: error("目前支持 B 站普通视频链接")
    val pageValue = uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("p=") }?.substringAfter('=')
    val page = if (pageValue == null) 1 else requireNotNull(pageValue.toIntOrNull()) { "分 P 编号无效" }
    require(page in 1..10000) { "分 P 编号无效" }
    return BiliLink(id, page)
}
data class BiliQuality(val id: Int, val label: String, val video: String, val audio: String?)
data class BiliVideo(val link: BiliLink, val cid: Long, val title: String, val duration: Double, val qualities: List<BiliQuality>)

/** Only public Bilibili endpoints are accepted; CDN addresses are never persisted. */
class BiliApi(private val cookie: () -> String = { "" }) {
    private var mixin: Pair<String, Long>? = null
    private val streams = LinkedHashMap<String, Pair<Long, BiliVideo>>()
    fun bytes(url: String, limit: Int = 8 * 1024 * 1024): ByteArray {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host in setOf("api.bilibili.com", "b23.tv")) { "不支持的请求地址" }
        val connection = uri.toURL().openConnection() as java.net.HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 8000; connection.readTimeout = 12000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Referer", "https://www.bilibili.com/")
        if (uri.host == "api.bilibili.com") cookie().takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Cookie", it) }
        try {
            require(connection.responseCode == 200) { "B 站请求失败（HTTP ${connection.responseCode}）" }
            return connection.inputStream.use { it.readNBytes(limit + 1).also { data -> require(data.size <= limit) { "B 站响应过大" } } }
        } finally { connection.disconnect() }
    }
    fun link(text: String): BiliLink {
        val url = Regex("https://(?:www\\.|m\\.)?bilibili\\.com/video/[^\\s]+|https://b23\\.tv/[A-Za-z0-9]+", RegexOption.IGNORE_CASE).find(text)?.value ?: text
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        if (uri?.host == "b23.tv" && uri.scheme == "https") {
            val connection = uri.toURL().openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = false; connection.connectTimeout = 8000; connection.readTimeout = 8000
            try { require(connection.responseCode in 300..399) { "B 站短链接未返回视频地址" }; return biliLink(connection.getHeaderField("Location")) }
            finally { connection.disconnect() }
        }
        return biliLink(url)
    }
    private fun json(path: String) = Json.parseToJsonElement(bytes("https://api.bilibili.com/$path").decodeToString()).jsonObject
    private fun data(path: String): JsonObject {
        val root = json(path)
        require(root["code"]?.jsonPrimitive?.intOrNull == 0) { "B 站：${root["message"]?.jsonPrimitive?.content ?: "请求失败"}" }
        return root["data"]?.jsonObject ?: error("B 站未返回视频数据")
    }
    fun clear() { streams.clear(); mixin = null }
    fun resolve(link: BiliLink, refresh: Boolean = false): BiliVideo {
        if (!refresh) streams[link.url]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return it.second }
        val argument = if (link.id.startsWith("av", true)) "aid=${link.id.drop(2)}" else "bvid=${link.id}"
        val info = data("x/web-interface/view?$argument")
        val page = info.getValue("pages").jsonArray.getOrNull(link.page - 1)?.jsonObject ?: error("此视频没有第 ${link.page} P")
        val cid = page.getValue("cid").jsonPrimitive.long
        val params = mapOf("bvid" to info.getValue("bvid").jsonPrimitive.content, "cid" to "$cid", "fnval" to "4048", "fnver" to "0", "fourk" to "1", "qn" to "127")
        var result = data("x/player/wbi/playurl?${signed(params)}")
        if (result["v_voucher"] != null) { mixin = null; result = data("x/player/wbi/playurl?${signed(params)}") }
        val formats = result["support_formats"]?.jsonArray.orEmpty().associate { item -> item.jsonObject.let { it.getValue("quality").jsonPrimitive.int to (it["new_description"] ?: it["display_desc"] ?: it["description"])!!.jsonPrimitive.content } }
        val dash = result["dash"] as? JsonObject
        val audio = (dash?.get("audio") as? JsonArray)?.maxByOrNull { it.jsonObject["bandwidth"]?.jsonPrimitive?.longOrNull ?: 0 }?.jsonObject?.let(::streamUrl)
        val qualities = (dash?.get("video") as? JsonArray).orEmpty().map { it.jsonObject }.sortedBy { if (it["codecid"]?.jsonPrimitive?.intOrNull == 7) 0 else 1 }
            .distinctBy { it.getValue("id").jsonPrimitive.int }.map { BiliQuality(it.getValue("id").jsonPrimitive.int, formats[it.getValue("id").jsonPrimitive.int] ?: "${it["height"]}P", streamUrl(it), audio) }
            .sortedByDescending { it.id }.ifEmpty {
                (result["durl"] as? JsonArray)?.takeIf { it.size == 1 }?.map { BiliQuality(result.getValue("quality").jsonPrimitive.int, "默认画质", it.jsonObject.getValue("url").jsonPrimitive.content, null) }.orEmpty()
            }
        require(qualities.isNotEmpty()) { "未获取可播放画质，请登录 B 站或稍后重试" }
        return BiliVideo(BiliLink(info.getValue("bvid").jsonPrimitive.content, link.page), cid, info.getValue("title").jsonPrimitive.content,
            page.getValue("duration").jsonPrimitive.double, qualities).also { streams[link.url] = System.currentTimeMillis() to it; while (streams.size > 16) streams.remove(streams.keys.first()) }
    }
    fun danmaku(cid: Long, segment: Int) = parseDanmaku(bytes("https://api.bilibili.com/x/v2/dm/web/seg.so?type=1&oid=$cid&segment_index=$segment"))
    private fun signed(values: Map<String, String>): String {
        val key = mixin?.takeIf { System.currentTimeMillis() - it.second < 3600_000 }?.first ?: run {
            val image = json("x/web-interface/nav").getValue("data").jsonObject.getValue("wbi_img").jsonObject
            val raw = listOf("img_url", "sub_url").joinToString("") { image.getValue(it).jsonPrimitive.content.substringAfterLast('/').substringBefore('.') }
            require(raw.length >= 64) { "B 站签名数据无效" }
            MIXIN.take(32).map { raw[it] }.joinToString("").also { mixin = it to System.currentTimeMillis() }
        }
        return wbiQuery(values, key, System.currentTimeMillis() / 1000)
    }
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36"
        private val MIXIN = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13)
    }
}
private fun streamUrl(obj: JsonObject): String {
    val candidates = listOfNotNull((obj["baseUrl"] ?: obj["base_url"])?.jsonPrimitive?.content) +
        ((obj["backupUrl"] ?: obj["backup_url"]) as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
    return candidates.mapNotNull { value ->
        val uri = runCatching { URI(value) }.getOrNull() ?: return@mapNotNull null
        val host = uri.host.orEmpty()
        if (uri.scheme == "https" && listOf(".bilivideo.com", ".bilivideo.cn", ".akamaized.net").any(host::endsWith)) value else null
    }.minByOrNull { if (URI(it).host.contains(".mcdn.")) 1 else 0 } ?: error("未找到受支持的视频 CDN 地址")
}
fun wbiQuery(values: Map<String, String>, key: String, time: Long): String {
    fun encode(value: String) = URLEncoder.encode(value.filter { it !in "!'()*" }, Charsets.UTF_8).replace("+", "%20")
    val query = (values + ("wts" to "$time")).toSortedMap().entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    val hash = MessageDigest.getInstance("MD5").digest((query + key).toByteArray()).joinToString("") { "%02x".format(it) }
    return "$query&w_rid=$hash"
}

data class BiliDanmaku(val id: Long, val time: Double, val mode: Int, val size: Int, val color: Int, val text: String, val user: String, val weight: Int)
private class Proto(private val data: ByteArray) {
    var offset = 0
    fun number(): Long {
        var result = 0L
        for (shift in 0..63 step 7) { require(offset < data.size); val b = data[offset++].toInt() and 255; result = result or ((b and 127).toLong() shl shift); if (b < 128) return result }
        error("弹幕整数编码无效")
    }
    fun fields(action: (Int, Long?, ByteArray?) -> Unit) {
        while (offset < data.size) {
            val tag = number().toInt(); require(tag > 0)
            when (tag and 7) {
                0 -> action(tag ushr 3, number(), null)
                2 -> { val count = number(); require(count in 0..(data.size - offset).toLong()); val end = offset + count.toInt(); val bytes = data.copyOfRange(offset, end); offset = end; action(tag ushr 3, null, bytes) }
                1 -> { require(offset + 8 <= data.size); offset += 8 }
                5 -> { require(offset + 4 <= data.size); offset += 4 }
                else -> error("弹幕字段编码无效")
            }
        }
    }
}
fun parseDanmaku(data: ByteArray): List<BiliDanmaku> {
    require(data.size <= 8 * 1024 * 1024)
    val list = mutableListOf<BiliDanmaku>()
    Proto(data).fields { field, _, bytes -> if (field == 1 && bytes != null) {
        require(list.size < 10000)
        var id = 0L; var time = 0L; var mode = 1; var size = 25; var color = 0xffffff; var text = ""; var user = ""; var weight = 0
        Proto(bytes).fields { f, n, s -> when (f) {
            1 -> id = n ?: 0; 2 -> time = n ?: 0; 3 -> mode = n?.toInt() ?: 1; 4 -> size = n?.toInt() ?: 25; 5 -> color = n?.toInt() ?: 0xffffff
            6 -> user = s?.decodeToString().orEmpty(); 7 -> text = s?.decodeToString().orEmpty(); 9 -> weight = n?.toInt() ?: 0
        } }
        if (time >= 0 && mode in 1..7 && text.isNotBlank() && text.length <= 2000) list += BiliDanmaku(id, time / 1000.0, mode, size.coerceIn(12,72), color and 0xffffff, text, user, weight)
    } }
    return list.sortedBy { it.time }
}
