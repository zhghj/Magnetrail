package app.magnetrail.shared

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleSrtTest {

    @Test
    fun `clock pads every field`() {
        assertEquals("00:00:01,500", SubtitleSrt.clock(1_500))
        assertEquals("01:02:03,004", SubtitleSrt.clock(3_723_004))
        assertEquals("00:00:00,000", SubtitleSrt.clock(-5))
    }

    @Test
    fun `format writes numbered blocks separated by blank lines`() {
        val srt = SubtitleSrt.format(
            listOf(
                SubtitleCue(1_000, 2_000, "第一句"),
                SubtitleCue(3_000, 4_500, "第二句", "translated")
            )
        )
        assertTrue(srt.startsWith("1\n00:00:01,000 --> 00:00:02,000\n第一句\n\n"))
        assertTrue(srt.endsWith("2\n00:00:03,000 --> 00:00:04,500\n第二句\n\n"))
    }

    @Test
    fun `translated variant falls back to original text`() {
        val withoutTranslation = SubtitleSrt.format(
            listOf(SubtitleCue(0, 1_000, "原文")), translated = true
        )
        assertTrue(withoutTranslation.contains("原文"))
        val withTranslation = SubtitleSrt.format(
            listOf(SubtitleCue(0, 1_000, "原文", "译文")), translated = true
        )
        assertTrue(withTranslation.contains("译文"))
    }

    @Test
    fun `round trip through parse keeps timing`() {
        val cues = listOf(
            SubtitleCue(1_000, 2_500, "a"),
            SubtitleCue(3_000, 4_000, "b")
        )
        val parsed = SubtitleCues.parseSrt(SubtitleSrt.format(cues))
        assertEquals(2, parsed.size)
        assertEquals(1_000L, parsed[0].startMs)
        assertEquals(2_500L, parsed[0].endMs)
        assertEquals(4_000L, parsed[1].endMs)
    }
}

class SpeechSegmentsTest {

    private val rate = 16_000

    /** 在 [startMs, endMs) 里放一段 440Hz 正弦，其余静音。 */
    private fun tone(totalMs: Int, vararg voiced: Pair<Int, Int>): FloatArray {
        val samples = FloatArray(totalMs * rate / 1000)
        voiced.forEach { (fromMs, toMs) ->
            val from = fromMs * rate / 1000
            val to = toMs * rate / 1000
            for (i in from until to.coerceAtMost(samples.size)) {
                samples[i] = (0.35 * sin(2 * PI * 440.0 * (i - from) / rate)).toFloat()
            }
        }
        return samples
    }

    @Test
    fun `detects two utterances and ignores silence`() {
        val samples = tone(6_000, 500 to 1_500, 3_000 to 4_000)
        val spans = SpeechSegments.detect(samples, rate)
        assertEquals(2, spans.size)
        // 允许 ±200ms 的分段误差（帧长 20ms + 120ms padding）
        assertTrue(kotlin.math.abs(spans[0].startMs - 500) <= 250)
        assertTrue(kotlin.math.abs(spans[0].endMs - 1_500) <= 250)
        assertTrue(kotlin.math.abs(spans[1].startMs - 3_000) <= 250)
    }

    @Test
    fun `bridges short pauses inside one sentence`() {
        val samples = tone(5_000, 500 to 1_500, 1_700 to 2_600)
        val spans = SpeechSegments.detect(samples, rate)
        assertEquals(1, spans.size)
        assertTrue(spans[0].durationMs >= 2_000)
    }

    @Test
    fun `drops blips shorter than the minimum`() {
        val samples = tone(4_000, 1_000 to 1_100)
        assertEquals(0, SpeechSegments.detect(samples, rate).size)
    }

    @Test
    fun `handles clips with almost no silence`() {
        // 整段 68 秒都有声音：低分位≈中位数，阈值不能因此被抬到触发不了
        val samples = tone(70_000, 500 to 69_000)
        val spans = SpeechSegments.detect(samples, rate)
        val dump = spans.joinToString { "${it.startMs}-${it.endMs}" }
        assertTrue(spans.size >= 3, "spans=$dump")
        assertTrue(spans.all { it.durationMs <= SpeechSegments.MAX_SPAN_MS }, "spans=$dump")
    }

    @Test
    fun `slice returns the samples of a span`() {
        val samples = tone(3_000, 1_000 to 2_000)
        val span = SpeechSegments.detect(samples, rate).first()
        val slice = SpeechSegments.slice(samples, span, rate)
        assertEquals((span.endMs - span.startMs) * rate / 1000, slice.size.toLong())
    }
}
