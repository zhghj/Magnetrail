package app.magnetrail.shared

/**
 * 把 cue 表写成 SRT。
 *
 * 只做一件事：保证第三方播放器/字幕库能吃（时间格式、空行、编号）。
 * 原文和译文分开写两份文件，而不是塞进同一条 —— 双语显示由我们自己按时间合并，
 * 这样换播放器时原文轨仍然可用。
 */
object SubtitleSrt {

    fun format(cues: List<SubtitleCue>, translated: Boolean = false): String {
        val sb = StringBuilder()
        var index = 1
        cues.forEach { cue ->
            val body = if (translated) cue.translated?.takeIf { it.isNotBlank() } ?: cue.text
            else cue.text
            if (body.isBlank()) return@forEach
            sb.append(index++).append('\n')
            sb.append(clock(cue.startMs)).append(" --> ").append(clock(cue.safeEndMs)).append('\n')
            sb.append(body.trim()).append("\n\n")
        }
        return sb.toString()
    }

    /** `01:02:03,456` —— SRT 必须用逗号毫秒。 */
    fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val h = total / 3_600_000
        val m = (total % 3_600_000) / 60_000
        val s = (total % 60_000) / 1_000
        val milli = total % 1_000
        return buildString {
            append(h.toString().padStart(2, '0')).append(':')
            append(m.toString().padStart(2, '0')).append(':')
            append(s.toString().padStart(2, '0')).append(',')
            append(milli.toString().padStart(3, '0'))
        }
    }
}
