package app.magnetrail.shared

/**
 * 字幕的纯逻辑：SRT 解析、时间轴查找、可读性后处理、原/译匹配。
 *
 * 全部是不依赖 Android 的纯函数，所以能在 commonTest 里覆盖边界（seek、重叠、
 * 坏时间戳、双语合并），不用上机也能保证「字幕不多不少正好卡在对应时间」。
 */
object SubtitleCues {

    /** 一句说完后多留一会儿，避免字幕一跳一跳；但不会超过下一句的开始。 */
    const val HOLD_MS = 900L

    /** 最短显示时长，太短的句子人眼根本没读到。 */
    const val MIN_DURATION_MS = 1_000L

    /**
     * 解析 SRT 文本。
     *
     * 容错策略（宁可少一条，也不要把垃圾显示出来）：
     *  - 容忍 BOM、CRLF、空块、缺序号；
     *  - 时间行支持 `,` 和 `.` 两种毫秒分隔；
     *  - 文本里 `<i>`、`{\an8}` 这类标记一律剥掉（它们是样式，不是内容）。
     */
    fun parseSrt(raw: String): List<SubtitleCue> {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) return emptyList()

        val cues = mutableListOf<SubtitleCue>()
        // SRT 用空行分块；有的工具最后一块没有结尾空行，所以按 "\n\n" 切完再清空块
        for (block in text.split(Regex("\n\\s*\n"))) {
            val lines = block.trim().lines()
            if (lines.isEmpty()) continue

            val timeIndex = lines.indexOfFirst { it.contains("-->") }
            if (timeIndex < 0) continue

            val (startMs, endMs) = parseTimeRange(lines[timeIndex]) ?: continue
            val body = lines.drop(timeIndex + 1)
                .joinToString("\n")
                .let(::stripMarkup)
                .trim()
            if (body.isEmpty()) continue

            cues.add(SubtitleCue(startMs.coerceAtLeast(0L), endMs, body))
        }
        return normalize(cues)
    }

    /** 只解析时间行 `00:00:01,000 --> 00:00:04,500`（其它部分忽略，含 position 等参数）。 */
    fun parseTimeRange(line: String): Pair<Long, Long>? {
        val parts = line.split("-->")
        if (parts.size != 2) return null
        val start = parseClock(parts[0].trim().substringBefore(' ')) ?: return null
        // 结束时间后面常跟 `X1:...` 之类的位置参数，取第一个 token 即可
        val end = parseClock(parts[1].trim().substringBefore(' ')) ?: return null
        if (end < start) return null
        return start to end
    }

    /** `HH:MM:SS,mmm` / `HH:MM:SS.mmm` / `MM:SS,mmm` 都接受。 */
    fun parseClock(raw: String): Long? {
        val cleaned = raw.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        val clockPart = cleaned.substringBefore('.')
        val millisPart = cleaned.substringAfter('.', "")
            .padEnd(3, '0')
            .take(3)
            .toLongOrNull() ?: 0L
        val fields = clockPart.split(':').map { it.trim().toLongOrNull() ?: return null }
        val (h, m, s) = when (fields.size) {
            3 -> Triple(fields[0], fields[1], fields[2])
            2 -> Triple(0L, fields[0], fields[1])
            1 -> Triple(0L, 0L, fields[0])
            else -> return null
        }
        if (m > 59 || s > 59) return null
        return ((h * 3600 + m * 60 + s) * 1_000) + millisPart
    }

    /**
     * 排序、去掉重叠、补最短时长。
     *
     * 重叠按「前一句提前收尾」处理，因为两句同时显示在手机上是灾难。
     * 最短时长只在下一条字幕允许的间隙内补，不会把下一句挤走。
     */
    fun normalize(cues: List<SubtitleCue>): List<SubtitleCue> {
        val sorted = cues.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        val out = ArrayList<SubtitleCue>(sorted.size)
        sorted.forEachIndexed { index, cue ->
            val start = cue.startMs.coerceAtLeast(0L)
            val nextStart = sorted.getOrNull(index + 1)?.startMs
            // 先补足最短显示时长，再被下一句"截断"（相邻两句永远不重叠）
            val target = maxOf(cue.safeEndMs, start + MIN_DURATION_MS)
            val end = if (nextStart == null) target else minOf(target, nextStart - 1)
            out.add(cue.copy(startMs = start, endMs = maxOf(end, start + 1)))
        }
        return out
    }

    /**
     * 取当前时间点要显示的字幕（二分查找，O(log n)，每帧调用也没问题）。
     *
     * 命中窗口 = [start, end + HOLD_MS]，且不越过下一句的开始 —— HOLD 只是"多留一会儿"，
     * 不是"赖着不走"。预览/拖动时命中的是拖动位置对应的那句，不做额外补偿。
     */
    fun at(cues: List<SubtitleCue>, positionMs: Long): SubtitleCue? {
        if (cues.isEmpty()) return null
        var low = 0
        var high = cues.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (found < 0) return null
        val cue = cues[found]
        val nextStart = cues.getOrNull(found + 1)?.startMs
        val visibleEnd = minOf(
            cue.safeEndMs + HOLD_MS,
            nextStart ?: Long.MAX_VALUE
        )
        return cue.takeIf { positionMs <= visibleEnd }
    }

    /**
     * 原文字幕 + 译文字幕合成一份双语表。
     *
     * 两边的时间轴往往不完全一致（机翻常常把一句拆成两句），所以按"重叠时长最大"配对，
     * 而不是按下标硬对 —— 下标错一位就会整片错位。
     */
    fun mergeLanguages(
        original: List<SubtitleCue>,
        translated: List<SubtitleCue>
    ): List<SubtitleCue> {
        if (original.isEmpty()) return translated
        if (translated.isEmpty()) return original
        return original.map { src ->
            val best = translated
                .map { it to overlap(src, it) }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }
                ?.first
            // 只在译句更长时向后延长，绝不让字幕因为翻译而提前出现
            src.copy(
                endMs = maxOf(src.safeEndMs, best?.safeEndMs ?: src.safeEndMs),
                translated = best?.text
            )
        }
    }

    /**
     * 把相邻的碎片字幕合并成「一句」再翻译。
     *
     * 逐段识别的基本单位是 VAD 静音切出来的片段，经常把一句话切成两三段；
     * 直接逐条翻译会得到「我想知道」「我是否问过」这种半句，中文很生硬。
     * 合并后再交给翻译器，句意完整，译文自然得多。
     *
     * 合并条件（同时满足）：
     *  - 与上一句的间隔 ≤ [maxGapMs]；
     *  - 合并后长度 ≤ [maxChars]；
     *  - 上一句没有以句末标点结尾；
     *  - 合并后总时长 ≤ [maxDurationMs]。
     */
    fun mergeSentences(
        cues: List<SubtitleCue>,
        maxGapMs: Long = 420,
        maxChars: Int = 42,
        maxDurationMs: Long = 8_000
    ): List<SubtitleCue> {
        if (cues.size <= 1) return cues
        val out = ArrayList<SubtitleCue>(cues.size)
        cues.forEach { cue ->
            val last = out.lastOrNull()
            val joined = if (last != null) joinText(last.text, cue.text) else ""
            val mergeable = last != null &&
                cue.startMs - last.endMs <= maxGapMs &&
                joined.length <= maxChars &&
                cue.endMs - last.startMs <= maxDurationMs &&
                !endsSentence(last.text)
            if (mergeable) {
                out[out.size - 1] = last.copy(
                    endMs = cue.endMs,
                    text = joined,
                    translated = null
                )
            } else {
                out.add(cue)
            }
        }
        return out
    }

    /** 中文/日文直接拼，拉丁字母之间补空格。 */
    private fun joinText(a: String, b: String): String {
        val left = a.trimEnd()
        val right = b.trimStart()
        val needSpace = left.isNotEmpty() && right.isNotEmpty() &&
            left.last().isLetterOrDigit() && right.first().isLetterOrDigit() &&
            left.last().code < 0x2E80 && right.first().code < 0x2E80
        return if (needSpace) "$left $right" else left + right
    }

    private fun endsSentence(text: String): Boolean {
        val ch = text.trimEnd().lastOrNull() ?: return false
        return ch in "。！？.!?…" || ch == '」' || ch == '』'
    }

    private fun overlap(a: SubtitleCue, b: SubtitleCue): Long =
        minOf(a.safeEndMs, b.safeEndMs) - maxOf(a.startMs, b.startMs)

    /** 剥掉 SRT 里常见的伪样式标记。 */
    private fun stripMarkup(text: String): String {
        var out = text
        out = HTML_TAG.replace(out, "")
        out = BRACE_STYLE.replace(out, "")
        return out.replace('\u00A0', ' ')
    }

    private val HTML_TAG = Regex("<[^>]{1,10}>")
    private val BRACE_STYLE = Regex("\\{[^}]{1,12}\\}")
}
