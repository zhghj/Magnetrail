package app.magnetrail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import app.magnetrail.shared.SubtitleCue
import app.magnetrail.shared.SubtitleCues
import app.magnetrail.shared.SubtitleEngine
import app.magnetrail.shared.SubtitleLine
import app.magnetrail.shared.SubtitleMode
import app.magnetrail.shared.SubtitlePagination
import app.magnetrail.shared.SubtitleResult
import app.magnetrail.shared.SubtitleSource
import app.magnetrail.shared.SubtitleState
import app.magnetrail.shared.AndroidSubtitleEngine
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 播放器侧的字幕控制（P1）。
 *
 * 两种来源都已接通：
 *  - **内置字幕轨**：ExoPlayer 自己解码，通过 `onCues` 把当前这句交给我们（我们只负责画）；
 *  - **本地字幕文件**：同目录同名的 `.srt`（含 `.zh.srt` / `.ja.srt` 之类的语言后缀）。
 *
 * 统一由 Compose 自绘一层字幕，不用 PlayerView 内置的 SubtitleView —— 只有一个渲染点，
 * 字号/位置/双语排版都只改一处。AI 两档在 P1 里只显示状态，P2/P3 接上 engine 即可。
 */
class SubtitleController(
    private val settings: AppSettings,
    private val engine: SubtitleEngine
) {

    var mode by mutableStateOf(settings.subtitleMode)
        private set

    var source by mutableStateOf(settings.subtitleSource)
        private set

    /** 给用户看的一句话状态：内置字幕 2 轨 / 本地字幕 2 个 / 无字幕。 */
    var status by mutableStateOf("无字幕")
        private set

    var embeddedTracks by mutableStateOf(0)
        private set

    var embeddedLabels by mutableStateOf<List<String>>(emptyList())
        private set

    private var sidecarFiles: List<File> = emptyList()

    /** 合成后的字幕表（原/译已合并）。 */
    private var cues: List<SubtitleCue> = emptyList()

    /** ExoPlayer 当前要显示的内置字幕文本（`onCues` 推过来的）。 */
    private var embeddedText by mutableStateOf<String?>(null)

    private var previousMode: SubtitleMode = settings.subtitleMode.takeIf { it != SubtitleMode.OFF }
        ?: SubtitleMode.ORIGINAL

    private var mediaPath: String? = null
    private var mediaKey: String? = null

    /** 实时生成用的音频来源：本地文件路径，或磁力流的本地磁盘路径。 */
    private var liveSource: String? = null

    /** 网页视频：旁路取音频用的 url + 请求头（Referer/Cookie/UA）。 */
    private var remoteUrl: String? = null
    private var remoteHeaders: Array<String>? = null

    /** 实时（边看边生成）累积的字幕。 */
    private var liveCues by mutableStateOf<List<SubtitleCue>>(emptyList())
    var liveActive by mutableStateOf(false)
        private set
    var liveCursorMs by mutableStateOf(0L)
        private set

    // ------------------------------------------------------------ 生命周期

    /** 打开影片时调用；[path] 为 null 表示网络视频（没有同目录字幕文件可找）。 */
    fun attach(
        path: String?,
        title: String,
        key: String? = null,
        liveSource: String? = null,
        remoteUrl: String? = null,
        remoteHeaders: Array<String>? = null
    ) {
        mediaPath = path
        mediaKey = key ?: title
        this.liveSource = liveSource ?: path
        this.remoteUrl = remoteUrl
        this.remoteHeaders = remoteHeaders
        cues = emptyList()
        sidecarFiles = emptyList()
        if (path != null) loadSidecars(File(path))
        refreshStatus()
        Log.i(TAG, "attach path=$path status=$status mode=$mode source=$source cues=${cues.size}")
    }

    fun detach() {
        embeddedText = null
        cues = emptyList()
        sidecarFiles = emptyList()
        mediaPath = null
    }

    /** 重新扫描本地字幕文件（用户刚丢进来一个 .srt 时用）。 */
    fun reload() {
        mediaPath?.let { loadSidecars(File(it)) }
        refreshStatus()
    }

    // ------------------------------------------------------------ 来源

    /** `onTracksChanged` 里统计容器/流里的字幕轨。 */
    fun updateTextTracks(tracks: Tracks) {
        var count = 0
        val labels = mutableListOf<String>()
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_TEXT || !group.isSupported) return@forEach
            count++
            val format = group.getTrackFormat(0)
            labels.add(
                format.label?.takeIf { it.isNotBlank() }
                    ?: format.language?.takeIf { it.isNotBlank() }
                    ?: "字幕 $count"
            )
        }
        if (count != embeddedTracks || labels != embeddedLabels) {
            embeddedTracks = count
            embeddedLabels = labels
            refreshStatus()
        }
    }

    /** ExoPlayer 解码出来的内置字幕：空 cue 表示这句结束了。 */
    fun onCues(cueGroup: CueGroup) {
        val text = cueGroup.cues
            .mapNotNull { it.text?.toString() }
            .joinToString("\n")
            .trim()
        embeddedText = text.ifBlank { null }
    }

    // ------------------------------------------------------------ 显示

    /** 当前位置该显示的那一行；没有就返回 null（UI 直接不画）。 */
    fun lineAt(positionMs: Long): SubtitleLine? {
        if (mode == SubtitleMode.OFF) return null
        // 时间轴微调只作用于我们自己的 cue 表（内置字幕轨由 ExoPlayer 直接输出，改不了）
        val cursor = (positionMs - offsetMs).coerceAtLeast(0L)
        val effective = when (source) {
            SubtitleSource.AUTO -> when {
                // 有已经生成/下载好的字幕文件 → 优先用它（省算力）
                cues.isNotEmpty() -> SubtitleSource.FILE
                // 实时字幕已经生成过：关闭翻译后仍要显示（原文），不能整条字幕消失
                liveCues.isNotEmpty() -> SubtitleSource.AI_LIVE
                // 实时翻译开着且正在跑
                liveTranslate && liveActive -> SubtitleSource.AI_LIVE
                // 内置字幕轨通常没有译文，放到最后
                embeddedTracks > 0 -> SubtitleSource.EMBEDDED
                else -> null
            }

            else -> source
        }
        return when (effective) {
            SubtitleSource.EMBEDDED -> embeddedText?.let { SubtitleLine(it, null) }
            // AI 离线生成的产物也是一个普通 .srt：再次进入直接读它，不用重新生成
            SubtitleSource.FILE, SubtitleSource.AI_OFFLINE ->
                SubtitleCues.at(cues, cursor)?.let {
                    SubtitlePagination.pageAt(it, mode, cursor, pageChars())
                }

            // 实时：边识别边显示，时间轴同样来自媒体时间（不是墙钟）
            SubtitleSource.AI_LIVE ->
                SubtitleCues.at(liveCues, cursor)?.let {
                    // 关掉「实时翻译」后强制只显示原文，即使后台已经生成过译文
                    val displayMode = if (liveTranslate) mode else SubtitleMode.ORIGINAL
                    SubtitlePagination.pageAt(it, displayMode, cursor, pageChars())
                }

            else -> null
        }
    }

    fun applyMode(value: SubtitleMode) {
        mode = value
        if (value != SubtitleMode.OFF) previousMode = value
        settings.subtitleMode = value
    }

    /** 字幕开关：关 → 恢复上次的模式。 */
    fun toggle() {
        applyMode(if (mode == SubtitleMode.OFF) previousMode else SubtitleMode.OFF)
    }

    fun applySource(value: SubtitleSource) {
        source = value
        settings.subtitleSource = value
        if (value.needsAi && mode == SubtitleMode.OFF) {
            // 选了 AI 来源却还关着字幕，等于点了没反应 —— 顺手打开
            applyMode(previousMode)
        }
        refreshStatus()
    }

    /** AI 档位的状态文案（P2 起会有真实进度）。 */
    fun aiStatusText(): String = when (val state = engine.state.value) {
        SubtitleState.Idle -> if (engine.available) "模型已就绪" else "模型未安装"
        is SubtitleState.Working -> "${state.message} ${(state.progress * 100).toInt()}%"
        is SubtitleState.NeedsModel -> state.message
        is SubtitleState.Error -> state.message
    }

    /** AI 字幕能不能直接用（模型已下载）。 */
    fun aiAvailable(): Boolean = engine.available

    /** 生成时是否同时出译文（设置项，默认开）。 */
    var bilingualOnGenerate by mutableStateOf(settings.subtitleBilingual)
        private set

    /** 译文语言，默认中文。 */
    var secondLanguage by mutableStateOf(settings.subtitleSecondLanguage)
        private set

    /** 源语言覆盖：空串=自动。 */
    var sourceLanguage by mutableStateOf(settings.subtitleSourceLanguage)
        private set

    /** 实时翻译独立开关（默认开）。 */
    var liveTranslate by mutableStateOf(settings.liveTranslate)
        private set

    /** 防截屏（FLAG_SECURE）。 */
    var secureScreen by mutableStateOf(settings.secureScreen)
        private set

    fun applySecureScreen(on: Boolean) {
        secureScreen = on
        settings.secureScreen = on
    }

    fun applyLiveTranslate(on: Boolean) {
        liveTranslate = on
        settings.liveTranslate = on
    }

    fun chooseSourceLanguage(tag: String) {
        sourceLanguage = tag
        settings.subtitleSourceLanguage = tag
    }

    private fun applyForcedLanguage() {
        (engine as? AndroidSubtitleEngine)?.forcedSourceLang = sourceLanguage.ifBlank { null }
    }

    /** 字号倍率 / 位置 / 时间轴偏移（显示设置）。 */
    var fontScale by mutableStateOf(settings.subtitleFontScale)
        private set
    var position by mutableStateOf(settings.subtitlePosition)
        private set
    var offsetMs by mutableStateOf(settings.subtitleOffsetMs)
        private set

    fun setBilingual(on: Boolean) {
        bilingualOnGenerate = on
        settings.subtitleBilingual = on
    }

    fun chooseSecondLanguage(tag: String) {
        secondLanguage = tag
        settings.subtitleSecondLanguage = tag
    }

    fun applyFontScale(value: Float) {
        fontScale = value
        settings.subtitleFontScale = value
    }

    fun applyPosition(value: String) {
        position = value
        settings.subtitlePosition = value
    }

    /** 时间轴微调，步进 200ms；正数=字幕往后推（画面先到、字幕后到）。 */
    fun bumpOffset(deltaMs: Int) {
        offsetMs = (offsetMs + deltaMs).coerceIn(-5_000, 5_000)
        settings.subtitleOffsetMs = offsetMs
    }

    fun resetOffset() {
        offsetMs = 0
        settings.subtitleOffsetMs = 0
    }

    /** 取消正在进行的 AI 生成。 */
    fun cancelAi() = engine.cancel()

    // ------------------------------------------------------------ 实时（边看边生成）

    /**
     * 边看边生成：从片头开始识别，**每识别完一块就把累计字幕交给播放器**，
     * 所以不用等整部跑完就能看到前面的字幕。
     *
     * 只对本地文件有效（网络视频没有可直接解码的音轨，需要另外接 PCM 旁路）。
     */
    fun startLive(scope: kotlinx.coroutines.CoroutineScope) {
        if (!engine.available || liveActive) return
        applyForcedLanguage()
        val file = liveSource
        val remote = remoteUrl
        if (file == null && remote == null) return
        liveActive = true
        liveCues = emptyList()
        liveCursorMs = 0L
        // 实时翻译用独立开关，不再依赖离线生成的「生成译文」
        val target = if (liveTranslate) secondLanguage else null
        scope.launch {
            try {
                if (file != null) {
                    engine.generateIncremental(mediaKey ?: file, file, target) { cues ->
                        liveCues = cues
                        liveCursorMs = cues.lastOrNull()?.endMs ?: 0L
                    }
                } else if (remote != null) {
                    (engine as? AndroidSubtitleEngine)?.generateIncrementalRemote(
                        mediaKey ?: remote, remote, remoteHeaders, target
                    ) { cues ->
                        liveCues = cues
                        liveCursorMs = cues.lastOrNull()?.endMs ?: 0L
                    }
                }
            } finally {
                liveActive = false
            }
        }
    }

    /** 能不能用实时模式：有可解码的音源（本地/磁力流文件，或网页视频 URL）+ 模型已装。 */
    fun canLive(): Boolean = (liveSource != null || remoteUrl != null) && engine.available

    /**
     * 「自动」来源在没有本地字幕时启动实时生成：
     * 即使有内置字幕轨也会走实时（内置轨通常没有译文）。
     */
    fun shouldAutoStartLive(): Boolean =
        source == SubtitleSource.AUTO &&
            cues.isEmpty() &&
            !liveActive &&
            liveTranslate &&
            canLive()

    fun stopLive() {
        if (!liveActive) return
        engine.cancel()
        liveActive = false
    }

    /** 实时状态文案（面板里显示）。 */
    fun liveStatusText(): String = when {
        !engine.available -> "需要先下载模型"
        mediaPath == null -> "只支持本地文件（网络视频需要 PCM 旁路）"
        liveActive -> "边看边生成中 · 已识别到 ${fmtClock(liveCursorMs)}"
        liveCues.isNotEmpty() -> "已生成 ${liveCues.size} 句"
        else -> "空闲：切到「AI 实时」后从头边看边生成"
    }

    private fun fmtClock(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }

    /** 单页最多显示多少字：字号越大，每页越短（屏幕上还是两行左右）。 */
    private fun pageChars(): Int = (24 / fontScale).toInt().coerceIn(14, 34)

    /** 触发 AI 离线生成；成功后自动切到「本地字幕文件」来源。 */
    suspend fun generateAi(language: String? = null): SubtitleResult {
        val path = mediaPath ?: return SubtitleResult.Failed("网络视频暂不支持离线生成")
        val key = mediaKey ?: path
        applyForcedLanguage()
        val target = if (bilingualOnGenerate) secondLanguage else null
        val result = engine.generateOffline(key, path, language, target)
        if (result is SubtitleResult.Ok) {
            loadSidecars(File(path))
            refreshStatus()
            applySource(SubtitleSource.FILE)
            if (mode == SubtitleMode.OFF) applyMode(SubtitleMode.BILINGUAL)
        }
        return result
    }

    /** 下载 AI 模型（228MB）。 */
    suspend fun installAiModels(): Boolean {
        val ok = engine.installModels()
        if (ok) {
            mediaPath?.let { loadSidecars(File(it)) }
            refreshStatus()
        }
        return ok
    }

    /** 有没有可显示的字幕（供 UI 决定要不要提示「无字幕」）。 */
    fun hasAnySubtitle(): Boolean = embeddedTracks > 0 || cues.isNotEmpty()

    /** 是否已经有本地/生成的字幕文件（自动模式判断用）。 */
    fun hasFileCues(): Boolean = cues.isNotEmpty()

    // ------------------------------------------------------------ 本地字幕文件

    /**
     * 找同目录同名的 .srt。
     *
     * 语言后缀按常见写法分类：zh/chi/chs/sc/中文 → 译文，其余（ja/jp/jpn/en/eng）→ 原文。
     * 只有一个文件时当作原文，CHINESE 模式下退回显示它（比空白好）。
     */
    private fun loadSidecars(video: File) {
        val dir = video.parentFile ?: return
        val base = video.nameWithoutExtension.lowercase()
        val candidates = dir.listFiles { f ->
            f.isFile && f.name.substringAfterLast('.', "").lowercase() == "srt" &&
                f.nameWithoutExtension.lowercase().startsWith(base)
        } ?: return
        if (candidates.isEmpty()) {
            sidecarFiles = emptyList()
            cues = emptyList()
            return
        }

        var original: File? = null
        var translated: File? = null
        candidates.sortedBy { it.name }.forEach { file ->
            val suffix = file.nameWithoutExtension.lowercase()
                .removePrefix(base)
                .trim('.', '_', '-', ' ')
            when {
                suffix.isBlank() -> if (original == null) original = file
                ZH_MARKERS.any { suffix.contains(it) } -> if (translated == null) translated = file
                else -> if (original == null) original = file
            }
        }

        sidecarFiles = listOfNotNull(original, translated)
        val srcCues = original?.let { parseFile(it) } ?: emptyList()
        val dstCues = translated?.let { parseFile(it) } ?: emptyList()
        cues = when {
            srcCues.isEmpty() -> dstCues
            dstCues.isEmpty() -> srcCues
            else -> SubtitleCues.mergeLanguages(srcCues, dstCues)
        }
        Log.i(
            TAG,
            "sidecars=${sidecarFiles.map { it.name }} src=${srcCues.size} " +
                "dst=${dstCues.size} merged=${cues.size}"
        )
    }

    private fun parseFile(file: File): List<SubtitleCue> = runCatching {
        val raw = readTextSmart(file)
        val parsed = SubtitleCues.parseSrt(raw)
        Log.i(
            TAG,
            "parse ${file.name}: bytes=${file.length()} chars=${raw.length} cues=${parsed.size} " +
                "head=${raw.take(24).replace("\n", "\\n")}"
        )
        parsed
    }.onFailure { Log.w(TAG, "parse ${file.name} failed", it) }
        .getOrDefault(emptyList())

    /** SRT 编码混乱：先按严格 UTF-8 解码，失败再按 GBK（老字幕组常用）。 */
    private fun readTextSmart(file: File): String {
        val bytes = file.readBytes()
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        return utf8 ?: String(bytes, Charset.forName("GBK"))
    }

    private fun refreshStatus() {
        status = when {
            embeddedTracks > 0 && sidecarFiles.isNotEmpty() ->
                "内置字幕 ${embeddedTracks} 轨 · 本地字幕 ${sidecarFiles.size} 个"

            embeddedTracks > 0 -> "内置字幕 ${embeddedTracks} 轨"
            sidecarFiles.isNotEmpty() -> "本地字幕 ${sidecarFiles.size} 个"
            source.needsAi -> aiStatusText()
            else -> "无字幕"
        }
    }

    companion object {
        private const val TAG = "MagnetrailSubtitle"
        private val ZH_MARKERS = listOf(
            "zh", "chi", "cht", "chs", "sc", "tc", "cn", "chinese", "中文", "简体", "繁體", "繁体"
        )
    }
}
