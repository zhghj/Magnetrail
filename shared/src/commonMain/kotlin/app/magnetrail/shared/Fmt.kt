package app.magnetrail.shared

import kotlin.math.abs
import kotlin.math.roundToInt

object Fmt {

    private fun oneDecimal(v: Double): String {
        val scaled = (v * 10.0).roundToInt()
        return "${scaled / 10}.${abs(scaled % 10)}"
    }

    private fun twoDigits(v: Long): String = if (v < 10) "0$v" else v.toString()

    fun bytes(value: Long): String {
        if (value <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = value.toDouble()
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) {
            v /= 1024.0
            i++
        }
        return if (i == 0) "$value B" else "${oneDecimal(v)} ${units[i]}"
    }

    fun speed(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/s"

    /** 0..1 → "72.4%" */
    fun percent(progress: Double): String = oneDecimal(progress.coerceIn(0.0, 1.0) * 100.0) + "%"

    /** Seconds → "2h 05m" / "3m 12s" / "—" */
    fun eta(seconds: Long): String {
        if (seconds <= 0 || seconds >= 30L * 24 * 3600) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${twoDigits(m)}m"
            m > 0 -> "${m}m ${twoDigits(s)}s"
            else -> "${s}s"
        }
    }

    fun durationMs(ms: Long): String {
        val total = abs(ms) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "$h:${twoDigits(m)}:${twoDigits(s)}" else "$m:${twoDigits(s)}"
    }
}
