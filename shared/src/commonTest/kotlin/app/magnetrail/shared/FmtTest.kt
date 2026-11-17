package app.magnetrail.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class FmtTest {

    @Test
    fun bytesUsesBinaryUnits() {
        assertEquals("0 B", Fmt.bytes(0))
        assertEquals("512 B", Fmt.bytes(512))
        assertEquals("1.0 KB", Fmt.bytes(1024))
        assertEquals("1.5 MB", Fmt.bytes(1024 * 1024 + 512 * 1024))
        assertEquals("1.0 GB", Fmt.bytes(1024L * 1024 * 1024))
        assertEquals("1.0 TB", Fmt.bytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun percentIsOneDecimal() {
        assertEquals("0.0%", Fmt.percent(0.0))
        assertEquals("50.0%", Fmt.percent(0.5))
        assertEquals("100.0%", Fmt.percent(1.0))
        assertEquals("100.0%", Fmt.percent(1.5))
    }

    @Test
    fun etaFormatsHoursMinutesSeconds() {
        assertEquals("59s", Fmt.eta(59))
        assertEquals("1m 01s", Fmt.eta(61))
        assertEquals("2h 05m", Fmt.eta(2 * 3600 + 5 * 60))
        assertEquals("—", Fmt.eta(0))
        assertEquals("—", Fmt.eta(100L * 24 * 3600))
    }

    @Test
    fun durationFormatsClock() {
        assertEquals("0:05", Fmt.durationMs(5_000))
        assertEquals("1:30", Fmt.durationMs(90_000))
        assertEquals("1:00:00", Fmt.durationMs(3_600_000))
    }
}
