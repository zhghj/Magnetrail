package app.magnetrail.shared

import kotlinx.coroutines.flow.StateFlow

/**
 * AI 字幕引擎（语音识别 + 翻译）的平台契约。
 *
 * 和 [TorrentEngine] 一样：接口在 commonMain，Android 用本地模型实现，
 * desktop 只有 stub（保证 :shared:desktopTest 能一直跑）。
 *
 * P1 只把接口和状态立起来，两个平台都返回「模型未安装」，UI 据此把 AI 选项禁用；
 * P2 起在同一接口后面换成真正实现，UI 不用改。
 */
interface SubtitleEngine {

    /** AI 能力是否就绪（模型已下载、运行时可用）。 */
    val available: Boolean

    /** 状态文案：未安装 / 生成中 37% / 错误。 */
    val state: StateFlow<SubtitleState>

    /**
     * 生成字幕（P2 实现）。
     *
     * @param key 缓存键（infohash + 文件名 + 模型版本），同一影片只生成一次
     * @param audio 音频来源：本地文件路径，或本地流 url
     * @param language 源语言（"ja" / "en" / null = 自动检测）
     * @param translateTo 译文语言（"zh" / null = 不翻译）
     */
    suspend fun generateOffline(
        key: String,
        audio: String,
        language: String?,
        translateTo: String?
    ): SubtitleResult

    /** 下载/安装模型（P2：SenseVoice int8，约 228MB）。默认不支持。 */
    suspend fun installModels(): Boolean = false

    /**
     * 边看边生成：识别过程中把「目前累积的字幕」回调出去，播放器不用等整部跑完。
     * 默认退回一次性生成（没有增量能力时仍然可用）。
     */
    suspend fun generateProgressive(
        key: String,
        audio: String,
        onCues: (List<SubtitleCue>) -> Unit
    ): SubtitleResult = generateOffline(key, audio, null, null)

    /**
     * 逐段生成（支持还没下载完的文件）：每段完成即回调，并可同时翻译。
     * 默认退回 [generateProgressive]。
     */
    suspend fun generateIncremental(
        key: String,
        audio: String,
        translateTo: String?,
        onCues: (List<SubtitleCue>) -> Unit
    ): SubtitleResult = generateProgressive(key, audio, onCues)

    /** 取消正在进行的生成。 */
    fun cancel()
}

expect fun createSubtitleEngine(modelRoot: String, workDir: String): SubtitleEngine
