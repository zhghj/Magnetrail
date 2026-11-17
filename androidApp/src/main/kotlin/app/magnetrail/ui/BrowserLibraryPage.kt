package app.magnetrail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.magnetrail.BrowserStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 浏览器里的整页（不再是弹窗）。 */
enum class LibraryPage { BOOKMARKS, HISTORY }

/**
 * 收藏夹 / 历史 页面。
 *
 * 历史分两个 tab：
 *  - 网页历史：普通浏览记录；
 *  - 视频历史：网页视频的观看进度，点一条就接着上次的位置播放。
 */
@Composable
fun BrowserLibraryPage(
    page: LibraryPage,
    store: BrowserStore,
    onOpenWeb: (String) -> Unit,
    onOpenVideo: (BrowserStore.VideoEntry) -> Unit,
    onClose: () -> Unit
) {
    var version by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
                Text(
                    if (page == LibraryPage.BOOKMARKS) "收藏夹" else "历史记录",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (page == LibraryPage.HISTORY) {
                    TextButton(onClick = {
                        if (tab == 0) store.clearHistory() else store.clearVideoHistory()
                        version++
                    }) { Text("清空") }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (page == LibraryPage.HISTORY) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("网页历史") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("视频历史") })
                }
            }

            if (page == LibraryPage.BOOKMARKS) {
                val entries = remember(version) { store.bookmarks() }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { "b:${it.url}" }) { entry ->
                        EntryRow(
                            title = entry.title,
                            subtitle = "${entry.host} · ${timeFormat.format(Date(entry.timeMs))}",
                            onClick = { onOpenWeb(entry.url) },
                            onDelete = {
                                store.removeBookmark(entry.url)
                                version++
                            }
                        )
                    }
                    if (entries.isEmpty()) {
                        item { LibraryEmpty("还没有收藏。在网页里点「展开」→「收藏」即可。") }
                    }
                }
            } else if (tab == 0) {
                val entries = remember(version) { store.history() }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { "h:${it.url}" }) { entry ->
                        EntryRow(
                            title = entry.title,
                            subtitle = "${entry.host} · ${timeFormat.format(Date(entry.timeMs))}",
                            onClick = { onOpenWeb(entry.url) },
                            onDelete = {
                                store.removeHistory(entry.url)
                                version++
                            }
                        )
                    }
                    if (entries.isEmpty()) {
                        item { LibraryEmpty("还没有网页历史。无痕模式下不会记录。") }
                    }
                }
            } else {
                val videos = remember(version) { store.videoHistory() }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(videos, key = { "v:${it.url}" }) { entry ->
                        VideoRow(
                            entry = entry,
                            timeFormat = timeFormat,
                            onClick = { onOpenVideo(entry) },
                            onDelete = {
                                store.removeVideo(entry.url)
                                version++
                            }
                        )
                    }
                    if (videos.isEmpty()) {
                        item {
                            LibraryEmpty("还没有视频观看记录。\n在「浏览」里打开网页视频后会自动记录进度。")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun VideoRow(
    entry: BrowserStore.VideoEntry,
    timeFormat: SimpleDateFormat,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 8.dp)) {
                Text(entry.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${hostOf(entry.url)} · ${clock(entry.positionMs)}${if (entry.durationMs > 0) " / " + clock(entry.durationMs) else ""} · ${timeFormat.format(Date(entry.timeMs))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.durationMs > 0) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { entry.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun LibraryEmpty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp)
    )
}

private fun clock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull() ?: url.take(28)
