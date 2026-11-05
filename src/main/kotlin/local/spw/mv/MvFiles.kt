package local.spw.mv

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import java.nio.file.Files

fun directoryFolders(path: Path): List<Path> {
    require(Files.isDirectory(path)) { "文件夹不存在或不可访问" }
    val found = mutableListOf<Path>()
    Files.newDirectoryStream(path).use { stream ->
        var count = 0
        for (entry in stream) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("已取消浏览")
            require(++count <= 100_000) { "目录项目过多，请在路径栏输入更具体的文件夹" }
            if (Files.isDirectory(entry)) found.add(entry.toAbsolutePath().normalize())
        }
    }
    return found.sortedBy { it.fileName?.toString()?.lowercase().orEmpty() }
}

object MvFiles {
    fun video(song: MediaItem, directory: Path?): Path? {
        val picker = JFileChooser(directory?.toFile()).apply {
            dialogTitle = "为 ${song.title} 添加 MV"
            fileFilter = FileNameExtensionFilter("视频文件", "mp4", "mkv", "webm", "mov", "m4v", "avi", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv")
        }
        return if (picker.showOpenDialog(HostBridge.hostWindow()) == JFileChooser.APPROVE_OPTION) picker.selectedFile.toPath() else null
    }
}
