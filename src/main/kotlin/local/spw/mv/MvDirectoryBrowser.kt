package local.spw.mv

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import java.nio.file.*

/** Stay inside SPW: no owned Swing dialog, fullscreen focus switch or shell icon lookup. */
@Composable fun MvDirectoryBrowser(controller: MvController) {
    val request = controller.screen.value.directoryBrowser ?: return
    val colors = NativeUi.palette()
    var text by remember(request.path) { mutableStateOf(request.path.toString()) }
    var invalid by remember(request.path) { mutableStateOf("") }
    val backup = controller.screen.value.textRequest?.kind in setOf("export", "backup")
    MvModal(if (backup) "选择备份文件夹" else "选择 MV 文件夹", "浏览文件夹，或直接输入路径", controller::cancelDirectory, controller::confirmDirectory,
        "选择此文件夹", !request.busy && request.error.isEmpty(), dismissLabel = "取消") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NativeUi.IconControl("上一级文件夹", Modifier.size(36.dp), { PlayerIcon(MvIcon.Back, color = colors.text) }) {
                request.path.parent?.let(controller::browseDirectory)
            }
            NativeUi.SearchInput(text, { text = it }, Modifier.weight(1f).semantics { contentDescription = "文件夹路径" },
                TextStyle(color = colors.text, fontSize = 13.sp))
            NativeUi.Button("前往", quiet = true) {
                runCatching { controller.browseDirectory(Path.of(text)) }.onFailure { invalid = "路径格式不正确" }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NativeUi.Button("用户目录", quiet = true) { controller.browseDirectory(Path.of(System.getProperty("user.home"))) }
            for (root in FileSystems.getDefault().rootDirectories) NativeUi.Button(root.toString(), quiet = true) { controller.browseDirectory(root) }
        }
        when {
            request.busy -> PlayerText("正在读取文件夹…", Modifier.padding(16.dp), color = colors.muted)
            request.error.isNotEmpty() || invalid.isNotEmpty() -> PlayerText(invalid.ifEmpty { request.error }, Modifier.padding(16.dp), color = colors.muted)
            request.folders.isEmpty() -> PlayerText("没有子文件夹，可直接选择当前文件夹", Modifier.padding(16.dp), color = colors.muted)
        }
        NativeUi.LazyRows(Modifier.fillMaxWidth().heightIn(max = 280.dp), request.folders.size) { index ->
            val folder = request.folders[index]
            Row(Modifier.fillMaxWidth().height(44.dp).clickable {
                controller.browseDirectory(folder)
            }.semantics { contentDescription = "打开文件夹 ${folder.fileName}" }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayerIcon(MvIcon.Folder, Modifier.size(20.dp), colors.muted)
                PlayerText(folder.fileName.toString(), color = colors.text)
            }
        }
    }
}
