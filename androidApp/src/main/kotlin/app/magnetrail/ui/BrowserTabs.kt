package app.magnetrail.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.magnetrail.BrowserController
import app.magnetrail.BrowserStore
import app.magnetrail.MediaCandidate
import kotlinx.coroutines.delay

/** 同时存在的浏览器标签页上限：每个标签一个 WebView，太多会吃内存。 */
const val MAX_BROWSER_TABS = 5

/**
 * 多标签浏览器容器。
 *
 * 设计要点：
 *  - 每个标签持有独立的 [BrowserController]（自己的 WebView、历史、无痕状态）；
 *  - 非活动标签保留在组合树里但收成 0x0 + WebView GONE：切回来页面不重载、登录态不丢；
 *  - 非活动标签暂停网页视频，避免后台多个标签一起出声；
 *  - 关闭标签不立刻 destroy WebView：先让 Compose 把 AndroidView 摘下，下一拍再销毁，
 *    否则 AndroidView 可能在已销毁的 WebView 上执行 update 导致崩溃。
 */
@Composable
fun BrowserTabs(
    initialController: BrowserController,
    initialUrl: String?,
    initialScan: Boolean,
    initialPlay: Boolean,
    visible: Boolean,
    /** 活动中的 WebView 登记表：Activity 销毁/打开播放器时统一处理所有标签。 */
    registry: MutableList<BrowserController>,
    newController: () -> BrowserController,
    onSnackbar: (String) -> Unit,
    onOpenMedia: (MediaCandidate, Array<String>) -> Unit,
    onOpenVideo: (BrowserStore.VideoEntry) -> Unit,
    onMagnet: (String) -> Unit,
    store: BrowserStore
) {
    val tabs = remember { mutableStateListOf(initialController) }
    var activeIndex by remember { mutableIntStateOf(0) }

    // 延迟销毁队列：关闭动作只把 WebView 从列表里摘掉，真正 destroy 放到下一拍。
    val pendingRelease = remember { mutableStateListOf<BrowserController>() }
    var releaseTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(releaseTick) {
        if (pendingRelease.isEmpty()) return@LaunchedEffect
        // 等一帧都不够稳（AndroidView 的 dispose 在 layout 之后），给 150ms 余量，用户无感。
        delay(150)
        val doomed = pendingRelease.toList()
        pendingRelease.clear()
        doomed.forEach { controller ->
            registry.remove(controller)
            runCatching { controller.release() }
        }
    }

    // 前后台：只让当前标签 resume，其它标签暂停定时器/动画；回后台全部暂停。
    val lifecycleOwner = LocalLifecycleOwner.current
    val activeNow by rememberUpdatedState(activeIndex)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> tabs.getOrNull(activeNow)?.resumeWebView()
                Lifecycle.Event.ON_STOP -> tabs.forEach { it.pauseWebView() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 切换标签：新标签开始跑，旧标签停掉（页面还在，只是不占 CPU/不出声）。
    LaunchedEffect(activeIndex, visible) {
        tabs.forEachIndexed { index, controller ->
            if (visible && index == activeIndex) controller.resumeWebView()
            else controller.pauseWebView()
        }
    }

    val active = tabs.getOrNull(activeIndex)
    BackHandler(enabled = visible && active != null && active.canGoBack) {
        active?.goBack()
    }

    fun selectTab(index: Int) {
        if (index in tabs.indices && index != activeIndex) activeIndex = index
    }

    fun addTab() {
        if (tabs.size >= MAX_BROWSER_TABS) {
            onSnackbar("最多同时开 $MAX_BROWSER_TABS 个标签页，先关掉一个")
            return
        }
        val controller = newController()
        tabs.add(controller)
        registry.add(controller)
        activeIndex = tabs.lastIndex
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        // 最后一个标签不销毁：回到起始页，保持“永远有一个标签”
        if (tabs.size == 1) {
            tabs[0].goHome()
            return
        }
        val closing = tabs.removeAt(index)
        pendingRelease.add(closing)
        releaseTick++
        activeIndex = when {
            activeIndex > index -> activeIndex - 1
            activeIndex == index -> index.coerceAtMost(tabs.lastIndex)
            else -> activeIndex
        }
    }

    Column(Modifier.fillMaxSize()) {
        BrowserTabStrip(
            tabs = tabs,
            activeIndex = activeIndex,
            onSelect = { selectTab(it) },
            onClose = { closeTab(it) },
            onAdd = { addTab() }
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            tabs.forEachIndexed { index, controller ->
                key(controller) {
                    Box(
                        Modifier.then(
                            if (index == activeIndex) Modifier.fillMaxSize()
                            else Modifier.size(0.dp)
                        )
                    ) {
                        BrowserTab(
                            controller = controller,
                            // 命令行直达只作用于第一个标签，别让每个新标签都去加载同一个 URL
                            initialUrl = if (controller === initialController) initialUrl else null,
                            initialScan = controller === initialController && initialScan,
                            initialPlay = controller === initialController && initialPlay,
                            visible = visible && index == activeIndex,
                            onSnackbar = onSnackbar,
                            onOpenMedia = onOpenMedia,
                            onOpenVideo = onOpenVideo,
                            onMagnet = onMagnet,
                            store = store
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- tab strip

@Composable
private fun BrowserTabStrip(
    tabs: List<BrowserController>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onAdd: () -> Unit
) {
    val scroll = rememberScrollState()

    // 新标签在右侧，开新标签后滚到底，保证能看到它
    var lastCount by remember { mutableIntStateOf(tabs.size) }
    LaunchedEffect(tabs.size) {
        if (tabs.size > lastCount) {
            delay(60) // 等这次布局把新 chip 量出来
            scroll.animateScrollTo(scroll.maxValue)
        }
        lastCount = tabs.size
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, controller ->
            key(controller) {
                BrowserTabChip(
                    controller = controller,
                    active = index == activeIndex,
                    onClick = { onSelect(index) },
                    onClose = { onClose(index) }
                )
                Spacer(Modifier.width(6.dp))
            }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(30.dp).clickable(onClick = onAdd)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "新建标签页",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun BrowserTabChip(
    controller: BrowserController,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        modifier = Modifier
            .height(30.dp)
            .widthIn(max = 150.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tabLabel(controller),
                style = MaterialTheme.typography.labelMedium,
                color = if (active) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 108.dp)
            )
            Box(
                Modifier.size(22.dp).clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "关闭标签页",
                    modifier = Modifier.size(14.dp),
                    tint = if (active) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun tabLabel(controller: BrowserController): String {
    val url = controller.currentUrl
    // goHome() 会把 WebView 送到 about:blank，标签上仍显示「新标签页」
    if (url.isBlank() || url == "about:blank") return "新标签页"
    val title = controller.pageTitle.trim()
    if (title.isNotEmpty() && title != "about:blank") return title
    val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty()
    return host.ifBlank { "新标签页" }
}
