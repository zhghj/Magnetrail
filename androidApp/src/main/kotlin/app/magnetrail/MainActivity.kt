package app.magnetrail

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.magnetrail.AdBlocker
import app.magnetrail.shared.MagnetLink
import app.magnetrail.shared.MagnetParser
import app.magnetrail.shared.TorrentFile
import app.magnetrail.shared.TorrentItem
import app.magnetrail.ui.FileOpenMode
import app.magnetrail.ui.FilesTab
import app.magnetrail.ui.MagnetrailTheme
import app.magnetrail.ui.TasksTab
import app.magnetrail.ui.BrowserTabs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private var pending by mutableStateOf<MagnetLink?>(null)

    /** magnet that should be opened straight into "缓冲观看" instead of the task list. */
    private var pendingWatch by mutableStateOf<MagnetLink?>(null)

    /** debug: 让播放器注入一次假错误，验证重试逻辑（仅 debug APK）。 */
    private var debugFail by mutableStateOf(false)
    private var debugFailDelay by mutableStateOf(6_000)

    /** debug: 从命令行直接打开某个 tab（`--ei tab 1` 打开文件页，2 打开浏览器）。 */
    private var initialTab by mutableStateOf(0)

    /** debug: `--es browser_url https://…` 直接在浏览器里打开这个网址。 */
    private var browserUrl by mutableStateOf<String?>(null)
    private var browserScan by mutableStateOf(false)

    /** debug: 扫到第一个视频就自动交给播放器（验证链路用）。 */
    private var browserPlay by mutableStateOf(false)

    /** 运行中 `am start --ei tab 2` 时切 tab 用。 */
    private var pendingTab by mutableStateOf<Int?>(null)

    /** 所有打开过的标签页（含已关闭但尚未销毁的），用于统一暂停/销毁。 */
    private val browserRegistry = mutableListOf<BrowserController>()

    /** 内置浏览器（WebView 由它管理，切 tab / 旋转都不丢状态）。 */
    private val browser: BrowserController by lazy {
        val app = application as MagnetrailApp
        BrowserController(this, app.appSettings, app.browserStore).also { browserRegistry.add(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initialTab = intent.getIntExtra(EXTRA_TAB, 0).coerceIn(0, 2)
        browserUrl = intent.getStringExtra(EXTRA_BROWSER_URL)
        browserScan = intent.getBooleanExtra(EXTRA_BROWSER_SCAN, false)
        browserPlay = intent.getBooleanExtra(EXTRA_BROWSER_PLAY, false)
        // debug: 直接把一个远端视频交给播放器（验证 HLS/网络播放，不需要点 UI）
        if (isDebuggable()) {
            intent.getStringExtra(EXTRA_PLAYER_URL)?.takeIf { it.startsWith("http") }?.let { url ->
                startActivity(
                    PlayerActivity.networkIntent(
                        this,
                        url,
                        "调试视频",
                        null,
                        if (url.contains(".m3u8")) "hls" else if (url.contains(".mpd")) "dash" else null
                    )
                )
            }
            // debug: 直接播放本地文件（验证字幕文件、边下边看等）
            intent.getStringExtra(EXTRA_PLAYER_PATH)?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    startActivity(
                        PlayerActivity.intent(
                            this,
                            file,
                            intent.getLongExtra(EXTRA_PLAYER_START_MS, 0L)
                        ).putExtra(
                            "debug_gen_sub",
                            intent.getBooleanExtra(EXTRA_PLAYER_GEN_SUB, false)
                        ).putExtra(
                            "debug_live_sub",
                            intent.getBooleanExtra(EXTRA_PLAYER_LIVE_SUB, false)
                        )
                    )
                }
            }
        }
        consume(intent)

        val app = application as MagnetrailApp
        if (app.store.magnets().isNotEmpty()) {
            DownloadService.start(this)
        }

        setContent {
            MagnetrailTheme {
                AppRoot(
                    app = app,
                    browser = browser,
                    browserRegistry = browserRegistry,
                    browserUrl = browserUrl,
                    browserScan = browserScan,
                    browserPlay = browserPlay,
                    pendingTab = pendingTab,
                    pending = pending,
                    pendingWatch = pendingWatch,
                    debugFail = debugFail,
                    debugFailDelay = debugFailDelay,
                    initialTab = initialTab,
                    onPendingTabConsumed = { pendingTab = null },
                    onPendingConsumed = { pending = null },
                    onPendingWatchConsumed = { pendingWatch = null }
                )
            }
        }
    }

    // WebView 的 onStart/onStop 由 BrowserTabs 统一管理：只恢复当前标签，其余保持暂停。

    override fun onDestroy() {
        if (isFinishing) {
            browserRegistry.toList().forEach { runCatching { it.release() } }
            browserRegistry.clear()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 运行中也可以 `am start --ei tab 2 --es browser_url …`
        val requestedTab = intent.getIntExtra(EXTRA_TAB, -1)
        if (requestedTab >= 0) pendingTab = requestedTab.coerceIn(0, 2)
        intent.getStringExtra(EXTRA_BROWSER_URL)?.let { browserUrl = it }
        consume(intent)
    }

    private fun consume(intent: Intent?) {
        val raw = intent?.data?.toString() ?: return
        val link = MagnetParser.parse(raw) ?: return
        debugFail = intent.getBooleanExtra(EXTRA_DEBUG_FAIL, false)
        debugFailDelay = intent.getIntExtra(EXTRA_DEBUG_FAIL_DELAY, 6_000)
        // adb / other apps can ask for the buffer-only player directly:
        //   am start -n app.magnetrail/.MainActivity -a VIEW -d 'magnet:...' -e watch true
        if (intent.getBooleanExtra(EXTRA_WATCH, false)) {
            pendingWatch = link
        } else {
            pending = link
        }
    }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private companion object {
        const val EXTRA_WATCH = "watch"
        const val EXTRA_DEBUG_FAIL = "debug_fail"
        const val EXTRA_DEBUG_FAIL_DELAY = "debug_fail_delay"
        const val EXTRA_TAB = "tab"
        const val EXTRA_BROWSER_URL = "browser_url"
        const val EXTRA_BROWSER_SCAN = "browser_scan"
        const val EXTRA_BROWSER_PLAY = "browser_play"
        const val EXTRA_PLAYER_URL = "player_url"
        const val EXTRA_PLAYER_PATH = "player_path"
        const val EXTRA_PLAYER_START_MS = "player_start_ms"
        const val EXTRA_PLAYER_GEN_SUB = "player_gen_sub"
        const val EXTRA_PLAYER_LIVE_SUB = "player_live_sub"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    app: MagnetrailApp,
    browser: BrowserController,
    browserRegistry: MutableList<BrowserController>,
    browserUrl: String?,
    browserScan: Boolean,
    browserPlay: Boolean,
    pendingTab: Int?,
    pending: MagnetLink?,
    pendingWatch: MagnetLink?,
    debugFail: Boolean,
    debugFailDelay: Int,
    initialTab: Int,
    onPendingTabConsumed: () -> Unit,
    onPendingConsumed: () -> Unit,
    onPendingWatchConsumed: () -> Unit
) {
    val context = LocalContext.current
    val items by app.engine.items.collectAsStateWithLifecycle()
    val logs by app.engine.logs.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    var linkText by remember { mutableStateOf("") }

    fun readClipboard(): String? {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)?.coerceToText(context)?.toString()
    }

    fun submit(text: String) {
        val link = MagnetParser.parse(text)
        if (link == null) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.bad_link)) }
            return
        }
        if (items.any { it.infoHash == link.infoHash && !it.isTransient }) {
            scope.launch { snackbar.showSnackbar("这个任务已经在列表里了") }
            return
        }
        DownloadService.start(context)
        app.store.add(link)
        val accepted = app.engine.addMagnet(link, app.saveRoot.absolutePath)
        linkText = ""
        scope.launch {
            snackbar.showSnackbar(
                if (accepted) "已加入下载队列" else "已记录，会话启动后自动加入"
            )
        }
    }

    /** 缓冲观看：只拉视频进缓存目录，不落盘保存，播完就删。 */
    fun watchOnline(link: MagnetLink) {
        DownloadService.start(context)
        linkText = ""
        scope.launch {
            snackbar.showSnackbar("正在获取种子信息，拿到后自动开始播放…")
            var tries = 0
            while (!app.engine.running.value && tries < 100) {
                kotlinx.coroutines.delay(100)
                tries++
            }
            app.engine.streamTransient(
                link,
                onReady = { url, title ->
                    scope.launch {
                        val playerIntent =
                            PlayerActivity.streamIntent(
                                context, url, title, link.infoHash,
                                torrentHash = link.infoHash
                            )
                        if (debugFail) {
                            playerIntent.putExtra("debug_fail", true)
                            playerIntent.putExtra("debug_fail_delay", debugFailDelay)
                        }
                        // 播放器要抢音频，先把网页里的声音停掉（WebView 隐藏后仍会继续放）
                        browserRegistry.forEach { it.pauseAllMedia() }
                        context.startActivity(playerIntent)
                    }
                },
                onError = { message ->
                    scope.launch { snackbar.showSnackbar(message) }
                }
            )
        }
    }

    // ---------------------------------------------------- 文件页：打开方式（⋮ 菜单）

    /** 找到磁盘文件对应的任务 + 任务内文件（用来流播 / 判断是否下完）。 */
    fun taskFileFor(file: File): Pair<TorrentItem, TorrentFile>? {
        for (item in items) {
            if (item.isTransient) continue
            val tf = item.files.firstOrNull { f ->
                File(item.savePath, f.path).absolutePath == file.absolutePath
            }
            if (tf != null) return item to tf
        }
        return null
    }

    /** 文件是否已经完整下载（没有对应任务时按"完整"处理）。 */
    fun isFileComplete(file: File): Boolean {
        val pair = taskFileFor(file) ?: return true
        return pair.second.progress >= 0.999 || pair.first.isDone
    }

    /** 用户选过就用选择的；没选过：没下完默认边下边看，完整文件默认本地播放。 */
    /** 严格版「是否下载完整」：批量生成字幕用，宁可漏排也不要排半成品。 */
    fun isFileCompleteStrict(file: File): Boolean {
        taskFileFor(file)?.let { (task, tf) ->
            return tf.progress >= 0.999 || task.isDone
        }
        // 任务/文件路径对不上时（嵌套目录、改名），退回「任务名目录 + 同名文件」再找一次
        val task = items.firstOrNull { item ->
            !item.isTransient &&
                file.absolutePath.contains(File.separator + item.name + File.separator)
        } ?: return true // 手动拷进来的孤儿文件，按完成处理
        val tf = task.files.firstOrNull { it.path.substringAfterLast('/') == file.name }
            ?: return true
        return tf.progress >= 0.999 || task.isDone
    }

    fun modeOf(file: File): FileOpenMode {
        val complete = isFileComplete(file)
        app.fileModes.modeOf(file.absolutePath)?.let { remembered ->
            // 下完的文件不再提供"下载"，没下完的文件不提供"本地播放"
            val valid = if (complete) remembered != FileOpenMode.DOWNLOAD
            else remembered != FileOpenMode.LOCAL
            if (valid) return remembered
        }
        return if (complete) FileOpenMode.LOCAL else FileOpenMode.STREAM
    }

    fun playLocalFile(file: File) {
        if (FileActions.isPlayable(file)) {
            browserRegistry.forEach { it.pauseAllMedia() }
            context.startActivity(PlayerActivity.intent(context, file))
        } else {
            FileActions.openExternal(context, file)
        }
    }

    fun openFileAsStream(file: File, ephemeral: Boolean) {
        val pair = taskFileFor(file)
        if (pair == null) {
            scope.launch { snackbar.showSnackbar("这个文件没有对应的下载任务，按本地文件打开") }
            playLocalFile(file)
            return
        }
        val (item, tf) = pair
        val open: (String) -> Unit = { url ->
            context.startActivity(
                PlayerActivity.streamIntent(
                    context,
                    url,
                    file.name,
                    if (ephemeral && item.isTransient) item.infoHash else null,
                    torrentHash = item.infoHash
                )
            )
        }
        val first = app.engine.streamUrl(item.infoHash, tf.index)
        if (first != null) {
            open(first)
        } else {
            // 会话可能刚被停掉：拉起服务再重试一小会儿
            DownloadService.start(context)
            scope.launch {
                var url: String? = null
                repeat(20) {
                    if (url == null) {
                        url = app.engine.streamUrl(item.infoHash, tf.index)
                        if (url == null) delay(100)
                    }
                }
                val ready = url
                if (ready != null) open(ready)
                else snackbar.showSnackbar("暂时无法播放：种子信息还没到，或任务已停止")
            }
        }
    }

    /** 「下载」：把该文件设为要下载、恢复任务，并回到任务页看进度。 */
    fun downloadFile(file: File) {
        val pair = taskFileFor(file)
        if (pair == null) {
            scope.launch { snackbar.showSnackbar("这个文件没有对应的下载任务") }
            return
        }
        val (item, tf) = pair
        app.engine.setFileWanted(item.infoHash, tf.index, true)
        app.engine.resume(item.infoHash)
        DownloadService.start(context)
        tab = 0
        scope.launch { snackbar.showSnackbar("已加入下载：${file.name}，进度见任务页") }
    }

    fun openFileWithMode(file: File, mode: FileOpenMode) {
        app.fileModes.set(file.absolutePath, mode)
        when (mode) {
            FileOpenMode.DOWNLOAD -> downloadFile(file)
            FileOpenMode.LOCAL -> playLocalFile(file)
            FileOpenMode.STREAM -> openFileAsStream(file, ephemeral = false)
            FileOpenMode.BUFFER -> {
                openFileAsStream(file, ephemeral = true)
                val pair = taskFileFor(file)
                if (pair != null && !pair.first.isTransient) {
                    scope.launch {
                        snackbar.showSnackbar("该文件属于下载任务，缓冲播放后文件仍会保留")
                    }
                }
            }
        }
    }

    // 从浏览器/别的 App 点 magnet: 链接进来时，直接加入任务并开始下载
    LaunchedEffect(pendingTab) {
        if (pendingTab != null) {
            tab = pendingTab.coerceIn(0, 2)
            onPendingTabConsumed()
        }
    }

    LaunchedEffect(pending) {
        if (pending != null) {
            submit(pending.uri)
            onPendingConsumed()
        }
    }

    // 带 watch=true 的 magnet: 链接（adb 调试或分享到「缓冲观看」）→ 直接播放
    LaunchedEffect(pendingWatch) {
        if (pendingWatch != null) {
            watchOnline(pendingWatch)
            onPendingWatchConsumed()
        }
    }

    // 打开 App 时输入框为空，且剪贴板里是还没添加过的磁力链接 → 自动填好
    LaunchedEffect(Unit) {
        if (linkText.isBlank()) {
            val link = MagnetParser.parse(readClipboard())
            if (link != null && items.none { it.infoHash == link.infoHash }) {
                linkText = link.uri
            }
        }
    }

    val addError = remember(linkText) {
        if (linkText.isBlank() || MagnetParser.parse(linkText) != null) null
        else context.getString(R.string.bad_link)
    }

    RequestNotificationPermissionOnce()

    var showSettings by remember { mutableStateOf(false) }
    var uploadLimit by remember { mutableIntStateOf(app.appSettings.uploadLimitBytes) }

    // 浏览器打开过一次后一直保留 WebView：切 tab 不销毁网页、不丢登录状态。
    var browserMounted by remember { mutableStateOf(false) }
    LaunchedEffect(tab) {
        if (tab == 2) browserMounted = true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // 浏览器自己有一条地址栏，不再叠 App 标题栏
            if (tab != 2) {
                TopAppBar(
                    title = { Text("Magnetrail", style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Rounded.Speed, contentDescription = "上传限速")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // tab 放到底部，单手更好按
            NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                    label = { Text(context.getString(R.string.tab_tasks)) }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                    label = { Text(context.getString(R.string.tab_files)) }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Rounded.Language, contentDescription = null) },
                    label = { Text(context.getString(R.string.tab_browser)) }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 浏览器标签页按需显示自己的顶栏，这里不需要再补上传限速入口
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // 打开过一次浏览器后就一直保留 WebView（切 tab 不销毁网页状态）；
                // 不在浏览器 tab 时把容器收成 0x0，任务/文件页盖在上面。
                if (browserMounted || tab == 2) {
                    Box(
                        Modifier.then(
                            if (tab == 2) Modifier.fillMaxSize() else Modifier.size(0.dp)
                        )
                    ) {
                        BrowserTabs(
                            initialController = browser,
                            initialUrl = browserUrl,
                            initialScan = browserScan,
                            initialPlay = browserPlay,
                            visible = tab == 2,
                            registry = browserRegistry,
                            newController = {
                                BrowserController(context, app.appSettings, app.browserStore)
                            },
                            onSnackbar = { message ->
                                scope.launch { snackbar.showSnackbar(message) }
                            },
                            onOpenMedia = { candidate, headers ->
                                browserRegistry.forEach { it.pauseAllMedia() }
                                context.startActivity(
                                    PlayerActivity.networkIntent(
                                        context,
                                        candidate.url,
                                        candidate.label,
                                        headers,
                                        candidate.kind
                                    )
                                )
                            },
                            onOpenVideo = { entry ->
                                browserRegistry.forEach { it.pauseAllMedia() }
                                context.startActivity(
                                    PlayerActivity.networkIntent(
                                        context,
                                        entry.url,
                                        entry.title,
                                        entry.headers.toTypedArray().takeIf { it.isNotEmpty() },
                                        AdBlocker.mediaKind(entry.url),
                                        startMs = entry.positionMs
                                    )
                                )
                            },
                            store = app.browserStore,
                            onMagnet = { uri ->
                                tab = 0
                                submit(uri)
                            }
                        )
                    }
                }
                if (tab != 2) when (tab) {
                0 -> TasksTab(
                    items = items,
                    linkText = linkText,
                    onLinkTextChange = { linkText = it },
                    onPaste = {
                        val text = readClipboard()
                        if (text.isNullOrBlank()) {
                            scope.launch { snackbar.showSnackbar("剪贴板里没有文本") }
                        } else {
                            linkText = text
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.copied)) }
                        }
                    },
                    onAdd = { submit(linkText) },
                    onWatchOnline = {
                        val link = MagnetParser.parse(linkText)
                        if (link == null) {
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.bad_link)) }
                        } else {
                            watchOnline(link)
                        }
                    },
                    onPause = { hash -> app.engine.pause(hash) },
                    onResume = { hash -> app.engine.resume(hash) },
                    onRemove = { hash, deleteFiles ->
                        app.engine.remove(hash, deleteFiles)
                        app.store.remove(hash)
                    },
                    onFileToggle = { hash, index, wanted ->
                        app.engine.setFileWanted(hash, index, wanted)
                    },
                    onStreamFile = { hash, index, name ->
                        val open: (String) -> Unit = { url ->
                            context.startActivity(
                                PlayerActivity.streamIntent(
                                    context, url, name, torrentHash = hash
                                )
                            )
                        }
                        val first = app.engine.streamUrl(hash, index)
                        if (first != null) {
                            open(first)
                        } else {
                            // session may have been idle-stopped: restart and retry shortly
                            DownloadService.start(context)
                            scope.launch {
                                var url: String? = null
                                repeat(20) {
                                    if (url == null) {
                                        url = app.engine.streamUrl(hash, index)
                                        if (url == null) kotlinx.coroutines.delay(100)
                                    }
                                }
                                if (url != null) open(url) else snackbar.showSnackbar(
                                    "暂时无法播放：种子信息还没到，或任务已停止"
                                )
                            }
                        }
                    },
                    addError = addError,
                    logs = logs
                )

                else -> FilesTab(
                    root = app.saveRoot,
                    modeOf = { file -> modeOf(file) },
                    isComplete = { file -> isFileComplete(file) },
                    onModeChosen = { file, mode -> openFileWithMode(file, mode) },
                    onOpen = { file -> FileActions.openExternal(context, file) },
                    onShare = { file -> FileActions.share(context, file) },
                    onDelete = { file -> FileActions.deleteFile(file) },
                    batch = app.subtitleBatch,
                    canBatch = { file -> isFileCompleteStrict(file) },
                    onStartBatch = { videos ->
                        if (!app.subtitleEngine.available) {
                            scope.launch {
                                snackbar.showSnackbar("先在播放器里下载 AI 模型，再来批量生成")
                            }
                        } else {
                            app.subtitleBatch.start(app.subtitleScope, videos)
                        }
                    },
                    onDeleteGroup = { name, groupFiles ->
                        // 这一组如果对应一个下载任务：连任务一起删（否则做种的任务会重建/占用文件）
                        val task = items.firstOrNull { it.name == name && !it.isTransient }
                            ?: items.firstOrNull { task ->
                                !task.isTransient && groupFiles.any { it.name == task.name }
                            }
                        if (task != null) {
                            app.engine.remove(task.infoHash, deleteFiles = true)
                            app.store.remove(task.infoHash)
                            scope.launch { snackbar.showSnackbar("已删除任务和文件：${task.name}") }
                        } else {
                            scope.launch {
                                val removed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    var count = 0
                                    groupFiles.forEach { f ->
                                        if (runCatching { f.deleteRecursively() }.getOrDefault(false)) count++
                                    }
                                    groupFiles.firstOrNull()?.parentFile?.let { dir ->
                                        if (dir != app.saveRoot && dir.listFiles()?.isEmpty() == true) {
                                            dir.delete()
                                        }
                                    }
                                    count
                                }
                                snackbar.showSnackbar("已删除 $name（$removed 个文件）")
                            }
                        }
                    }
                )
            }
        }
    }
    if (showSettings) {
        UploadLimitDialog(
            current = uploadLimit,
            onPick = { bytes ->
                uploadLimit = bytes
                app.appSettings.uploadLimitBytes = bytes
                app.engine.setUploadRateLimit(bytes)
                showSettings = false
                scope.launch {
                    snackbar.showSnackbar(
                        if (bytes == 0) "上传已不限速" else "上传限速：${bytes / 1024} KB/s"
                    )
                }
            },
            onDismiss = { showSettings = false }
        )
    }
}
}

@Composable
private fun RequestNotificationPermissionOnce() {
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }
    var asked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!asked && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** 上传限速选择：128 KB/s 是推荐下限，0 = 不限。 */
@Composable
private fun UploadLimitDialog(
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        0 to "不限速（做种最快，占上行）",
        2 * 1024 * 1024 to "2 MB/s",
        1024 * 1024 to "1 MB/s",
        512 * 1024 to "512 KB/s",
        256 * 1024 to "256 KB/s",
        128 * 1024 to "128 KB/s（合理最小值）"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上传限速（做种）") },
        text = {
            Column {
                Text(
                    "做种会占用上行带宽。128 KB/s 是合理的最低值：再低 peer 容易把你 choke，" +
                        "BT 互惠变差，下载反而更慢。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                options.forEach { (bytes, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(bytes) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = current == bytes,
                            onClick = { onPick(bytes) }
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
