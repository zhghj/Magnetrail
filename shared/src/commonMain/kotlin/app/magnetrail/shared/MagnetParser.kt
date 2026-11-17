package app.magnetrail.shared

/**
 * Minimal magnet URI parser.
 *
 * Only `xt=urn:btih:` (BitTorrent v1 info hash) is accepted, in either hex (40 chars)
 * or base32 (32 chars) form. `dn` (display name) is used to label the entry before
 * metadata arrives; `tr` tracker entries and `xl` are ignored here because libtorrent
 * already reads the full URI verbatim.
 */
data class MagnetLink(
    val uri: String,
    val infoHash: String,     // lowercase 40-char hex
    val displayName: String?
) {
    val shortHash: String get() = infoHash.take(8) + "..."
}

object MagnetParser {

    private val HEX = Regex("^[0-9a-fA-F]{40}$")
    private val BASE32 = Regex("^[A-Za-z2-7]{32}$")

    fun parse(raw: String?): MagnetLink? {
        val text = raw?.trim().orEmpty()
        if (!text.startsWith("magnet:?", ignoreCase = true)) return null
        val query = text.substringAfter('?', "")
        if (query.isEmpty()) return null

        var hash: String? = null
        var name: String? = null

        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=').lowercase()
            val value = pair.substringAfter('=', "")
            when (key) {
                "xt" -> {
                    val v = decodePercent(value)
                    if (v.startsWith("urn:btih:", ignoreCase = true)) {
                        hash = normalizeHash(v.substring(9))
                    }
                }
                "dn" -> name = decodePercent(value).ifBlank { null }
            }
        }

        if (hash == null) return null
        return MagnetLink(uri = text, infoHash = hash, displayName = name)
    }

    private fun normalizeHash(raw: String): String? = when {
        HEX.matches(raw) -> raw.lowercase()
        BASE32.matches(raw) -> base32ToHex(raw)
        else -> null
    }

    /** RFC 4648 base32 (upper case, padding ignored) → lowercase hex. */
    private fun base32ToHex(input: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0L
        var bitsLeft = 0
        val hex = StringBuilder(40)
        for (c in input.uppercase()) {
            val v = alphabet.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v.toLong()
            bitsLeft += 5
            while (bitsLeft >= 4) {
                bitsLeft -= 4
                hex.append(((buffer shr bitsLeft) and 0x0F).toString(16))
            }
        }
        return if (hex.length == 40) hex.toString() else null
    }

    /** Percent decoding without java.net.URLDecoder (shared code must stay platform agnostic). */
    fun decodePercent(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val code = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
