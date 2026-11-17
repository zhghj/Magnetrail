package app.magnetrail.shared

/**
 * 字幕的数据模型与显示模式。
 *
 * 设计要点：一条字幕同时带「原文」和「译文」，用 [SubtitleMode] 决定怎么显示，
 * 而不是给每种模式各存一份字幕表 —— 这样切模式是纯 UI 行为，不用重新生成。
 */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translated: String? = null
) {
    /** 结束时间兜底：坏字幕常见 end<=start，给 1s。 */
    val safeEndMs: Long get() = if (endMs > startMs) endMs else startMs + 1_000

    /** 按模式取出要显示的内容；CHINESE 没有译文时退回原文，避免整段空白。 */
    fun lineFor(mode: SubtitleMode): SubtitleLine? = when (mode) {
        SubtitleMode.OFF -> null
        SubtitleMode.ORIGINAL -> text.takeIf { it.isNotBlank() }?.let { SubtitleLine(it, null) }
        SubtitleMode.CHINESE -> {
            val shown = translated?.takeIf { it.isNotBlank() } ?: text
            shown.takeIf { it.isNotBlank() }?.let { SubtitleLine(it, null) }
        }
        SubtitleMode.BILINGUAL -> {
            val primary = translated?.takeIf { it.isNotBlank() } ?: text
            if (primary.isBlank()) null
            else SubtitleLine(primary, text.takeIf { it.isNotBlank() && it != primary })
        }
    }
}

/** 真正交给 UI 画的一行：主行 + 可选的次要小字（双语时是原文）。 */
data class SubtitleLine(val primary: String, val secondary: String?)

enum class SubtitleMode {
    /** 关闭：不显示任何字幕。 */
    OFF,

    /** 只显示原文（日语/英语）。 */
    ORIGINAL,

    /** 只显示中文；没有译文时退回原文。 */
    CHINESE,

    /** 中文在上、原文小字在下。 */
    BILINGUAL;

    val label: String
        get() = when (this) {
            OFF -> "关闭"
            ORIGINAL -> "原文"
            CHINESE -> "中文"
            BILINGUAL -> "双语"
        }
}

/** 字幕从哪来。AUTO 按「内置 → 本地文件 → AI」的顺序挑可用的。 */
enum class SubtitleSource {
    AUTO,
    EMBEDDED,     // 容器/流里的字幕轨（mkv 内嵌、HLS 的 WebVTT…）
    FILE,         // 同目录同名 .srt
    AI_OFFLINE,   // 本地识别 + 翻译，提前生成
    AI_LIVE;      // 播放管线实时识别

    val label: String
        get() = when (this) {
            AUTO -> "自动"
            EMBEDDED -> "内置字幕"
            FILE -> "本地字幕文件"
            AI_OFFLINE -> "AI 生成（离线）"
            AI_LIVE -> "AI 生成（实时）"
        }

    /** 还没实现的来源在 UI 里要显示成不可选。 */
    val needsAi: Boolean get() = this == AI_OFFLINE || this == AI_LIVE
}

/** AI 字幕引擎的状态，UI 直接显示它的 message。 */
sealed interface SubtitleState {
    data object Idle : SubtitleState

    data class Working(val progress: Float, val message: String) : SubtitleState

    /** 需要先下载模型（APK 里不带，首次使用时下）。 */
    data class NeedsModel(val message: String) : SubtitleState

    data class Error(val message: String) : SubtitleState
}

sealed interface SubtitleResult {
    data object Ok : SubtitleResult
    data object NeedsModel : SubtitleResult
    data class Failed(val message: String) : SubtitleResult
}
