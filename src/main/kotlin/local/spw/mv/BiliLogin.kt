package local.spw.mv

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.sun.jna.platform.win32.Crypt32Util
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import kotlinx.serialization.json.*

data class BiliLoginState(val png: ByteArray? = null, val message: String = "正在获取二维码…", val finished: Boolean = false)
class BiliLogin(private val file: Path, private val changed: (BiliLoginState?) -> Unit) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "SPW Bilibili login").apply { isDaemon = true } }
    private val generation = AtomicLong()
    @Volatile private var cookies: String? = null
    fun cookie(): String {
        cookies?.let { return it }
        return if (Files.exists(file)) {
            require(Files.size(file) <= 64 * 1024) { "B 站登录文件无效，请退出后重新扫码" }
            Crypt32Util.cryptUnprotectData(Files.readAllBytes(file)).decodeToString().also { cookies = it }
        } else "".also { cookies = it }
    }
    private fun request(path: String): Pair<JsonObject, List<String>> {
        val connection = URI("https://passport.bilibili.com/x/passport-login/web/qrcode/$path").toURL().openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 8000; connection.readTimeout = 10000; connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", BiliApi.USER_AGENT)
        connection.setRequestProperty("Referer", "https://www.bilibili.com/")
        try {
            require(connection.responseCode == 200) { "登录服务暂不可用" }
            val bytes = connection.inputStream.use { it.readNBytes(65537) }; require(bytes.size <= 65536)
            val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            require(root["code"]?.jsonPrimitive?.intOrNull == 0) { "登录服务返回错误" }
            val headers = connection.headerFields.filterKeys { it?.equals("Set-Cookie", true) == true }.values.flatten()
            return root.getValue("data").jsonObject to headers
        } finally { connection.disconnect() }
    }
    fun start() {
        val run = generation.incrementAndGet()
        changed(BiliLoginState())
        executor.execute {
            try {
                val data = request("generate").first
                val key = data.getValue("qrcode_key").jsonPrimitive.content
                require(Regex("[A-Za-z0-9]{16,128}").matches(key))
                val url = data.getValue("url").jsonPrimitive.content
                require(URI(url).host in setOf("passport.bilibili.com", "account.bilibili.com") && URI(url).scheme == "https")
                val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 240, 240)
                val image = BufferedImage(240,240,BufferedImage.TYPE_INT_RGB)
                for (y in 0 until 240) for (x in 0 until 240) image.setRGB(x,y,if (matrix[x,y]) 0 else 0xffffff)
                val png = ByteArrayOutputStream().also { ImageIO.write(image,"png",it) }.toByteArray(); image.flush()
                if (generation.get() != run) return@execute
                changed(BiliLoginState(png, "使用哔哩哔哩客户端扫码，并在手机上确认登录"))
                poll(run, key, png, System.nanoTime() + TimeUnit.SECONDS.toNanos(180))
            } catch (_: Exception) { if (generation.get() == run) changed(BiliLoginState(message = "无法获取二维码，请重试", finished = true)) }
        }
    }
    private fun poll(run: Long, key: String, png: ByteArray, deadline: Long) {
        executor.schedule({
            if (generation.get() != run) return@schedule
            if (System.nanoTime() > deadline) { changed(BiliLoginState(message = "二维码已过期，请刷新", finished = true)); return@schedule }
            try {
                val (data, headers) = request("poll?qrcode_key=$key")
                if (generation.get() != run) return@schedule
                when (data.getValue("code").jsonPrimitive.int) {
                    0 -> {
                        val allowed = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid")
                        val value = headers.map { it.substringBefore(';') }.filter { it.substringBefore('=') in allowed }.joinToString("; ")
                        require(value.contains("SESSDATA="))
                        val encrypted = Crypt32Util.cryptProtectData(value.toByteArray())
                        synchronized(this) {
                            if (generation.get() != run) return@schedule
                            Files.createDirectories(file.parent)
                            val stage = Files.createTempFile(file.parent, "bili-login-", ".tmp")
                            try { Files.write(stage, encrypted); Files.move(stage,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); cookies = value }
                            finally { Files.deleteIfExists(stage) }
                        }
                        changed(BiliLoginState(message = "登录成功；画质按账号权限开放", finished = true))
                    }
                    86038 -> changed(BiliLoginState(message = "二维码已失效，请刷新", finished = true))
                    86090 -> { changed(BiliLoginState(png,"扫码成功，请在手机上确认")); poll(run,key,png,deadline) }
                    86101 -> poll(run,key,png,deadline)
                    else -> changed(BiliLoginState(message = "登录状态异常，请刷新", finished = true))
                }
            } catch (_: Exception) { if (generation.get() == run) changed(BiliLoginState(message = "登录请求失败，请刷新", finished = true)) }
        },2,TimeUnit.SECONDS)
    }
    @Synchronized fun cancel() { generation.incrementAndGet(); changed(null) }
    @Synchronized fun logout() { cancel(); Files.deleteIfExists(file); cookies = "" }
    override fun close() { cancel(); executor.shutdownNow(); cookies = "" }
}
