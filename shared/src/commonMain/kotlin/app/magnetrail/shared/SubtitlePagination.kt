package app.magnetrail.shared

/**
 * 超长句的「分页显示」：一句话按标点/字数切成 2–3 屏，按字数比例分配时间。
 *
 * 为什么不改 SRT：文件里保留完整句子（导出/别的播放器都正常），
 * 只在**显示时**分页，这样字号改大改小都能自适应。
 */
object SubtitlePagination {

    /** 句末/句中停顿：优先在这些位置切。 */
    private val BREAK_CHARS = charArrayOf(
        '，', '、', '；', '：', '。', '！', '？', '…',
        ',', ';', ':', '.', '!', '?', ' ', '　'
    )

    /**
     * 当前时间点应该显示哪一页。
     *
     * @param maxChars 单页最多显示的字符数（跟随字号：字越大，每页越短）
     */
    fun pageAt(
        cue: SubtitleCue,
        mode: SubtitleMode,
        positionMs: Long,
        maxChars: Int = 26
    ): SubtitleLine? {
        val line = cue.lineFor(mode) ?: return null
        val primary = line.primary
        if (primary.length <= maxChars) return line

        val pages = split(primary, maxChars)
        if (pages.size <= 1) return line

        val total = primary.length.toFloat().coerceAtLeast(1f)
        val span = (cue.safeEndMs - cue.startMs).coerceAtLeast(1L)
        val progress = ((positionMs - cue.startMs).toFloat() / span).coerceIn(0f, 0.999f)

        // 按字数比例找到当前页
        var acc = 0
        var index = 0
        var pageStart = 0
        pages.forEachIndexed { i, page ->
            if (progress * total >= acc && progress * total < acc + page.length) {
                index = i
                pageStart = acc
            }
            acc += page.length
        }
        val page = pages[index]

        // 原文小字按同样的比例切（译文和原文语序不同，只能近似对齐）
        val secondary = line.secondary?.let { src ->
            if (src.length <= maxChars) src
            else {
                val from = (src.length.toFloat() * pageStart / total).toInt()
                val to = (src.length.toFloat() * (pageStart + page.length) / total).toInt()
                src.substring(from.coerceIn(0, src.length), to.coerceIn(from, src.length))
            }
        }
        return SubtitleLine(page, secondary)
    }

    /** 把一段文本切成若干页：优先在标点处断开，找不到就按字数硬切。 */
    fun split(text: String, maxChars: Int): List<String> {
        val limit = maxChars.coerceAtLeast(6)
        if (text.length <= limit) return listOf(text)
        val out = ArrayList<String>()
        var index = 0
        while (index < text.length) {
            var end = (index + limit).coerceAtMost(text.length)
            if (end < text.length) {
                // 在 [index+limit/2, index+limit] 里从后往前找标点
                val floor = index + limit / 2
                var cut = -1
                for (i in end - 1 downTo floor) {
                    if (text[i] in BREAK_CHARS) {
                        cut = i + 1
                        break
                    }
                }
                if (cut > index) end = cut
            }
            out.add(text.substring(index, end).trim())
            index = end
        }
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
