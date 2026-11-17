package app.magnetrail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.magnetrail.FileActions
import app.magnetrail.SubtitleBatchJob
import app.magnetrail.shared.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File browser grouped by torrent folder.
 *
 * A torrent with several files lands in `Downloads/<torrent name>/…`, so the first
 * path segment becomes the group ("组/文件夹"); single-file torrents stay in a
 * "（下载根目录）" group.
 */
@Composable
fun FilesTab(
    root: File,
    modeOf: (File) -> FileOpenMode,
    isComplete: (File) -> Boolean,
    onModeChosen: (File, FileOpenMode) -> Unit,
    onOpen: (File) -> Unit,
    onShare: (File) -> Unit,
    onDelete: (File) -> Unit,
    onDeleteGroup: (String, List<File>) -> Unit,
    batch: SubtitleBatchJob,
    canBatch: (File) -> Boolean,
    onStartBatch: (List<File>) -> Unit
) {
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmGroup by remember { mutableStateOf<FileGroup?>(null) }

    LaunchedEffect(root, refresh) {
        files = withContext(Dispatchers.IO) { FileActions.listFiles(root) }
    }
    LaunchedEffect(root) {
        while (true) {
            delay(3_000)
            refresh++
        }
    }

    val groups = remember(files, root) { groupByFolder(root, files) }
    val totalBytes = files.sumOf { it.length() }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${groups.size} 个组 · ${files.size} 个文件 · ${Fmt.bytes(totalBytes)}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        root.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { refresh++ }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                }
            }
        }

        if (files.isEmpty()) {
            item(key = "empty") {
                EmptyHint("下载目录还是空的。任务完成后文件会出现在这里。")
            }
        }

        item(key = "batch") {
            BatchSubtitleCard(
                batch = batch,
                // 只排「已下载完成」的视频：半成品没有完整音频轨，跑也是白跑
                videos = files.filter {
                    it.extension.lowercase() in VIDEO_EXTENSIONS && canBatch(it)
                },
                onStart = onStartBatch
            )
        }

        groups.forEach { group ->
            val expanded = group.name !in collapsed
            item(key = "g:${group.name}") {
                GroupHeader(
                    name = group.name,
                    fileCount = group.files.size,
                    totalBytes = group.files.sumOf { it.length() },
                    expanded = expanded,
                    onToggle = {
                        collapsed = if (expanded) {
                            collapsed + group.name
                        } else {
                            collapsed - group.name
                        }
                    },
                    onDelete = { confirmGroup = group }
                )
            }
            if (expanded) {
                items(group.files, key = { "f:${it.absolutePath}" }) { file ->
                    val complete = isComplete(file)
                    FileRow(
                        file = file,
                        mode = modeOf(file),
                        options = if (complete) COMPLETE_MODES else INCOMPLETE_MODES,
                        onClick = {
                            val mode = modeOf(file)
                            if (FileActions.isPlayable(file) || mode != FileOpenMode.LOCAL) {
                                onModeChosen(file, mode)
                            } else {
                                onOpen(file)
                            }
                        },
                        onModeChosen = { mode -> onModeChosen(file, mode) },
                        onShare = { onShare(file) },
                        onDelete = { onDelete(file) }
                    )
                }
            }
        }
        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }

    confirmGroup?.let { group ->
        val size = group.files.sumOf { it.length() }
        AlertDialog(
            onDismissRequest = { confirmGroup = null },
            title = { Text("删除整组") },
            text = {
                Text(
                    "删除「${group.name}」里的 ${group.files.size} 个文件（${Fmt.bytes(size)}）？\n" +
                        "如果这一组对应一个下载任务，任务会一起移除；此操作不可恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmGroup = null
                    onDeleteGroup(group.name, group.files)
                }) { Text("删除整组", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmGroup = null }) { Text("取消") }
            }
        )
    }
}

private data class FileGroup(val name: String, val files: List<File>)

/** 未下完的文件才提供「下载」；下完的文件只提供「本地播放」。 */
private val INCOMPLETE_MODES = listOf(
    FileOpenMode.DOWNLOAD,
    FileOpenMode.STREAM,
    FileOpenMode.BUFFER
)
private val COMPLETE_MODES = listOf(
    FileOpenMode.LOCAL,
    FileOpenMode.STREAM,
    FileOpenMode.BUFFER
)

private fun groupByFolder(root: File, files: List<File>): List<FileGroup> {
    if (files.isEmpty()) return emptyList()
    val separator = File.separatorChar
    return files
        .groupBy { file ->
            val relative = file.absolutePath.removePrefix(root.absolutePath).trim(separator)
            if (relative.contains(separator)) {
                relative.substringBefore(separator)
            } else {
                "（下载根目录）"
            }
        }
        .map { (name, list) -> FileGroup(name, list.sortedByDescending { it.lastModified() }) }
        .sortedWith(compareBy({ it.name == "（下载根目录）" }, { it.name.lowercase() }))
}

@Composable
private fun GroupHeader(
    name: String,
    fileCount: Int,
    totalBytes: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (expanded) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$fileCount 个文件 · ${Fmt.bytes(totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = "删除整组",
                tint = MaterialTheme.colorScheme.error
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FileRow(
    file: File,
    mode: FileOpenMode,
    options: List<FileOpenMode>,
    onClick: () -> Unit,
    onModeChosen: (FileOpenMode) -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember(file.absolutePath) { mutableStateOf(false) }
    var menuOpen by remember(file.absolutePath) { mutableStateOf(false) }
    val date = remember(file.lastModified()) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 40.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon: ImageVector = when {
            FileActions.isVideo(file) -> Icons.Rounded.Movie
            FileActions.isPlayable(file) -> Icons.Rounded.MusicNote
            else -> Icons.Rounded.InsertDriveFile
        }
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp)
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${Fmt.bytes(file.length())} · $date · ${mode.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "打开方式")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        leadingIcon = {
                            if (option == mode) {
                                Icon(Icons.Rounded.Check, contentDescription = "当前默认")
                            } else {
                                Spacer(Modifier.size(24.dp))
                            }
                        },
                        onClick = {
                            menuOpen = false
                            onModeChosen(option)
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("分享") },
                    leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onShare()
                    }
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuOpen = false
                        confirmDelete = true
                    }
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除文件") },
            text = { Text("删除「${file.name}」？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

/** 能生成字幕的容器格式（音频轨能不能解码由系统决定）。 */
private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "flv", "wmv", "rmvb", "mpg", "mpeg"
)

/**
 * 文件页顶部的「批量生成 AI 字幕」卡片：显示待处理数量、当前进度，可取消。
 * 已有 `<名字>.asr.srt` 的文件自动跳过，所以重复点不会重跑。
 */
@Composable
private fun BatchSubtitleCard(
    batch: SubtitleBatchJob,
    videos: List<File>,
    onStart: (List<File>) -> Unit
) {
    val state by batch.state.collectAsState()
    val pending = remember(videos) { batch.pending(videos) }
    var confirming by remember { mutableStateOf(false) }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("生成 AI 字幕（${pending.size} 部）") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "按顺序生成，已有字幕的自动跳过。中途可以取消，已完成的不会重跑。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    pending.forEach { file ->
                        Text(
                            "${file.name}  ·  ${Fmt.bytes(file.length())}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onStart(videos)
                }) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("取消") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI 字幕", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = when {
                        state.running ->
                            "正在生成 ${state.index}/${state.total} · ${state.current}"
                        state.total > 0 && !state.running ->
                            "完成：成功 ${state.ok} · 失败 ${state.failed}"
                        pending.isEmpty() && videos.isNotEmpty() -> "已完成视频都已有字幕"
                        videos.isEmpty() -> "还没有下载完成的视频"
                        else -> "待生成 ${pending.size} 部完成视频 · 已有字幕自动跳过"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            when {
                state.running -> TextButton(onClick = { batch.cancel() }) { Text("取消") }
                pending.isNotEmpty() -> TextButton(onClick = { confirming = true }) { Text("生成") }
            }
        }
        if (state.running && state.total > 0) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = {
                    (state.index - 1).coerceAtLeast(0).toFloat() / state.total.toFloat()
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
