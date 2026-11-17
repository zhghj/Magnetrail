package app.magnetrail.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleCuesTest {

    private val sample = """
        1
        00:00:01,000 --> 00:00:04,000
        第一句

        2
        00:00:05,500 --> 00:00:09,200
        第二句
        换行也要保留
    """.trimIndent()

    @Test
    fun `parse basic srt`() {
        val cues = SubtitleCues.parseSrt(sample)
        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(4_000L, cues[0].endMs)
        assertEquals("第一句", cues[0].text)
        assertEquals("第二句\n换行也要保留", cues[1].text)
    }

    @Test
    fun `parse tolerates bom crlf and clock variants`() {
        val raw = "\uFEFF1\r\n00:00:02.500 --> 00:00:06.750\r\nhello\r\n\r\n"
        val cues = SubtitleCues.parseSrt(raw)
        assertEquals(1, cues.size)
        assertEquals(2_500L, cues[0].startMs)
        assertEquals(6_750L, cues[0].endMs)
    }

    @Test
    fun `parse strips styling markup`() {
        val raw = "1\n00:00:01,000 --> 00:00:02,000\n<i>斜体</i> {\\an8}正文\n"
        val cues = SubtitleCues.parseSrt(raw)
        assertEquals("斜体 正文", cues.first().text)
    }

    @Test
    fun `malformed blocks are skipped instead of crashing`() {
        val raw = """
            1
            00:00:01,000 --> 00:00:02,000

            2
            这不是时间行
            垃圾

            3
            00:00:60,000 --> 00:00:70,000
            秒数越界

            4
            00:00:10,000 --> 00:00:12,000
            好的
        """.trimIndent()
        val cues = SubtitleCues.parseSrt(raw)
        assertEquals(1, cues.size)
        assertEquals("好的", cues[0].text)
    }

    @Test
    fun `parseClock handles all accepted shapes`() {
        assertEquals(1_500L, SubtitleCues.parseClock("00:00:01,500"))
        assertEquals(3_050L, SubtitleCues.parseClock("00:03.050"))
        assertEquals(3_050L, SubtitleCues.parseClock("3.050"))
        assertNull(SubtitleCues.parseClock("nope"))
    }

    @Test
    fun `normalize enforces min duration without pushing the next cue`() {
        val cues = listOf(
            SubtitleCue(1_000, 1_200, "短"),
            SubtitleCue(1_600, 2_600, "下一句"),
            SubtitleCue(9_000, 8_000, "结束早于开始")
        )
        val fixed = SubtitleCues.normalize(cues)
        assertEquals(1_599L, fixed[0].endMs)   // 补到最短时长，被下一句截断
        assertEquals(2_600L, fixed[1].endMs)
        assertEquals(10_000L, fixed[2].endMs)  // safeEnd 兜底 +1s
    }

    @Test
    fun `at picks the cue covering the position`() {
        val cues = SubtitleCues.parseSrt(sample)
        assertEquals("第一句", SubtitleCues.at(cues, 2_000)?.text)
        assertEquals("第二句\n换行也要保留", SubtitleCues.at(cues, 7_000)?.text)
        assertNull(SubtitleCues.at(cues, 0))
    }

    @Test
    fun `at honours hold time but never runs past the next cue`() {
        val cues = SubtitleCues.parseSrt(sample)
        // 第 1 句 4.0s 结束，HOLD 900ms → 4.9s 仍在显示
        assertNotNull(SubtitleCues.at(cues, 4_800))
        // 但下一句 5.5s 开始后，第一句必须让位
        assertEquals("第二句\n换行也要保留", SubtitleCues.at(cues, 5_600)?.text)
        // 缝隙里（4.9s~5.5s）不显示任何东西
        assertNull(SubtitleCues.at(cues, 5_300))
    }

    @Test
    fun `at works after seeking near the end`() {
        val cues = SubtitleCues.parseSrt(sample)
        assertEquals("第二句\n换行也要保留", SubtitleCues.at(cues, 9_000)?.text)
        // 第二句 9.2s 结束 + HOLD 0.9s → 10.1s 之后必须消失
        assertNull(SubtitleCues.at(cues, 10_200))
    }

    @Test
    fun `mergeLanguages pairs by overlap not by index`() {
        val original = listOf(
            SubtitleCue(1_000, 3_000, "one"),
            SubtitleCue(3_000, 6_000, "two")
        )
        // 译句被拆成三句且整体晚 0.2s
        val translated = listOf(
            SubtitleCue(1_200, 1_900, "一"),
            SubtitleCue(1_900, 2_950, "一续"),
            SubtitleCue(3_200, 6_100, "二")
        )
        val merged = SubtitleCues.mergeLanguages(original, translated)
        assertEquals("一续", merged[0].translated)
        assertEquals("二", merged[1].translated)
        // 原来双语时整句只显示中文 + 原文小字
        val line = merged[0].lineFor(SubtitleMode.BILINGUAL)
        assertEquals("一续", line?.primary)
        assertEquals("one", line?.secondary)
    }

    @Test
    fun `mode mapping falls back to original text when no translation`() {
        val cue = SubtitleCue(0, 1_000, "原文 only", null)
        assertEquals("原文 only", cue.lineFor(SubtitleMode.CHINESE)?.primary)
        assertEquals(null, cue.lineFor(SubtitleMode.OFF))
        assertTrue(cue.lineFor(SubtitleMode.BILINGUAL)?.secondary == null)
    }

    @Test
    fun `mergeSentences joins fragments of one sentence`() {
        val cues = listOf(
            SubtitleCue(1_000, 2_000, "如果你现在想，"),
            SubtitleCue(2_100, 3_000, "如果你打开那个盒子，"),
            SubtitleCue(3_100, 4_200, "我应该仔细考虑。")
        )
        val merged = SubtitleCues.mergeSentences(cues)
        // 前两句没有句末标点 → 合并；第三句已带句号，仍可并入（未超过上限）
        assertEquals(1, merged.size)
        assertEquals("如果你现在想，如果你打开那个盒子，我应该仔细考虑。", merged[0].text)
        assertEquals(1_000, merged[0].startMs)
        assertEquals(4_200, merged[0].endMs)
    }

    @Test
    fun `mergeSentences stops at sentence ends and long gaps`() {
        val ended = listOf(
            SubtitleCue(0, 1_000, "结束了。"),
            SubtitleCue(1_100, 2_000, "新的一句")
        )
        assertEquals(2, SubtitleCues.mergeSentences(ended).size)

        val gap = listOf(
            SubtitleCue(0, 1_000, "前半"),
            SubtitleCue(2_000, 3_000, "后半")
        )
        assertEquals(2, SubtitleCues.mergeSentences(gap).size)
    }

    @Test
    fun `mergeSentences limits length and adds latin spacing`() {
        val latin = listOf(
            SubtitleCue(0, 1_000, "hello"),
            SubtitleCue(1_050, 2_000, "world")
        )
        assertEquals("hello world", SubtitleCues.mergeSentences(latin).single().text)

        val long = (0 until 6).map { i ->
            SubtitleCue(i * 1_000L, i * 1_000L + 900, "一二三四五六七八九十")
        }
        assertTrue(SubtitleCues.mergeSentences(long).size > 1)
        assertTrue(SubtitleCues.mergeSentences(long).all { it.text.length <= 42 })
    }
}
