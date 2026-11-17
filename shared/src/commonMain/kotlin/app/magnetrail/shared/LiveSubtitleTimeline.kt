package app.magnetrail.shared

/**
 * 实时字幕的时序模型：用「缓冲窗口」把识别结果钉到媒体时间轴上。
 *
 * 实时字幕有两个时间来源，必须只信一个：
 *  - 识别器只知道「这段音频的第几个样本」（与媒体时间一一对应，精确，但没有绝对时间）；
 *  - 播放器知道 position / bufferedPosition（会 seek、会停、会跳，但它是唯一的真相）。
 *
 * 做法：以播放器为锚，**每次取一段音频（缓冲窗口）就重新记录它的媒体起点**，
 * 识别出的偏移量加上这个起点就是字幕时间。这样：
 *  - 不需要墙钟，暂停/缓冲/卡顿都不会漂移；
 *  - 只有落在 `bufferedEnd` 内的结果才算数（播放器还没拿到的内容不该提前显示）；
 *  - seek 之后从新位置重新开窗，旧的偏移全部作废。
 */
class LiveSubtitleTimeline {

    /** 当前窗口第一帧样本对应的媒体时间（毫秒）。 */
    var windowStartMs: Long = 0L
        private set

    private val cues = ArrayList<SubtitleCue>()

    /** 已生成的 cue（按窗口追加顺序）。 */
    val generated: List<SubtitleCue> get() = cues

    /**
     * 开始一段新窗口。
     *
     * @param mediaStartMs 这段音频第一帧在**媒体时间轴**上的位置：
     *   起播取 `player.currentPosition`，seek 后取新的 position，
     *   本地边下边看则取「已下载但尚未识别」的起点。
     */
    fun beginWindow(mediaStartMs: Long) {
        windowStartMs = mediaStartMs.coerceAtLeast(0L)
    }

    /**
     * 追加一条识别结果（偏移量相对于当前窗口）。
     *
     * @param bufferedEndMs 播放器当前已缓冲到的媒体时间；超出它的结果直接丢弃，
     *   跨过它的部分会被截断 —— 用户看不到的部分没有必要提前出现在屏幕上。
     */
    fun addCue(
        offsetStartMs: Long,
        offsetEndMs: Long,
        text: String,
        bufferedEndMs: Long = Long.MAX_VALUE
    ): SubtitleCue? {
        if (text.isBlank()) return null
        val start = windowStartMs + offsetStartMs.coerceAtLeast(0L)
        val end = windowStartMs + offsetEndMs.coerceAtLeast(offsetStartMs)
        if (start >= bufferedEndMs) return null
        val clipped = minOf(end, bufferedEndMs)
        if (clipped <= start) return null
        val cue = SubtitleCue(start, clipped, text.trim())
        cues.add(cue)
        return cue
    }

    /**
     * 当前该显示哪一条（UI 每帧调用）。
     *
     * @param staleGraceMs 识别晚于画面时的宽限：结束时间已经早于 `position - grace`
     *   的 cue 直接丢掉，否则会出现「话早说完了，字幕才蹦出来」。
     */
    fun visible(positionMs: Long, staleGraceMs: Long = 1_200L): SubtitleCue? {
        val cue = cues.lastOrNull { it.startMs <= positionMs } ?: return null
        val effectiveEnd = cue.safeEndMs + staleGraceMs
        if (positionMs > effectiveEnd) return null
        return cue.takeIf { positionMs >= it.startMs }
    }

    /** seek / 换片时清空已生成内容。 */
    fun reset() {
        cues.clear()
        windowStartMs = 0L
    }
}
