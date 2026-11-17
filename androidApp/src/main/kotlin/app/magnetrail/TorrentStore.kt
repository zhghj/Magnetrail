package app.magnetrail

import android.content.Context
import app.magnetrail.shared.MagnetLink
import app.magnetrail.shared.MagnetParser

/** Remembers magnet URIs so the session can be rebuilt after the process dies. */
class TorrentStore(context: Context) {

    private val prefs = context.getSharedPreferences("magnetrail", Context.MODE_PRIVATE)

    fun magnets(): List<MagnetLink> =
        prefs.getString(KEY, "").orEmpty()
            .let { SecurePrefs.decrypt(it) }
            .split('\n')
            .mapNotNull { MagnetParser.parse(it) }

    fun add(link: MagnetLink) {
        val current = magnets()
        if (current.any { it.infoHash == link.infoHash }) return
        save(current + link)
    }

    fun remove(infoHash: String) {
        save(magnets().filterNot { it.infoHash == infoHash })
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun save(list: List<MagnetLink>) {
        val plain = list.joinToString("\n") { it.uri }
        prefs.edit().putString(KEY, SecurePrefs.encrypt(plain)).apply()
    }

    private companion object {
        const val KEY = "magnets"
    }
}
