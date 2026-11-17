package app.magnetrail

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import app.magnetrail.shared.SubtitleLine
import app.magnetrail.shared.SubtitleMode
import app.magnetrail.shared.SubtitleSource
import app.magnetrail.shared.SubtitleState
import app.magnetrail.ui.MagnetrailTheme
import app.magnetrail.ui.PlayerSettingsPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * In-app player (Media3/ExoPlayer) for finished files and for HTTP streams served by
 * TorrentStreamServer ("边下边播"). Supports Android Picture-in-Picture; the PiP window
 * is resizable by the user (and on Android 12+ it can auto-enter when leaving the app).
 */
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var inPip by mutableStateOf(false)
    private var streamingRegistered = false
    private var streamedTorrentHash: String? = null
    private var pipAspect: Rational = Rational(16, 9)
    private var errorMessage by mutableStateOf<String?>(null)

    // 播放状态（给自绘控制条用）
    private var isPlaying by mutableStateOf(false)
    private var currentHeight by mutableStateOf(0)
    private var positionMs by mutableStateOf(0L)
    private var durationMs by mutableStateOf(0L)
    private var bufferedMs by mutableStateOf(0L)

    // 出错重试：记住出错时的进度，重试时从那里附近接着播
    private var retrying by mutableStateOf<String?>(null)
    private var retryAttempt = 0
    private var lastGoodPositionMs = 0L
    private var lastErrorAtMs = 0L
    private var lastPlaybackError: androidx.media3.common.PlaybackException? = null
    private var retryJob: Job? = null
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var mediaItem: MediaItem
    private lateinit var dataSourceFactory: DefaultDataSource.Factory
    private lateinit var trackSelector: DefaultTrackSelector

    /** 源里实际存在的画质档位（HLS/DASH 多档时会有多条；单文件只有一条）。 */
    private var qualityOptions by mutableStateOf<List<QualityOption>>(emptyList())

    /** 用户手动锁定的档位；null = 自动（自适应带宽）。 */
    private var chosenQuality by mutableStateOf<QualityOption?>(null)
    private var qualityDialogOpen by mutableStateOf(false)
    private var settingsOpen by mutableStateOf(false)
    private lateinit var subtitles: SubtitleController
    private var preferHd by mutableStateOf(false)
    private var autoPickDone = false
    private var stallSince = 0L
    private var qualityNotice by mutableStateOf<String?>(null)

    /** "hls" / "dash" / "progressive"（网页视频用，null 表示按扩展名自动判断）。 */
    private var mediaKind: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filePath = intent.getStringExtra(EXTRA_PATH)
        val streamUrl = intent.getStringExtra(EXTRA_URL)
        val ephemeralHash = intent.getStringExtra(EXTRA_EPHEMERAL)
        val torrentHash = intent.getStringExtra(EXTRA_TORRENT)
        val network = intent.getBooleanExtra(EXTRA_NETWORK, false)
        val networkKind = intent.getStringExtra(EXTRA_KIND)
        val title = intent.getStringExtra(EXTRA_TITLE)
            ?: filePath?.substringAfterLast(File.separatorChar)
            ?: "播放"

        val uri: Uri? = when {
            streamUrl != null -> Uri.parse(streamUrl)
            filePath != null -> {
                val file = File(filePath)
                if (!file.exists()) {
                    finish()
                    return
                }
                FileActions.uriFor(this, file)
            }
            else -> null
        }
        if (uri == null) {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 本地流播放期间让下载会话保持存活（否则服务空闲停服会把 HTTP 服务关掉）；
        // 网页视频（network=true）跟下载会话无关。
        val localStream = !network && streamUrl != null
        if (localStream) {
            streamingRegistered = true
            streamedTorrentHash = torrentHash
            runCatching { (application as MagnetrailApp).engine.beginStreamingPlayback() }
        }

        val appSettings = (application as MagnetrailApp).appSettings
        preferHd = appSettings.preferHd
        subtitles = SubtitleController(appSettings, (application as MagnetrailApp).subtitleEngine)
        // 选档策略保持 ExoPlayer 默认的自适应（不锁死码率），
        // 「优先高清」只表现为"起播时选一次最高档"，卡住由卡顿保护自动退回。
        trackSelector = DefaultTrackSelector(this)
        val exo = ExoPlayer.Builder(this).setTrackSelector(trackSelector).build()
        player = exo

        // Streaming needs patient reads: the local server blocks until the needed piece
        // is downloaded, so the default 8s HTTP read timeout is too short.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(90_000)
            .setAllowCrossProtocolRedirects(true)
        // 网页视频：带上网页的 Referer/Cookie/UA，很多站点缺了就 403
        intent.getStringArrayExtra(EXTRA_HEADERS)?.let { lines ->
            val map = HashMap<String, String>()
            var userAgent: String? = null
            lines.forEach { line ->
                val at = line.indexOf(':')
                if (at > 0) {
                    val key = line.substring(0, at).trim()
                    val value = line.substring(at + 1).trim()
                    if (key.equals("User-Agent", ignoreCase = true)) userAgent = value else map[key] = value
                }
            }
            val ua = userAgent
            if (ua != null) runCatching { httpFactory.setUserAgent(ua) }
            if (map.isNotEmpty()) runCatching { httpFactory.setDefaultRequestProperties(map) }
        }
        dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        if (network) mediaKind = networkKind
        // 本地文件才能找到同目录的字幕文件；网络视频靠内置字幕轨 / 后续 AI
        // 磁力流：从 stream URL 反解 infoHash + index，拿到磁盘路径，供「边看边生成」读音频
        val livePath = filePath ?: run {
            val match = Regex("/stream/[0-9a-f]{20}/([0-9a-fA-F]{40})/(\\d+)")
                .find(streamUrl.orEmpty())
            match?.let {
                (application as MagnetrailApp).engine.filePath(
                    it.groupValues[1].lowercase(),
                    it.groupValues[2].toInt()
                )
            }
        }
        subtitles.attach(
            filePath,
            title,
            torrentHash ?: filePath ?: title,
            livePath,
            remoteUrl = if (network) streamUrl else null,
            remoteHeaders = intent.getStringArrayExtra(EXTRA_HEADERS)
        )
        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    pipAspect = Rational(videoSize.width, videoSize.height)
                    updatePipParams()
                    currentHeight = videoSize.height
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                updateQualityOptions(tracks)
                subtitles.updateTextTracks(tracks)
            }

            override fun onCues(cueGroup: CueGroup) {
                subtitles.onCues(cueGroup)
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                // 稳定播了 10 秒以上才算真恢复，避免"能播一下就崩"时重试计数被清零
                if (playing && System.currentTimeMillis() - lastErrorAtMs > 10_000) {
                    retryAttempt = 0
                    retryJob?.cancel()
                    retryJob = null
                    retrying = null
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                handlePlaybackError(error)
            }
        })
        startPlayback(intent.getLongExtra(EXTRA_START_MS, 0L))

        updatePipParams()
        applyImmersive()

        // 调试用：`-e debug_fail true` 会在播放 6 秒后注入一次假的播放错误，
        // 用来验证"自动重试 + 回到原进度"（仅 debug APK 生效）
        val debuggable =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // 调试用：`-e debug_gen_sub true` 直接触发离线字幕生成（跳过点 UI）
        if (debuggable && intent.getBooleanExtra(EXTRA_DEBUG_GEN_SUB, false)) {
            uiScope.launch {
                delay(2_000)
                val result = subtitles.generateAi()
                Log.i(TAG, "debug generate -> $result")
            }
        }
        // 调试用：`-e debug_live_sub true` 直接切到实时字幕（边看边生成）
        if (debuggable && intent.getBooleanExtra(EXTRA_DEBUG_LIVE_SUB, false)) {
            subtitles.applySource(SubtitleSource.AI_LIVE)
            subtitles.applyMode(SubtitleMode.ORIGINAL)
        }
        if (debuggable && intent.getBooleanExtra(EXTRA_DEBUG_FAIL, false)) {            val failDelay = intent.getIntExtra(EXTRA_DEBUG_FAIL_DELAY, 6_000).toLong()
            uiScope.launch {
                delay(failDelay.coerceAtLeast(1_000))
                handlePlaybackError(
                    androidx.media3.common.PlaybackException(
                        "debug: simulated playback failure",
                        null,
                        androidx.media3.common.PlaybackException.ERROR_CODE_UNSPECIFIED
                    )
                )
            }
        }

        setContent {
            val app = application as MagnetrailApp
            val aiEngine = app.subtitleEngine
            val aiState by aiEngine.state.collectAsState()
            MagnetrailTheme {
                val landscape =
                    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

                // 画中画是“小窗口重新布局”，不是整块界面等比缩小：
                // 按当前窗口宽度 / 全屏宽度算一个比例，字幕字号跟着缩，才不会占满小窗。
                val config = LocalConfiguration.current
                var fullWidthDp by remember { mutableStateOf(config.screenWidthDp) }
                if (!inPip) fullWidthDp = config.screenWidthDp
                val pipScale = if (inPip) {
                    (config.screenWidthDp.toFloat() / fullWidthDp.coerceAtLeast(1))
                        .coerceIn(0.15f, 1f)
                } else 1f
                var controlsVisible by remember { mutableStateOf(true) }
                var scrubbing by remember { mutableStateOf(false) }

                // 播放中 3 秒没操作就自动隐藏底部控制条（暂停/拖动时保持显示）
                LaunchedEffect(isPlaying, controlsVisible, scrubbing, landscape) {
                    if (controlsVisible && isPlaying && !scrubbing) {
                        delay(3_000)
                        if (isPlaying && !scrubbing) controlsVisible = false
                    }
                }

                // 生成任务在 Application 作用域跑；结束（成功或失败）后把新字幕读回来
                var wasGenerating by remember { mutableStateOf(false) }
                LaunchedEffect(aiState) {
                    when (aiState) {
                        is SubtitleState.Working -> wasGenerating = true
                        else -> if (wasGenerating) {
                            wasGenerating = false
                            subtitles.reload()
                        }
                    }
                }

                // 「自动」来源：等轨道解析完再决定要不要启动实时生成
                LaunchedEffect(subtitles.source, subtitles.liveTranslate, subtitles.hasFileCues()) {
                    if (subtitles.source == SubtitleSource.AUTO && !subtitles.hasFileCues()) {
                        delay(2_500)
                        if (subtitles.shouldAutoStartLive()) {
                            subtitles.startLive(app.subtitleScope)
                        }
                    }
                }

        // 防截屏（可选）：窗口级 FLAG_SECURE，截屏/录屏/任务缩略图都会变黑
        LaunchedEffect(subtitles.secureScreen) {
            if (subtitles.secureScreen) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

                // 进入画中画时关掉设置面板，避免被缩放到看不见
                LaunchedEffect(inPip) {
                    if (inPip) settingsOpen = false
                }

                // 实时（边看边生成）：切到 AI_LIVE 就自动开跑，切走就停
                LaunchedEffect(subtitles.source) {
                    if (subtitles.source == SubtitleSource.AI_LIVE) {
                        subtitles.startLive(app.subtitleScope)
                    } else {
                        subtitles.stopLive()
                    }
                }

                // 手动改源语言后，实时任务用新语言重新跑（首次组合不算“改动”）
                var appliedLang by remember { mutableStateOf(subtitles.sourceLanguage) }
                LaunchedEffect(subtitles.sourceLanguage) {
                    if (appliedLang != subtitles.sourceLanguage) {
                        appliedLang = subtitles.sourceLanguage
                        if (subtitles.source == SubtitleSource.AI_LIVE) {
                            subtitles.stopLive()
                            subtitles.startLive(app.subtitleScope)
                        }
                    }
                }

                // 位置/缓冲进度轮询（拖动时 SeekBar 内部自己接管）
                LaunchedEffect(Unit) {
                    while (true) {
                        player?.let { p ->
                            val pos = p.currentPosition
                            positionMs = pos
                            bufferedMs = p.bufferedPosition
                            durationMs = p.duration.coerceAtLeast(0L)
                            // 只在真的在播时才记住位置：重试失败/缓冲中不覆盖
                            if (pos > 0 && p.isPlaying) lastGoodPositionMs = pos

                            // 卡顿保护：锁着高码率但网速跟不上时自动退回自适应，
                            // 否则会一直转圈（6 Mbps 的 1080p 在 3 Mbps 网络上必卡）。
                            if (p.playbackState == Player.STATE_BUFFERING && !p.isPlaying) {
                                val now = System.currentTimeMillis()
                                if (stallSince == 0L) stallSince = now
                                else if (now - stallSince > STALL_FALLBACK_MS && chosenQuality != null) {
                                    val label = chosenQuality?.label ?: ""
                                    selectQuality(null)
                                    autoPickDone = true
                                    notifyQuality("网速跟不上 $label，已切回自动")
                                }
                            } else if (p.playbackState == Player.STATE_READY) {
                                stallSince = 0L
                            }
                        }
                        delay(250)
                    }
                }

                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exo
                                useController = false
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                // 字幕统一由我们自己的 Compose 层画；内置 SubtitleView 留着会重影
                                subtitleView?.visibility = View.GONE
                            }
                        },
                        update = { view -> view.useController = false },
                        modifier = Modifier.fillMaxSize()
                    )
                    // 点画面切换控制条
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { controlsVisible = !controlsVisible }
                            }
                    )
                    if (!inPip) {
                        if (landscape) {
                            // 横屏全屏：顶栏和底部控制条一起淡入淡出
                            AnimatedVisibility(
                                visible = controlsVisible,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier.align(Alignment.TopStart)
                            ) {
                                PlayerTopBar(
                                    title = title,
                                    streaming = localStream,
                                    network = network,
                                    transient = ephemeralHash != null,
                                    onBack = { finish() },
                                    onPip = { enterPip() },
                                    onRotate = { toggleOrientation() },
                                    onSettings = { settingsOpen = true },
                                    gradient = true
                                )
                            }
                        } else {
                            PlayerTopBar(
                                title = title,
                                streaming = localStream,
                                network = network,
                                transient = ephemeralHash != null,
                                onBack = { finish() },
                                onPip = { enterPip() },
                                onRotate = { toggleOrientation() },
                                onSettings = { settingsOpen = true },
                                modifier = Modifier.align(Alignment.TopStart)
                            )
                        }
                    }
                    if (!inPip) {
                        AnimatedVisibility(
                            visible = controlsVisible,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.BottomCenter)
                        ) {
                            PlayerBottomBar(
                                isPlaying = isPlaying,
                                positionMs = positionMs,
                                durationMs = durationMs,
                                bufferedMs = bufferedMs,
                                landscape = landscape,
                                qualityLabel = when {
                                    chosenQuality != null -> (chosenQuality?.label ?: "") + " 锁定"
                                    qualityOptions.size > 1 ->
                                        (if (currentHeight > 0) "${currentHeight}p" else "自动") + " 自动"
                                    qualityOptions.size == 1 -> qualityOptions.first().label
                                    else -> null
                                },
                                onQuality = { qualityDialogOpen = true },
                                onTogglePlay = {
                                    if (exo.isPlaying) exo.pause() else exo.play()
                                    controlsVisible = true
                                },
                                onSeek = { target -> exo.seekTo(target) },
                                onScrubbing = { scrubbing = it },
                                onToggleFullscreen = { toggleOrientation() }
                            )
                        }
                    }

                    // 字幕层：画中画时按窗口比例缩放字号（PiP 是重新布局，不是位图缩放）
                    subtitles.lineAt(positionMs)?.let { line ->
                        SubtitleOverlay(
                            line = line,
                            fontScale = subtitles.fontScale * pipScale,
                            bottomPadding = when {
                                inPip -> 4.dp
                                !controlsVisible -> 26.dp
                                subtitles.position == "middle" -> 180.dp
                                else -> 92.dp
                            },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                    // 设置侧栏：盖在画面上，点空白关闭
                    if (settingsOpen) {
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color(0x66000000))
                                    .clickable { settingsOpen = false }
                            )
                            androidx.compose.animation.AnimatedVisibility(
                                visible = settingsOpen,
                                enter = androidx.compose.animation.slideInHorizontally { it },
                                exit = androidx.compose.animation.slideOutHorizontally { it },
                                modifier = Modifier.align(Alignment.CenterEnd)
                            ) {
                                PlayerSettingsPanel(
                                    controller = subtitles,
                                    aiState = aiState,
                                    qualityLabels = qualityOptions.map { option ->
                                        "${option.label} · ${option.bitrate / 1000}k"
                                    },
                                    qualitySelected = chosenQuality?.let { chosen ->
                                        qualityOptions.indexOfFirst {
                                            it.group === chosen.group && it.index == chosen.index
                                        }
                                    } ?: -1,
                                    preferHd = preferHd,
                                    onSelectQuality = { index ->
                                        if (index < 0) selectQuality(null)
                                        else qualityOptions.getOrNull(index)?.let { selectQuality(it) }
                                    },
                                    onPreferHd = { applyPreferHd(it) },
                                    onAiInstall = {
                                        app.subtitleScope.launch { subtitles.installAiModels() }
                                    },
                                    onAiGenerate = {
                                        app.subtitleScope.launch { subtitles.generateAi() }
                                    },
                                    onAiCancel = { subtitles.cancelAi() },
                                    onClose = { settingsOpen = false }
                                )
                            }
                        }
                    }

                    retrying?.let { notice ->
                        Row(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Color(0xB3000000), RoundedCornerShape(10.dp))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.height(18.dp).width(18.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = notice,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    qualityNotice?.let { notice ->
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 110.dp)
                                .background(Color(0xB3000000), RoundedCornerShape(10.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = notice,
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    errorMessage?.let { message ->
                        Column(
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = message,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val resumePoint = (lastGoodPositionMs - REWIND_MS).coerceAtLeast(0L)
                                androidx.compose.material3.TextButton(onClick = {
                                    retryPlayback(lastGoodPositionMs, rewind = true)
                                }) {
                                    Text("从 ${clock(resumePoint)} 继续", color = Color.White)
                                }
                                androidx.compose.material3.TextButton(onClick = {
                                    retryPlayback(0L)
                                }) {
                                    Text("从头播放", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            if (qualityDialogOpen) {
                QualityDialog(
                    options = qualityOptions,
                    chosen = chosenQuality,
                    currentHeight = currentHeight,
                    preferHd = preferHd,
                    onSelect = { option ->
                        selectQuality(option)
                        qualityDialogOpen = false
                    },
                    onPreferHd = { applyPreferHd(it) },
                    onDismiss = { qualityDialogOpen = false }
                )
            }
        }
    }

    /**
     * 采集源里所有视频档位。
     *
     * 这是判断「有没有更高清地址」的唯一通用手段：HLS master / DASH manifest 的每一档
     * 都会成为一个 TrackGroup，ExoPlayer 解析完就摆在这里，不需要去猜 URL 命名规则。
     * 只有一个档位 = 站点确实没给更高清，超分才有意义。
     */
    private fun updateQualityOptions(tracks: Tracks) {
        val found = mutableListOf<QualityOption>()
        // HLS 是"每档一个 TrackGroup"，DASH 是"一个 TrackGroup 里多个 Format"，两种都收
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_VIDEO || !group.isSupported) return@forEach
            val trackGroup = group.mediaTrackGroup
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                if (format.width <= 0 || format.height <= 0) continue
                found.add(
                    QualityOption(
                        height = format.height,
                        width = format.width,
                        bitrate = format.bitrate,
                        group = trackGroup,
                        index = i,
                        selected = group.isTrackSelected(i)
                    )
                )
            }
        }
        // 同一分辨率有多个编码（avc/hevc）时只留码率高的那个，列表才不啰嗦
        val deduped = found
            .groupBy { it.width to it.height }
            .map { (_, list) -> list.maxByOrNull { it.bitrate } ?: list.first() }
            .sortedByDescending { it.height }

        if (deduped != qualityOptions) {
            qualityOptions = deduped
            Log.i(
                TAG,
                "video renditions:${deduped.size} " + deduped.joinToString(" ") {
                    "${it.label}@${it.bitrate / 1000}k${if (it.selected) "*" else ""}"
                }
            )
        }
        // 「起播就选最高档」：第一次拿到档位就锁最高，卡了交给卡顿保护退回
        if (preferHd && chosenQuality == null && !autoPickDone && deduped.size > 1) {
            autoPickDone = true
            selectQuality(deduped.first())
        }
    }

    /** null = 恢复自动；指定档位 = 锁定（HLS 会无缝换档，不重新起播）。 */
    private fun selectQuality(option: QualityOption?) {
        val exo = player ?: return
        val builder = exo.trackSelectionParameters.buildUpon()
        if (option == null) {
            builder.clearOverrides()
        } else {
            builder.setOverrideForType(TrackSelectionOverride(option.group, option.index))
        }
        exo.trackSelectionParameters = builder.build()
        chosenQuality = option
        stallSince = 0L
        Log.i(TAG, "quality -> ${option?.label ?: "auto"}")
    }

    /** 起播就挑最高档（持久化）；切换时立即生效，卡住由卡顿保护退回自动。 */
    private fun applyPreferHd(enabled: Boolean) {
        preferHd = enabled
        runCatching { (application as MagnetrailApp).appSettings.preferHd = enabled }
        if (enabled) {
            autoPickDone = true
            qualityOptions.firstOrNull()?.let { selectQuality(it) }
        } else if (chosenQuality != null) {
            selectQuality(null)
        }
    }

    /** 短暂提示（例如"网速跟不上，已切回自动"）。 */
    private fun notifyQuality(text: String) {
        qualityNotice = text
        Log.i(TAG, text)
        uiScope.launch {
            delay(5_000)
            if (qualityNotice == text) qualityNotice = null
        }
    }

    private fun startPlayback(startPositionMs: Long) {
        val exo = player ?: return
        val source = buildMediaSource()
        exo.setMediaSource(source, startPositionMs.coerceAtLeast(0L))
        exo.prepare()
        exo.playWhenReady = true
        android.util.Log.i(TAG, "startPlayback from ${startPositionMs.coerceAtLeast(0L)} ms")
    }

    /** 本地文件 / 本地 torrent 流走 Progressive；网页视频可能是 HLS(.m3u8) 或 DASH(.mpd)。 */
    private fun buildMediaSource(): MediaSource {
        val url = runCatching { mediaItem.localConfiguration?.uri.toString() }.getOrDefault("")
        val kind = mediaKind?.lowercase()
            ?: when {
                url.substringBefore('?').lowercase().endsWith(".m3u8") -> "hls"
                url.substringBefore('?').lowercase().endsWith(".mpd") -> "dash"
                else -> "progressive"
            }
        return when (kind) {
            "hls" -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            "dash" -> DashMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            else -> ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }
    }

    /** 播放出错：先自动重试，且每次都回到出错位置附近，别把进度清零。 */
    private fun handlePlaybackError(error: androidx.media3.common.PlaybackException) {
        lastErrorAtMs = System.currentTimeMillis()
        lastPlaybackError = error
        val pos = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)
        android.util.Log.i(TAG, "player error at ${pos}ms: ${error.errorCodeName} ${error.message}")
        errorMessage = null
        startAutoRetry(error)
    }

    private fun startAutoRetry(firstError: androidx.media3.common.PlaybackException?) {
        if (retryJob?.isActive == true) return
        retryJob = uiScope.launch {
            var last = firstError
            while (retryAttempt < MAX_RETRIES) {
                retryAttempt++
                val wait = RETRY_DELAYS_MS.getOrElse(retryAttempt - 1) { 15_000L }
                val resumeAt = (lastGoodPositionMs - REWIND_MS).coerceAtLeast(0L)
                retrying =
                    "播放中断，${wait / 1000}s 后从 ${clock(resumeAt)} 继续" +
                        "（第 $retryAttempt/$MAX_RETRIES 次）"
                delay(wait)
                // 退到后台/锁屏时别在后台偷偷重试
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    retrying = null
                    errorMessage = describe(last)
                    return@launch
                }
                startPlayback(resumeAt)
                delay(4_000)
                // 重试算成功 = 4 秒内回到了 READY 或已经在播（而不是又崩了）
                val exoNow = player
                if (exoNow != null &&
                    (exoNow.playbackState == Player.STATE_READY || exoNow.isPlaying)
                ) {
                    android.util.Log.i(
                        TAG,
                        "retry #$retryAttempt ok, position=${exoNow.currentPosition}"
                    )
                    retrying = null
                    lastPlaybackError = null
                    return@launch
                }
                last = lastPlaybackError ?: last
            }
            retrying = null
            errorMessage = describe(lastPlaybackError ?: last)
        }
    }

    private fun retryPlayback(startPositionMs: Long, rewind: Boolean = false) {
        retryJob?.cancel()
        retryJob = null
        retryAttempt = 0
        errorMessage = null
        retrying = null
        lastPlaybackError = null
        lastGoodPositionMs = startPositionMs
        startPlayback(if (rewind) (startPositionMs - REWIND_MS).coerceAtLeast(0L) else startPositionMs)
    }

    private fun describe(error: androidx.media3.common.PlaybackException?): String {
        if (error == null) return "播放失败"
        val detail = error.message?.takeIf { it.isNotBlank() }
        return if (detail == null) error.errorCodeName else "${error.errorCodeName}: $detail"
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val builder = PictureInPictureParams.Builder().setAspectRatio(pipAspect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
            builder.setSeamlessResizeEnabled(true)
        }
        runCatching { setPictureInPictureParams(builder.build()) }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val builder = PictureInPictureParams.Builder().setAspectRatio(pipAspect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
            builder.setSeamlessResizeEnabled(true)
        }
        runCatching { enterPictureInPictureMode(builder.build()) }
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun toggleOrientation() {
        requestedOrientation = if (isLandscape()) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /** Landscape playback goes full-screen (no status/navigation bar). */
    private fun applyImmersive() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isLandscape()) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersive()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP by itself (setAutoEnterEnabled above); older versions need this
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.R) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (!isInPictureInPictureMode && isFinishing) {
            // 小窗被关闭：活动正在结束，立刻停声
            player?.pause()
            player?.volume = 0f
        }
        if (!isInPictureInPictureMode) {
            // 关闭小窗 / 展开回全屏都会走到这里：
            // 300ms 后再判断，展开回全屏会处于 RESUMED（不停），被关掉则停在后台（停声）
            uiScope.launch {
                delay(300)
                if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    player?.pause()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        player?.volume = 1f
    }

    // 注意：不要在 onPause 里立即暂停 —— 进入画中画会先走 onPause，
    // 此时 isInPictureInPictureMode 还是 false，会把 PiP 的声音也停掉。
    // 改为延迟检查：真的不在前台、也不在 PiP 才停。
    override fun onPause() {
        super.onPause()
        val finishing = isFinishing
        uiScope.launch {
            delay(1_200)
            val background = !isInPictureInPictureMode &&
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (finishing || background) {
                player?.pause()
                player?.volume = 0f
            }
        }
    }

    override fun onStop() {
        super.onStop()
        recordWebHistory()
        android.util.Log.i(
            TAG,
            "onStop pip=$isInPictureInPictureMode playing=${player?.isPlaying}"
        )
        player?.pause()
        player?.volume = 0f
    }

    /** 网页视频退出时记下播放进度，供「视频历史」继续播放。 */
    private fun recordWebHistory() {
        if (!intent.getBooleanExtra(EXTRA_NETWORK, false)) return
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val p = player ?: return
        val duration = p.duration
        if (duration <= 0) return
        runCatching {
            (application as MagnetrailApp).browserStore.recordVideo(
                url = url,
                title = intent.getStringExtra(EXTRA_TITLE) ?: "",
                positionMs = p.currentPosition,
                durationMs = duration,
                headers = intent.getStringArrayExtra(EXTRA_HEADERS)
            )
        }
    }

    override fun onDestroy() {
        retryJob?.cancel()
        uiScope.cancel()
        if (streamingRegistered) {
            streamingRegistered = false
            runCatching { (application as MagnetrailApp).engine.endStreamingPlayback() }
        }
        // 关播放器后恢复多片并行下载（顺序下载只该在边下边看时开）
        streamedTorrentHash?.let { hash ->
            runCatching { (application as MagnetrailApp).engine.endStreamingFile(hash) }
        }
        // "缓冲观看": closing the player throws the temporary data away
        intent.getStringExtra(EXTRA_EPHEMERAL)?.let { hash ->
            runCatching { (application as MagnetrailApp).engine.stopTransient(hash) }
        }
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MagnetrailPlayer"
        private const val EXTRA_PATH = "extra_path"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_EPHEMERAL = "extra_ephemeral"
        private const val EXTRA_TORRENT = "extra_torrent"
        private const val EXTRA_NETWORK = "extra_network"
        private const val EXTRA_KIND = "extra_kind"
        private const val EXTRA_HEADERS = "extra_headers"
        private const val EXTRA_START_MS = "extra_start_ms"
        private const val EXTRA_DEBUG_GEN_SUB = "debug_gen_sub"
        private const val EXTRA_DEBUG_LIVE_SUB = "debug_live_sub"
        private const val EXTRA_DEBUG_FAIL = "debug_fail"
        private const val EXTRA_DEBUG_FAIL_DELAY = "debug_fail_delay"

        /** 出错自动重试次数与退避间隔；重试都从 lastGoodPositionMs 回退一点续播。 */
        private const val MAX_RETRIES = 5
        private const val REWIND_MS = 5_000L
        private val RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

        /** 锁定高码率后持续缓冲超过这个时间就退回自动选档。 */
        private const val STALL_FALLBACK_MS = 8_000L

        /** Local, fully downloaded file. */
        fun intent(context: android.content.Context, file: File, startMs: Long = 0L): Intent {
            val intent = Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_PATH, file.absolutePath)
                .putExtra(EXTRA_TITLE, file.name)
            if (startMs > 0L) intent.putExtra(EXTRA_START_MS, startMs)
            return intent
        }

        /** "边下边播": http url served by TorrentStreamServer. */
        fun streamIntent(
            context: android.content.Context,
            url: String,
            title: String,
            ephemeralHash: String? = null,
            torrentHash: String? = null
        ): Intent {
            val intent = Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
            if (ephemeralHash != null) intent.putExtra(EXTRA_EPHEMERAL, ephemeralHash)
            if (torrentHash != null) intent.putExtra(EXTRA_TORRENT, torrentHash)
            return intent
        }

        /** 网页视频：远端 http(s) 地址，可带 Referer/Cookie/UA 和 HLS/DASH 类型。 */
        fun networkIntent(
            context: android.content.Context,
            url: String,
            title: String,
            headers: Array<String>?,
            kind: String?,
            startMs: Long = 0L
        ): Intent {
            val intent = Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_NETWORK, true)
            if (headers != null && headers.isNotEmpty()) intent.putExtra(EXTRA_HEADERS, headers)
            if (!kind.isNullOrBlank()) intent.putExtra(EXTRA_KIND, kind)
            if (startMs > 0L) intent.putExtra(EXTRA_START_MS, startMs)
            return intent
        }
    }
}

@Composable
private fun PlayerTopBar(
    title: String,
    streaming: Boolean,
    network: Boolean,
    transient: Boolean,
    onBack: () -> Unit,
    onPip: () -> Unit,
    onRotate: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    gradient: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (gradient) {
                    // 横屏全屏：顶部渐隐，和底部控制条呼应
                    Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))
                } else {
                    SolidColor(Color(0xB3000000))
                }
            )
            .padding(
                start = 4.dp,
                end = 4.dp,
                top = if (gradient) 10.dp else 4.dp,
                bottom = if (gradient) 20.dp else 4.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = when {
                transient -> "$title · 缓冲观看（不保存）"
                streaming -> "$title · 边下边播"
                network -> "$title · 网页视频"
                else -> title
            },
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onSettings) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "设置", tint = Color.White)
        }
        IconButton(onClick = onRotate) {
            Icon(Icons.Rounded.ScreenRotation, contentDescription = "横屏/竖屏", tint = Color.White)
        }
        IconButton(onClick = onPip) {
            Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = "画中画", tint = Color.White)
        }
    }
}

@Composable
private fun PlayerBottomBar(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    landscape: Boolean,
    qualityLabel: String?,
    onQuality: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrubbing: (Boolean) -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))
            )
            .padding(
                horizontal = if (landscape) 28.dp else 10.dp,
                vertical = 8.dp
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onTogglePlay) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = Color.White
                )
            }
            SeekBar(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                onSeek = onSeek,
                onScrubbing = onScrubbing,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${clock(positionMs)} / ${clock(durationMs)}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
            if (qualityLabel != null) {
                TextButton(
                    onClick = onQuality,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 6.dp,
                        vertical = 2.dp
                    )
                ) {
                    Text(
                        qualityLabel,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    imageVector = if (landscape) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    contentDescription = if (landscape) "退出全屏" else "全屏",
                    tint = Color.White
                )
            }
        }
    }
}

/**
 * 自绘进度条：灰底=总长，浅灰=已缓冲，主题色=已播放，圆点=当前位置。
 * 支持点按跳转与拖动跳转；拖动期间位置不跟随播放器，松手才 seek。
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
    onScrubbing: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val playedFraction =
        if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val bufferedFraction =
        if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shown = if (dragging) dragFraction else playedFraction
    val active = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .height(34.dp)
            .pointerInput(durationMs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    dragFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                    onScrubbing(true)
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        pressed = change.pressed
                        if (pressed) {
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                            change.consume()
                        }
                    }
                    dragging = false
                    onScrubbing(false)
                    if (durationMs > 0) onSeek((dragFraction * durationMs).toLong())
                }
            }
    ) {
        val cy = size.height / 2f
        val trackHeight = 3.dp.toPx()
        val thumbRadius = 7.dp.toPx()
        val usable = (size.width - thumbRadius * 2).coerceAtLeast(1f)
        val left = thumbRadius
        val top = cy - trackHeight / 2f
        val radius = CornerRadius(trackHeight / 2f)

        drawRoundRect(
            color = Color(0x33FFFFFF),
            topLeft = Offset(left, top),
            size = Size(usable, trackHeight),
            cornerRadius = radius
        )
        if (bufferedFraction > 0f) {
            drawRoundRect(
                color = Color(0x80FFFFFF),
                topLeft = Offset(left, top),
                size = Size(usable * bufferedFraction, trackHeight),
                cornerRadius = radius
            )
        }
        if (shown > 0f) {
            drawRoundRect(
                color = active,
                topLeft = Offset(left, top),
                size = Size(usable * shown, trackHeight),
                cornerRadius = radius
            )
        }
        drawCircle(
            color = active,
            radius = if (dragging) thumbRadius * 1.4f else thumbRadius,
            center = Offset(left + usable * shown, cy)
        )
    }
}

/** 02:03 / 1:12:30 */
private fun clock(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

/** 源里的一个画质档位（来自 ExoPlayer 解析好的 TrackGroup，不是猜出来的）。 */
private data class QualityOption(
    val height: Int,
    val width: Int,
    val bitrate: Int,
    val group: TrackGroup,
    val index: Int,
    val selected: Boolean
) {
    val label: String get() = if (height > 0) "${height}p" else "原画"
    val detail: String get() = buildString {
        append("$width×$height")
        if (bitrate > 0) append(" · ${"%.1f".format(bitrate / 1_000_000f)} Mbps")
    }
}

/**
 * 清晰度面板：列出源里真实存在的档位。
 *
 * 通用方案的核心就是这个列表 —— 只有一档时它直接告诉用户「站点没有更高清」，
 * 那才轮到超分；有多档时可以一键锁定，也能开「起播就选最高档」。
 */
@Composable
private fun QualityDialog(
    options: List<QualityOption>,
    chosen: QualityOption?,
    currentHeight: Int,
    preferHd: Boolean,
    onSelect: (QualityOption?) -> Unit,
    onPreferHd: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清晰度（${options.size} 档）") },
        text = {
            Column {
                if (options.size <= 1) {
                    Text(
                        "这个源只有 ${options.firstOrNull()?.label ?: "一"} 一档，" +
                            "站点没有再给更高清的地址（换线路/换站点才有用，超分也不会凭空长出细节）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                QualityRow(
                    title = "自动（按带宽自适应）",
                    detail = "起播快；带宽好时会自己升到最高档",
                    checked = chosen == null,
                    onClick = { onSelect(null) }
                )
                options.forEach { option ->
                    val isChosen = chosen != null &&
                        chosen.group === option.group && chosen.index == option.index
                    QualityRow(
                        title = if (option.height == currentHeight && chosen == null)
                            "${option.label}（正在播放）"
                        else option.label,
                        detail = option.detail,
                        checked = isChosen,
                        onClick = { onSelect(option) }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("起播就选最高档", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "多档站点默认起播往往从低档开始，打开后直接上最高码率",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = preferHd, onCheckedChange = onPreferHd)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun QualityRow(
    title: String,
    detail: String,
    checked: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = checked, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 自绘字幕层：中文（或唯一语言）主行 + 可选原文小字。
 *
 * 放在播放器画面之上、控制条之上（由调用方传 bottomPadding），
 * 不参与点击事件，所以不会抢「点画面显示/隐藏控制条」的手势。
 */
@Composable
private fun SubtitleOverlay(
    line: SubtitleLine,
    fontScale: Float,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(start = 20.dp, end = 20.dp, bottom = bottomPadding)
            .background(Color(0x73000000), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = line.primary,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 16.sp * fontScale,
                lineHeight = 21.sp * fontScale,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                shadow = Shadow(color = Color.Black, offset = Offset.Zero, blurRadius = 8f)
            ),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        line.secondary?.let { secondary ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = secondary,
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp * fontScale,
                    lineHeight = 16.sp * fontScale,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(color = Color.Black, offset = Offset.Zero, blurRadius = 6f)
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
