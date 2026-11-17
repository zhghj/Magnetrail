package app.magnetrail.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LiveSubtitleTimelineTest {

    @Test
    fun `offsets are anchored to the window start`() {
        val timeline = LiveSubtitleTimeline()
        timeline.beginWindow(mediaStartMs = 60_000)
        val cue = timeline.addCue(1_000, 3_000, "第一句")
        assertNotNull(cue)
        assertEquals(61_000, cue.startMs)
        assertEquals(63_000, cue.endMs)
    }

    @Test
    fun `results beyond the buffered window are dropped`() {
        val timeline = LiveSubtitleTimeline()
        timeline.beginWindow(10_000)
        // 缓冲只到 15s，这条从 16s 开始 → 丢弃
        assertNull(timeline.addCue(6_000, 8_000, "还没缓冲到", bufferedEndMs = 15_000))
        // 从 13s 开始、跨过 15s → 截断到 15s
        val clipped = timeline.addCue(3_000, 9_000, "跨窗口", bufferedEndMs = 15_000)
        assertEquals(13_000, clipped?.startMs)
        assertEquals(15_000, clipped?.endMs)
    }

    @Test
    fun `visible never shows a cue before it starts`() {
        val timeline = LiveSubtitleTimeline()
        timeline.beginWindow(0)
        timeline.addCue(5_000, 7_000, "五秒后")
        assertNull(timeline.visible(4_999))
        assertEquals("五秒后", timeline.visible(5_000)?.text)
        assertEquals("五秒后", timeline.visible(6_500)?.text)
    }

    @Test
    fun `late results are dropped after the grace period`() {
        val timeline = LiveSubtitleTimeline()
        timeline.beginWindow(0)
        timeline.addCue(5_000, 7_000, "迟到")
        // 7s 结束 + 1.2s 宽限 = 8.2s 之后不再显示，避免"话早说完字幕才蹦出来"
        assertEquals("迟到", timeline.visible(8_000)?.text)
        assertNull(timeline.visible(8_300))
    }

    @Test
    fun `seek starts a new window and old cues stop matching`() {
        val timeline = LiveSubtitleTimeline()
        timeline.beginWindow(0)
        timeline.addCue(1_000, 3_000, "开头")

        timeline.reset()
        timeline.beginWindow(600_000)
        timeline.addCue(500, 2_500, "跳转后")

        assertEquals(600_500, timeline.generated.single().startMs)
        assertNull(timeline.visible(2_000))       // 旧字幕已经清掉
        assertEquals("跳转后", timeline.visible(601_000)?.text)
    }

    @Test
    fun `blank text is ignored`() {
        val timeline = LiveSubtitleTimeline()
        timeline.beginWindow(0)
        assertNull(timeline.addCue(0, 1_000, "   "))
        assertEquals(0, timeline.generated.size)
    }

    @Test
    fun `paused playback keeps the same window`() {
        val timeline = LiveSubtitleTimeline()
        timeline.beginWindow(30_000)
        timeline.addCue(0, 2_000, "A")
        // 播放器暂停：没有新的 position，也没有新窗口 —— 重新读同一条即可
        assertEquals("A", timeline.visible(30_500)?.text)
        assertEquals("A", timeline.visible(31_500)?.text)
    }
}
