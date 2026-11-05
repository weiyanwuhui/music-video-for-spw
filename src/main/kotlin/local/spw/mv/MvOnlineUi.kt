package local.spw.mv

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.loadImageBitmap
import java.io.ByteArrayInputStream

@Composable fun MvLoginPrompt(controller: MvController) {
    val state = controller.screen.value.login ?: return
    val bitmap = remember(state.png) { state.png?.let { loadImageBitmap(ByteArrayInputStream(it)) } }
    MvModal("扫码登录 B 站", state.message, controller::cancelLogin, controller::login, "刷新二维码", dismissLabel = "关闭") {
        Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) NativeUi.Image(bitmap, "B 站登录二维码", Modifier.size(240.dp))
            else PlayerText(state.message)
        }
    }
}

@Composable fun MvOnlinePrompt(controller: MvController) {
    val state = controller.screen.value
    val request = state.textRequest ?: return
    val colors = NativeUi.palette()
    val clipboard = LocalClipboardManager.current
    var input by remember(request) { mutableStateOf(request.initial.ifEmpty { when (request.kind) {
        "backup" -> controller.store.backupPath()?.toString().orEmpty()
        "words" -> controller.store.danmakuSetting("words", "")
        else -> ""
    } }) }
    val title = when (request.kind) { "words" -> "屏蔽关键词"; "link" -> "添加 B 站 MV"; "backup" -> "自动备份文件"; "export" -> "导出 MV 链接"; else -> "导入 MV 链接" }
    val description = when (request.kind) {
        "words" -> "输入关键词，用 | 分隔；留空清除。"
        "link" -> "${request.track?.title.orEmpty()} · ${request.track?.artist.orEmpty()}\n粘贴视频链接、分享文本、BV / av 号，支持分 P。"
        "backup" -> "输入插件目录外的 JSON 文本文件完整路径。关联增删会自动更新；留空关闭。已有备份请先导入，防止覆盖。"
        "export" -> "输入 JSON 备份文件路径；已有不同内容不会被覆盖。只包含歌曲信息和 MV 链接，不包含视频或登录信息。"
        else -> "输入备份文件完整路径。按歌曲合并，备份中的同曲关联优先；不删除其他歌曲。路径变化时只接受歌名、歌手和专辑完全一致的唯一匹配。"
    }
    MvModal(title, description, controller::cancelLink, { controller.submitText(request, input) },
        if (request.kind == "link") "关联并播放" else "确认", dismissLabel = "取消", enabled = !state.busy && (input.isNotBlank() || request.kind in setOf("backup","words"))) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().background(colors.text.copy(alpha = .05f), playerShape(8.dp)).padding(14.dp)) {
                NativeUi.SearchInput(input, { input = it }, Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics { contentDescription = title }, TextStyle(color = colors.text, fontSize = 14.sp))
            }
            if (request.kind in setOf("backup", "export", "import")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (request.kind != "import") NativeUi.Button("选择文件夹", Modifier.weight(1f), quiet = true) { controller.chooseBackupDirectory(input) }
                    NativeUi.Button("粘贴路径", Modifier.weight(1f), quiet = true) { clipboard.getText()?.text?.trim()?.trim('"')?.let { input = it } }
                    if (request.kind == "export") NativeUi.Button("使用默认位置", Modifier.weight(1f), quiet = true) {
                        input = java.nio.file.Path.of(System.getProperty("user.home"), "Documents", "Music Video for SPW backup.json").toString()
                    }
                }
            }
            if (state.message.isNotEmpty()) NativeUi.Text(state.message, size = 12, color = colors.muted)
        }
    }
}
