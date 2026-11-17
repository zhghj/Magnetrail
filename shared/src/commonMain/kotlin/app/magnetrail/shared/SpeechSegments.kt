package app.magnetrail.shared

/** 一段「有人在说话」的区间（毫秒，媒体时间轴）。 */
data class SpeechSpan(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * 能量法语音分段（不依赖 VAD 模型，少一个 2MB 下载、少一个运行时）。
 *
 * 对字幕来说够用：SRT 本来就是按停顿切句，±200ms 的边界人眼看不出。
 * 灵敏度靠**自适应噪声底**：取所有帧能量的低分位当底噪，阈值取底噪的 3 倍，
 * 这样安静片和吵闹片都能用同一套参数。
 */
object SpeechSegments {

    const val FRAME_MS = 20
    const val MIN_SPEECH_MS = 350L
    const val MAX_GAP_MS = 300L
    const val PAD_MS = 120L

    /** SenseVoice 单段上限约 30s，留点余量。 */
    const val MAX_SPAN_MS = 28_000L

    /**
     * @param absoluteFloor 绝对下限：低于它一律当静音（防止纯噪声片子把整段判成语音）
     */
    fun detect(
        samples: FloatArray,
        sampleRate: Int = 16_000,
        absoluteFloor: Float = 0.006f
    ): List<SpeechSpan> {
        if (samples.isEmpty() || sampleRate <= 0) return emptyList()
        val frame = (sampleRate * FRAME_MS / 1000).coerceAtLeast(1)
        val frameCount = samples.size / frame
        if (frameCount <= 0) return emptyList()

        val energy = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            var sum = 0.0
            val offset = i * frame
            for (j in 0 until frame) {
                val v = samples[offset + j]
                sum += v * v
            }
            energy[i] = kotlin.math.sqrt(sum / frame).toFloat()
        }

        val sorted = energy.copyOf()
        sorted.sort()
        val p05 = sorted[(sorted.size * 0.05f).toInt().coerceIn(0, sorted.size - 1)]
        val median = sorted[sorted.size / 2]
        // 常规片：大部分帧是静音，用低分位当底噪。
        // 极端片（几乎没有静音，比如整段 BGM/持续人声）：低分位≈中位数，
        // 这时改用中位数的一个比例，否则阈值会被抬到永远触发不了。
        val threshold = if (p05 >= median * 0.5f) {
            maxOf(absoluteFloor, median * 0.35f)
        } else {
            maxOf(absoluteFloor, p05 * 3f)
        }

        // 逐帧标语音，再把短静音桥接掉
        val spans = ArrayList<SpeechSpan>()
        var runStart = -1
        for (i in 0..frameCount) {
            val voiced = i < frameCount && energy[i] >= threshold
            if (voiced && runStart < 0) runStart = i
            if (!voiced && runStart >= 0) {
                spans.add(SpeechSpan(runStart.toLong() * FRAME_MS, i.toLong() * FRAME_MS))
                runStart = -1
            }
        }

        // 合并间隙（同一句话里的换气）
        val merged = ArrayList<SpeechSpan>()
        spans.forEach { span ->
            val last = merged.lastOrNull()
            if (last != null && span.startMs - last.endMs <= MAX_GAP_MS) {
                merged[merged.size - 1] = last.copy(endMs = span.endMs)
            } else {
                merged.add(span)
            }
        }

        val totalMs = samples.size * 1000L / sampleRate
        return merged
            .filter { it.durationMs >= MIN_SPEECH_MS }
            .map { it.copy(startMs = (it.startMs - PAD_MS).coerceAtLeast(0L),
                endMs = (it.endMs + PAD_MS).coerceAtMost(totalMs)) }
            .flatMap { splitLong(it) }
    }

    private fun splitLong(span: SpeechSpan): List<SpeechSpan> {
        if (span.durationMs <= MAX_SPAN_MS) return listOf(span)
        val out = ArrayList<SpeechSpan>()
        var start = span.startMs
        while (start < span.endMs) {
            val end = (start + MAX_SPAN_MS).coerceAtMost(span.endMs)
            if (end - start >= MIN_SPEECH_MS) out.add(SpeechSpan(start, end))
            start = end
        }
        return out
    }

    /** 取出某段的浮点采样（识别用）。 */
    fun slice(samples: FloatArray, span: SpeechSpan, sampleRate: Int = 16_000): FloatArray {
        val from = (span.startMs * sampleRate / 1000).toInt().coerceIn(0, samples.size)
        val to = (span.endMs * sampleRate / 1000).toInt().coerceIn(from, samples.size)
        return samples.copyOfRange(from, to)
    }
}
