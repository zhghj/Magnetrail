package app.magnetrail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.magnetrail.FileActions
import app.magnetrail.shared.Fmt
import app.magnetrail.shared.TorrentItem
import app.magnetrail.shared.TorrentState

@Composable
fun TasksTab(
    items: List<TorrentItem>,
    linkText: String,
    onLinkTextChange: (String) -> Unit,
    onPaste: () -> Unit,
    onAdd: () -> Unit,
    onWatchOnline: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRemove: (String, Boolean) -> Unit,
    onFileToggle: (String, Int, Boolean) -> Unit,
    onStreamFile: (String, Int, String) -> Unit,
    addError: String?,
    logs: List<String>
) {
    val visibleItems = items.filterNot { it.isTransient }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "add") {
            AddMagnetCard(
                text = linkText,
                onTextChange = onLinkTextChange,
                onPaste = onPaste,
                onAdd = onAdd,
                onWatchOnline = onWatchOnline,
                errorText = addError
            )
        }
        if (visibleItems.isEmpty()) {
            item(key = "empty") { EmptyHint("还没有任务。贴一个磁力链接开始下载。") }
        }
        items(visibleItems, key = { it.infoHash }) { item ->
            TorrentCard(
                item = item,
                onPause = onPause,
                onResume = onResume,
                onRemove = onRemove,
                onFileToggle = onFileToggle,
                onStreamFile = onStreamFile
            )
        }
        if (logs.isNotEmpty()) {
            item(key = "logs") { LogsCard(logs) }
        }
    }
}

@Composable
private fun TorrentCard(
    item: TorrentItem,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRemove: (String, Boolean) -> Unit,
    onFileToggle: (String, Int, Boolean) -> Unit,
    onStreamFile: (String, Int, String) -> Unit
) {
    var expanded by remember(item.infoHash) { mutableStateOf(false) }
    var confirmDelete by remember(item.infoHash) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val stateText = buildString {
                        append(stateLabel(item.state))
                        if (!item.hasMetadata && item.state != TorrentState.PAUSED) append(" · 已连上 peers 才会出信息")
                        if (item.hasMetadata && item.files.isNotEmpty()) {
                            val skipped = item.files.count { !it.wanted }
                            if (skipped > 0) append(" · 仅视频：跳过 $skipped 个非视频文件")
                        }
                        item.error?.let { append(" · $it") }
                    }
                    Text(
                        text = stateText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { if (item.isPaused) onResume(item.infoHash) else onPause(item.infoHash) }) {
                    Icon(
                        imageVector = if (item.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = if (item.isPaused) "继续" else "暂停"
                    )
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "删除")
                }
            }

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { item.progress.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (item.isDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(6.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Fmt.percent(item.progress), style = MaterialTheme.typography.bodySmall)
                Text(
                    "${Fmt.bytes(item.downloadedBytes)} / ${Fmt.bytes(item.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "↓ ${Fmt.speed(item.downloadRate)}   ↑ ${Fmt.speed(item.uploadRate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    "${item.peers} 连接 / ${item.seeds} 种子 · ${Fmt.eta(item.etaSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (item.files.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(4.dp))
                    val skipped = item.files.count { !it.wanted }
                    Text(
                        if (skipped > 0) "文件 (${item.files.size} · 跳过 $skipped)"
                        else "文件 (${item.files.size})"
                    )
                }
                if (expanded) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Column(Modifier.padding(top = 6.dp)) {
                        item.files.take(200).forEach { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        file.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${Fmt.bytes(file.sizeBytes)} · ${Fmt.percent(file.progress)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (FileActions.isVideoName(file.displayName) && file.wanted) {
                                    IconButton(
                                        onClick = {
                                            onStreamFile(item.infoHash, file.index, file.displayName)
                                        }
                                    ) {
                                        Icon(
                                            Icons.Rounded.PlayCircle,
                                            contentDescription = "边下边播",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Switch(
                                    checked = file.wanted,
                                    onCheckedChange = { onFileToggle(item.infoHash, file.index, it) }
                                )
                            }
                        }
                        if (item.files.size > 200) {
                            Text(
                                "还有 ${item.files.size - 200} 个文件…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除任务") },
            text = {
                Text("「${item.name}」\n\n是否同时删除已经下载的文件？")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onRemove(item.infoHash, true)
                }) { Text("删除任务和文件", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmDelete = false }) { Text("取消") }
                    TextButton(onClick = {
                        confirmDelete = false
                        onRemove(item.infoHash, false)
                    }) { Text("仅移除任务") }
                }
            }
        )
    }
}

@Composable
fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(24.dp)
    )
}

@Composable
private fun LogsCard(logs: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            SectionTitle("运行日志（最近 ${minOf(logs.size, 8)} 条）")
            Spacer(Modifier.height(4.dp))
            logs.takeLast(8).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF9BB4CF),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
