package local.spw.mv

import dev.gaboron.spwmedia.sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.awt.image.BufferedImage
import java.nio.file.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** Load only visible covers off the UI thread; the cache belongs to this plugin, never the media folder. */
class MvArtwork(private val pluginId: String, private val directory: Path) {
    private val permits = Semaphore(2)
    suspend fun load(entry: MvQueueEntry): ByteArray? {
        permits.acquire()
        try { return runInterruptible(Dispatchers.IO) {
        val video = entry.file
        if ((entry.online == null && !Files.isRegularFile(video)) || !MediaRuntime.isInstalled()) return@runInterruptible null
        val audio = entry.displayTrack?.path?.let(::localFile)?.takeIf(Files::isRegularFile)
        val stamp = listOfNotNull(video.takeIf(Files::isRegularFile), audio).joinToString { "$it:${Files.size(it)}:${Files.getLastModifiedTime(it)}" }
        val key = MessageDigest.getInstance("SHA-256").digest(stamp.toByteArray()).joinToString("") { "%02x".format(it) }
        Files.createDirectories(directory)
        val cached = directory.resolve("$key.png")
        if (Files.isRegularFile(cached)) return@runInterruptible Files.readAllBytes(cached)
        val output = Files.createTempFile(directory, "frame-", ".png")
        Files.delete(output)
        try {
            MediaRuntime.connect(pluginId).use { media ->
                val cover = audio != null && runCatching {
                    val task = media.metadata.extractArtwork(audio, output)
                    try { task.result.toCompletableFuture().get(12, TimeUnit.SECONDS); true }
                    catch (error: Exception) { task.cancel(); false }
                }.getOrDefault(false)
                if (!cover && entry.online != null) return@runInterruptible null
                if (!cover) {
                    val task = media.processing.extractFrame(video, output, 5.0)
                    try { task.result.toCompletableFuture().get(15, TimeUnit.SECONDS) }
                    catch (error: Exception) { task.cancel(); throw error }
                }
            }
            val source = ImageIO.read(output.toFile()) ?: return@runInterruptible null
            val scale = minOf(1.0, 256.0 / maxOf(source.width, source.height))
            val image = BufferedImage((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
            image.createGraphics().let { graphics ->
                try {
                    graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    graphics.drawImage(source, 0, 0, image.width, image.height, null)
                } finally { graphics.dispose(); source.flush() }
            }
            ImageIO.write(image, "png", output.toFile()); image.flush()
            Files.move(output, cached, StandardCopyOption.REPLACE_EXISTING)
            // shortcut: keep 192 small covers on disk; use a shared media index if large libraries need more.
            Files.list(directory).use { stream -> stream.filter { it.fileName.toString().matches(Regex("[0-9a-f]{64}\\.png")) }
                .sorted(compareByDescending<Path> { Files.getLastModifiedTime(it) }).skip(192).forEach { runCatching { Files.deleteIfExists(it) } } }
            Files.readAllBytes(cached)
        } finally { Files.deleteIfExists(output) }
        } } finally { permits.release() }
    }
}
