package app.magnetrail.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitlePaginationTest {

    private val long = SubtitleCue(
        1_000,
        7_000,
        "如果你现在想，如果你打开那个盒子，我应该仔细考虑，因为这件事没有那么简单。"
    )

    @Test
    fun `short lines are not paginated`() {
        val cue = SubtitleCue(0, 2_000, "没关系。")
        assertEquals("没关系。", SubtitlePagination.pageAt(cue, SubtitleMode.ORIGINAL, 500)?.primary)
        assertEquals(1, SubtitlePagination.split("没关系。", 26).size)
    }

    @Test
    fun `long line is split at punctuation`() {
        val pages = SubtitlePagination.split(long.text, maxChars = 12)
        assertTrue(pages.size >= 3, "pages=$pages")
        pages.forEach { assertTrue(it.length <= 12, "page too long: $it") }
        // 每一页都以标点或原文边界结束，不出现半截词
        assertEquals(long.text.replace(" ", ""), pages.joinToString("").replace(" ", ""))
    }

    @Test
    fun `pages advance with playback position`() {
        val early = SubtitlePagination.pageAt(long, SubtitleMode.ORIGINAL, 1_200, 12)
        val middle = SubtitlePagination.pageAt(long, SubtitleMode.ORIGINAL, 4_000, 12)
        val late = SubtitlePagination.pageAt(long, SubtitleMode.ORIGINAL, 6_800, 12)
        assertNotNull(early)
        assertNotNull(middle)
        assertNotNull(late)
        assertTrue(early!!.primary != middle!!.primary)
        assertTrue(middle != late)
    }

    @Test
    fun `bilingual keeps a proportional slice of the original`() {
        val cue = SubtitleCue(
            0, 8_000,
            "これはとても長い日本語のセリフで、二画面に分けて表示する必要があります。",
            "这是一段很长的中文台词，需要分成两屏来显示。"
        )
        val first = SubtitlePagination.pageAt(cue, SubtitleMode.BILINGUAL, 500, 16)
        val last = SubtitlePagination.pageAt(cue, SubtitleMode.BILINGUAL, 7_800, 16)
        assertNotNull(first)
        assertNotNull(last)
        assertTrue(first!!.primary != last!!.primary)
        assertNotNull(first.secondary)
        assertNotNull(last.secondary)
        assertTrue(first.secondary!!.length < cue.translated!!.length)
    }

    @Test
    fun `off mode still returns null`() {
        assertNull(SubtitlePagination.pageAt(long, SubtitleMode.OFF, 2_000, 20))
    }

    @Test
    fun `hard cut when no punctuation exists`() {
        val text = "あ".repeat(50)
        val pages = SubtitlePagination.split(text, 18)
        assertTrue(pages.all { it.length <= 18 })
        assertEquals(50, pages.joinToString("").length)
    }
}
