package local.spw.mv

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*

fun mvGridColumns(width: Float) = ((width + 20f) / 216f).toInt().coerceIn(1, 8)

@Composable fun MvGrid(entries: List<MvQueueEntry>, chosen: MvQueueEntry?, controller: MvController,
    modifier: Modifier, select: (MvQueueEntry) -> Unit) {
    val colors = NativeUi.palette()
    BoxWithConstraints(modifier) {
        val columns = mvGridColumns(maxWidth.value)
        val rows = (entries.size + columns - 1) / columns
        NativeUi.LazyRows(Modifier.fillMaxSize(), rows) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                repeat(columns) { column ->
                    val entry = entries.getOrNull(row * columns + column)
                    if (entry == null) Spacer(Modifier.weight(1f))
                    else key(entry.file) {
                        Column(Modifier.weight(1f).graphicsLayer { shadowElevation = 4.dp.toPx(); ambientShadowColor = Color.Black.copy(alpha = .06f); spotShadowColor = Color.Black.copy(alpha = .06f); shape = playerShape(12.dp); clip = false }.clip(playerShape(12.dp))
                            .background(colors.text.copy(alpha = if (chosen?.file == entry.file) .075f else .025f))
                            .clickable(role = Role.Button) { select(entry) }
                            .semantics { selected = chosen?.file == entry.file; contentDescription = "选择 MV ${entry.file.fileName}" }
                            .padding(10.dp)) {
                            MvCover(entry, controller, Modifier.fillMaxWidth().aspectRatio(1f))
                            PlayerText(entry.title, Modifier.padding(top = 12.dp), size = 14)
                            PlayerText(entry.detail, Modifier.padding(top = 5.dp, bottom = 5.dp), size = 11, color = colors.muted)
                        }
                    }
                }
            }
        }
    }
}
