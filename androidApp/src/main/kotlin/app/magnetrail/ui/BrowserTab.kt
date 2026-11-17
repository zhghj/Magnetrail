package app.magnetrail.ui

import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.magnetrail.AdBlocker
import app.magnetrail.BrowserController
import app.magnetrail.BrowserStore
import app.magnetrail.MediaCandidate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「浏览」内置浏览器页：
 *  - 顶部：返回 / 前进 / 网址栏 / 刷新（网址栏带无痕标记）；
 *  - 底部：可收起操作栏（本页视频、拦截计数、粘贴、收藏、历史、无痕…）；
 *  - 网页视频一律交给 PlayerActivity（自带画中画、横屏、出错重试）。
 */
@Composable
fun BrowserTab(
    controller: BrowserController,
    initialUrl: String?,
    initialScan: Boolean,
    initialPlay: Boolean,
    visible: Boolean,
    onSnackbar: (String) -> Unit,
    onOpenMedia: (MediaCandidate, Array<String>) -> Unit,
    onOpenVideo: (app.magnetrail.BrowserStore.VideoEntry) -> Unit,
    onMagnet: (String) -> Unit,
    store: app.magnetrail.BrowserStore
) {
    controller.notify = onSnackbar
    controller.onMagnet = onMagnet

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var address by remember { mutableStateOf(controller.currentUrl) }
    var editing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showMedia by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showIncognitoConfirm by remember { mutableStateOf(false) }
    var actionsExpanded by remember { mutableStateOf(false) }
    var historyVersion by remember { mutableIntStateOf(0) }
    var bookmarkVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(controller.currentUrl, editing) {
        if (!editing) address = controller.currentUrl
    }

    // 切到别的 tab 时网页还在后台（保留状态），但别让网页视频继续出声、键盘也别挡着
    LaunchedEffect(visible) {
        if (!visible) {
            controller.pauseAllMedia()
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    // 调试/命令行直达：`--ei tab 2 --es browser_url https://…`
    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank() && controller.currentUrl.isBlank()) {
            controller.load(initialUrl)
            if (initialScan || initialPlay) {
                kotlinx.coroutines.delay(2_500)
                controller.scanMedia(silent = true)
                if (initialPlay) {
                    kotlinx.coroutines.delay(1_200)
                    val first = controller.mediaUrls.firstOrNull()
                    if (first != null) onOpenMedia(first, controller.headersFor(first.url))
                }
            }
        }
    }

    fun submit() {
        val text = address.trim()
        if (text.isEmpty()) return
        focusManager.clearFocus()
        keyboard?.hide()
        editing = false
        controller.load(text)
    }

    fun pasteAndLoad() {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
        } else null
        if (text.isNullOrBlank()) {
            onSnackbar("剪贴板里没有网址")
            return
        }
        address = text
        focusManager.clearFocus()
        keyboard?.hide()
        editing = false
        controller.load(text)
    }

    val bookmarked = remember(controller.currentUrl, bookmarkVersion) {
        controller.isCurrentBookmarked()
    }

    // 收藏夹 / 历史：整页显示（不再用弹窗）
    if (showHistory || showBookmarks) {
        BrowserLibraryPage(
            page = if (showBookmarks) LibraryPage.BOOKMARKS else LibraryPage.HISTORY,
            store = store,
            onOpenWeb = { url ->
                showHistory = false
                showBookmarks = false
                address = url
                controller.load(url)
            },
            onOpenVideo = { entry ->
                showHistory = false
                showBookmarks = false
                onOpenVideo(entry)
            },
            onClose = {
                showHistory = false
                showBookmarks = false
            }
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        // ------------------------------------------------------------ 地址栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, end = 6.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { controller.goBack() }, enabled = controller.canGoBack) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "后退",
                    tint = if (controller.canGoBack) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
            IconButton(onClick = { controller.goForward() }, enabled = controller.canGoForward) {
                Icon(
                    Icons.Rounded.ArrowForward,
                    contentDescription = "前进",
                    tint = if (controller.canGoForward) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f).height(40.dp)
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (controller.incognito) {
                        Icon(
                            Icons.Rounded.VisibilityOff,
                            contentDescription = "无痕模式",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    BasicTextField(
                        value = address,
                        onValueChange = { address = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { editing = it.isFocused },
                        decorationBox = { inner ->
                            Box {
                                if (address.isEmpty()) {
                                    Text(
                                        if (controller.incognito) "无痕模式：输入网址或搜索词"
                                        else "输入网址或搜索词",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
            }
            IconButton(onClick = { controller.reloadOrStop() }) {
                Icon(
                    if (controller.loading) Icons.Rounded.Close else Icons.Rounded.Refresh,
                    contentDescription = if (controller.loading) "停止" else "刷新"
                )
            }
        }

        // 加载进度条（不占布局高度，细线）
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (controller.loading) {
                LinearProgressIndicator(
                    progress = { (controller.progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }

        // ------------------------------------------------------------ 网页
        Box(Modifier.fillMaxWidth().weight(1f)) {
            key(controller.generation) {
                AndroidView(
                    factory = { controller.obtainWebView() },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        // 切到别的 tab 时：只把容器收成 0x0 不够，WebView 会溢出父容器继续绘制
                        // （任务页卡片缝隙里能看到网页）。GONE 直接让它不参与绘制。
                        view.visibility = if (visible) android.view.View.VISIBLE
                        else android.view.View.GONE
                    }
                )
            }

            if (controller.currentUrl.isBlank() && controller.errorMessage == null) {
                BrowserStartPage(
                    adBlockEnabled = controller.adBlockEnabled,
                    blockedCount = controller.blockedCount,
                    lastUrl = controller.lastUrl,
                    onPaste = { pasteAndLoad() },
                    onOpenQuick = { url ->
                        address = url
                        controller.load(url)
                    }
                )
            }

            controller.errorMessage?.let { message ->
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Row {
                        TextButton(onClick = { controller.reloadOrStop() }) { Text("重试") }
                        TextButton(onClick = {
                            address = ""
                            controller.goHome()
                        }) { Text("回首页") }
                    }
                }
            }
        }

        // ------------------------------------------------------------ 底部操作栏（可收起）
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (actionsExpanded) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ActionChip(
                            label = if (bookmarked) "已收藏" else "收藏",
                            icon = {
                                Icon(
                                    if (bookmarked) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (bookmarked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {
                                controller.toggleBookmarkCurrent()
                                bookmarkVersion++
                            }
                        )
                        ActionChip("历史", Icons.Rounded.History) {
                            historyVersion++
                            showHistory = true
                        }
                        ActionChip("收藏夹", Icons.Rounded.Star) { showBookmarks = true }
                        ActionChip(
                            if (controller.incognito) "无痕中" else "无痕",
                            Icons.Rounded.VisibilityOff,
                            tint = if (controller.incognito) MaterialTheme.colorScheme.primary
                            else null
                        ) {
                            if (controller.incognito) {
                                controller.applyIncognito(false)
                            } else {
                                showIncognitoConfirm = true
                            }
                        }
                        ActionChip("粘贴", Icons.Rounded.ContentPaste) { pasteAndLoad() }
                        ActionChip(
                            if (controller.desktopMode) "桌面版" else "手机版",
                            Icons.Rounded.OpenInBrowser
                        ) { controller.toggleDesktopMode() }
                        ActionChip("清缓存", Icons.Rounded.Delete) { showClearConfirm = true }
                        ActionChip("首页", Icons.Rounded.Home) {
                            address = ""
                            controller.goHome()
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            if (controller.currentUrl.isBlank()) {
                                onSnackbar("先输入网址打开一个网页")
                            } else {
                                controller.scanMedia()
                                showMedia = true
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Rounded.PlayCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "本页视频 ${controller.mediaUrls.size}",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                    TextButton(
                        onClick = { controller.toggleAdBlock() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Security,
                            contentDescription = "广告拦截",
                            tint = if (controller.adBlockEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (controller.adBlockEnabled) "拦截 ${controller.blockedCount}"
                            else "拦截已关",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { actionsExpanded = !actionsExpanded },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            if (actionsExpanded) Icons.Rounded.ExpandMore
                            else Icons.Rounded.ExpandLess,
                            contentDescription = if (actionsExpanded) "收起操作" else "展开更多操作",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = "更多",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (controller.adBlockEnabled) "关闭广告拦截" else "开启广告拦截") },
                                leadingIcon = { Icon(Icons.Rounded.Security, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    controller.toggleAdBlock()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (controller.desktopMode) "切回手机版网页" else "切换桌面版网页") },
                                leadingIcon = { Icon(Icons.Rounded.OpenInBrowser, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    controller.toggleDesktopMode()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("复制当前网址") },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    val url = controller.currentUrl
                                    if (url.isBlank()) onSnackbar("还没有打开网页")
                                    else controller.copyText(url)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("用系统浏览器打开") },
                                leadingIcon = { Icon(Icons.Rounded.OpenInBrowser, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    val url = controller.currentUrl
                                    if (url.isBlank()) onSnackbar("还没有打开网页")
                                    else controller.openExternal(url)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("分享当前网页") },
                                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    val url = controller.currentUrl
                                    if (url.isBlank()) onSnackbar("还没有打开网页")
                                    else controller.shareText(url)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("重新扫描本页视频") },
                                leadingIcon = { Icon(Icons.Rounded.PlayCircle, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    controller.scanMedia()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("清理网页缓存") },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    showClearConfirm = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("清除 Cookie 和缓存") },
                                leadingIcon = { Icon(Icons.Rounded.VisibilityOff, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    controller.clearBrowsingData()
                                    onSnackbar("已清除 Cookie 和缓存")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("回到空白首页") },
                                leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    address = ""
                                    controller.goHome()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ 本页视频列表
    if (showMedia) {
        MediaListDialog(
            controller = controller,
            onDismiss = { showMedia = false },
            onPlay = { candidate ->
                showMedia = false
                onOpenMedia(candidate, controller.headersFor(candidate.url))
            }
        )
    }

    // ------------------------------------------------------------ 长按链接
    controller.pendingLink?.let { link ->
        val playable = AdBlocker.isPlayableMediaUrl(link)
        AlertDialog(
            onDismissRequest = { controller.pendingLink = null },
            title = { Text("链接操作") },
            text = {
                Column {
                    Text(
                        link,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    LinkAction("打开这个链接") {
                        controller.pendingLink = null
                        address = link
                        controller.load(link)
                    }
                    LinkAction("复制链接") {
                        controller.pendingLink = null
                        controller.copyText(link)
                    }
                    LinkAction("用系统浏览器打开") {
                        controller.pendingLink = null
                        controller.openExternal(link)
                    }
                    if (playable) {
                        LinkAction("用本机播放器播放（画中画）") {
                            controller.pendingLink = null
                            onOpenMedia(
                                MediaCandidate(
                                    url = link,
                                    label = link.substringAfterLast('/').take(40).ifBlank { "视频" },
                                    kind = AdBlocker.mediaKind(link)
                                ),
                                controller.headersFor(link)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { controller.pendingLink = null }) { Text("取消") }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清理网页缓存？") },
            text = { Text("会清掉网页缓存和前进/后退记录，登录状态（Cookie）保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    controller.clearCache()
                }) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showIncognitoConfirm) {
        AlertDialog(
            onDismissRequest = { showIncognitoConfirm = false },
            title = { Text("开启无痕模式？") },
            text = {
                Text(
                    "无痕模式下不会记录浏览历史和「上次网址」。\n\n" +
                        "开启时会清除浏览器 Cookie 和缓存（普通模式的登录状态也会被清掉），退出时再清一次。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showIncognitoConfirm = false
                    controller.applyIncognito(true)
                }) { Text("开启") }
            },
            dismissButton = {
                TextButton(onClick = { showIncognitoConfirm = false }) { Text("取消") }
            }
        )
    }

    if (showHistory) {
        // 已改为整页显示（见函数开头的 BrowserLibraryPage）
        showHistory = false
    }

    if (showBookmarks) {
        showBookmarks = false
    }
}

@Composable
private fun BrowserStartPage(
    adBlockEnabled: Boolean,
    blockedCount: Int,
    lastUrl: String,
    onPaste: () -> Unit,
    onOpenQuick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.PlayCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("浏览器", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "输入网址开始浏览。网页里的视频可以交给本机播放器：支持画中画、横屏、拖动进度和断流自动重试。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                StartRow("从剪贴板粘贴网址", onPaste)
                if (lastUrl.isNotBlank()) {
                    StartRow("继续上次：${hostOf(lastUrl)}", onClick = { onOpenQuick(lastUrl) })
                }
                StartRow(
                    if (adBlockEnabled) "广告拦截：已开启（已拦 $blockedCount 个请求）"
                    else "广告拦截：已关闭"
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "常用站点",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            QuickChip("百度", "https://m.baidu.com", onOpenQuick)
            QuickChip("必应", "https://cn.bing.com", onOpenQuick)
            QuickChip("B站", "https://m.bilibili.com", onOpenQuick)
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "小技巧：长按网页里的链接可以「复制 / 新页打开 / 直接播放」；" +
                "点开网页视频后再按「本页视频」，识别率最高。magnet: 链接会直接加入下载。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StartRow(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun QuickChip(label: String, url: String, onOpen: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable { onOpen(url) }
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun LinkAction(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 底部操作栏里的一个紧凑按钮。 */
@Composable
private fun ActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit
) = ActionChip(label, { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant) }, onClick)

/** 同上，但允许自定义图标内容（例如收藏/未收藏用不同图标）。 */
@Composable
private fun ActionChip(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
    ) {
        icon()
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun MediaListDialog(
    controller: BrowserController,
    onDismiss: () -> Unit,
    onPlay: (MediaCandidate) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本页视频（${controller.mediaUrls.size}）") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (controller.mediaUrls.isEmpty()) {
                    Text(
                        "还没检测到视频。\n\n" +
                            "1. 先在网页里点一下播放按钮（让它真的去请求视频）；\n" +
                            "2. 再点「重新扫描」或「本页视频」；\n" +
                            "3. 仍然没有的话，长按视频画面/播放按钮，看是否能拿到直链。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                controller.mediaUrls.forEach { candidate ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                candidate.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${candidate.kind.uppercase()} · ${hostOf(candidate.url)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = { onPlay(candidate) }) { Text("播放") }
                        IconButton(
                            onClick = { controller.copyText(candidate.url) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = "复制链接",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            TextButton(onClick = { controller.scanMedia() }) { Text("重新扫描") }
        }
    )
}

/** 历史 / 收藏夹通用列表：点一项打开，右侧垃圾桶删除，可清空。 */
@Composable
private fun EntryListDialog(
    title: String,
    entries: List<BrowserStore.Entry>,
    emptyText: String,
    onOpen: (BrowserStore.Entry) -> Unit,
    onDelete: (BrowserStore.Entry) -> Unit,
    onClearAll: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (entries.isEmpty()) {
                    Text(
                        emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                entries.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).clickable { onOpen(entry) },
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                entry.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${entry.host} · ${timeFormat.format(Date(entry.timeMs))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { onDelete(entry) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = "删除这一条",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = if (onClearAll != null) {
            {
                TextButton(onClick = onClearAll) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            }
        } else null
    )
}

private fun hostOf(url: String): String {
    val host = runCatching { Uri.parse(url).host }.getOrNull()
    return host ?: url.take(32)
}
