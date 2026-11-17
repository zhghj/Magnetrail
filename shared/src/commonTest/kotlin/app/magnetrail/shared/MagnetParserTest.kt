package app.magnetrail.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MagnetParserTest {

    private val hex = "7306cee4e4145438a5523fbdea309b241ad1a3df"
    private val base32 = "OMDM5ZHECRKDRJKSH666UME3EQNNDI67"

    @Test
    fun parsesHexMagnet() {
        val link = MagnetParser.parse("magnet:?xt=urn:btih:$hex")
        assertEquals(hex, link?.infoHash)
    }

    @Test
    fun parsesHexMagnetWithDisplayNameAndTrackers() {
        val link = MagnetParser.parse(
            "magnet:?xt=urn:btih:${hex.uppercase()}&dn=My%20File&tr=udp%3A%2F%2Ftracker.example%3A80"
        )
        assertEquals(hex, link?.infoHash)
        assertEquals("My File", link?.displayName)
    }

    @Test
    fun parsesBase32Magnet() {
        // base32 above was produced independently from the hex value
        val link = MagnetParser.parse("magnet:?xt=urn:btih:$base32")
        assertEquals(hex, link?.infoHash)
    }

    @Test
    fun rejectsJunk() {
        assertNull(MagnetParser.parse(""))
        assertNull(MagnetParser.parse(null))
        assertNull(MagnetParser.parse("https://example.com/file.torrent"))
        assertNull(MagnetParser.parse("magnet:?dn=only-a-name"))
        assertNull(MagnetParser.parse("magnet:?xt=urn:btih:zzzz"))
        assertNull(MagnetParser.parse("magnet:?xt=urn:btmh:1220abc"))
    }

    @Test
    fun ignoresTrailingWhitespace() {
        val link = MagnetParser.parse("  magnet:?xt=urn:btih:$hex  \n")
        assertEquals(hex, link?.infoHash)
    }

    @Test
    fun percentDecodeHandlesPlusAndBrokenEscapes() {
        assertEquals("a+b c", MagnetParser.decodePercent("a%2Bb%20c"))
        assertEquals("50%", MagnetParser.decodePercent("50%"))
        assertEquals("%zz", MagnetParser.decodePercent("%zz"))
    }
}
