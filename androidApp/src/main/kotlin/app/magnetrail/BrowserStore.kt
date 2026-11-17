package app.magnetrail

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * 浏览器的轻量持久化：历史、收藏、无痕开关。
 *
 * 用一段 JSON 存进 SharedPreferences，条数很少（历史最多 200 条），够用且简单。
 */
class BrowserStore(context: Context) {

    data class Entry(
        val url: String,
        val title: String,
        val timeMs: Long
    ) {
        /** 列表里显示的主机名。 */
        val host: String
            get() = runCatching { URI(url).host }.getOrNull() ?: url.take(32)
    }

    private val prefs = context.applicationContext
        .getSharedPreferences("browser", Context.MODE_PRIVATE)

    /** 无痕：不记历史、不记上次网址。 */
    var incognito: Boolean
        get() = prefs.getBoolean(KEY_INCOGNITO, false)
        set(value) {
            prefs.edit().putBoolean(KEY_INCOGNITO, value).apply()
        }

    // ------------------------------------------------------------ 历史

    fun history(): List<Entry> = readList(KEY_HISTORY)

    fun addHistory(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        if (!url.startsWith("http")) return
        val list = history().toMutableList()
        list.removeAll { it.url == url }
        list.add(0, Entry(url, title.ifBlank { url }, System.currentTimeMillis()))
        writeList(KEY_HISTORY, list.take(MAX_HISTORY))
    }

    fun removeHistory(url: String) {
        writeList(KEY_HISTORY, history().filterNot { it.url == url })
    }

    fun clearHistory() {
        writeList(KEY_HISTORY, emptyList())
    }

    // ------------------------------------------------------------ 收藏

    fun bookmarks(): List<Entry> = readList(KEY_BOOKMARKS)

    fun isBookmarked(url: String): Boolean =
        url.isNotBlank() && bookmarks().any { it.url == url }

    /** 切换收藏，返回切换后的状态（true = 已收藏）。 */
    fun toggleBookmark(url: String, title: String): Boolean {
        if (url.isBlank()) return false
        val list = bookmarks().toMutableList()
        val existing = list.firstOrNull { it.url == url }
        if (existing != null) {
            list.remove(existing)
            writeList(KEY_BOOKMARKS, list)
            return false
        }
        list.add(0, Entry(url, title.ifBlank { url }, System.currentTimeMillis()))
        writeList(KEY_BOOKMARKS, list)
        return true
    }

    fun removeBookmark(url: String) {
        writeList(KEY_BOOKMARKS, bookmarks().filterNot { it.url == url })
    }

    // ------------------------------------------------------------ 视频播放历史

    /** 网页视频的观看记录：用来「继续播放」。 */
    data class VideoEntry(
        val url: String,
        val title: String,
        val positionMs: Long,
        val durationMs: Long,
        val timeMs: Long,
        val headers: List<String>
    ) {
        val progress: Float
            get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    fun videoHistory(): List<VideoEntry> {
        val raw = prefs.getString(KEY_VIDEO_HISTORY, null)?.let { SecurePrefs.decrypt(it) } ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = obj.optString("url")
                if (url.isBlank()) null else VideoEntry(
                    url = url,
                    title = obj.optString("title").ifBlank { url },
                    positionMs = obj.optLong("position", 0L),
                    durationMs = obj.optLong("duration", 0L),
                    timeMs = obj.optLong("time", 0L),
                    headers = runCatching {
                        val h = obj.optJSONArray("headers") ?: JSONArray()
                        (0 until h.length()).map { h.getString(it) }
                    }.getOrDefault(emptyList())
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 记录/更新一条视频观看进度（按 url 去重，最多 100 条）。 */
    fun recordVideo(url: String, title: String, positionMs: Long, durationMs: Long, headers: Array<String>?) {
        if (url.isBlank() || !url.startsWith("http")) return
        if (positionMs < 5_000) return // 刚点开就退出，不值得记
        val list = videoHistory().toMutableList()
        list.removeAll { it.url == url }
        list.add(
            0,
            VideoEntry(
                url = url,
                title = title.ifBlank { url },
                positionMs = positionMs,
                durationMs = durationMs,
                timeMs = System.currentTimeMillis(),
                headers = headers?.toList() ?: emptyList()
            )
        )
        writeVideoList(list.take(MAX_VIDEO_HISTORY))
    }

    fun removeVideo(url: String) {
        writeVideoList(videoHistory().filterNot { it.url == url })
    }

    fun clearVideoHistory() {
        writeVideoList(emptyList())
    }

    private fun writeVideoList(entries: List<VideoEntry>) {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(
                JSONObject()
                    .put("url", entry.url)
                    .put("title", entry.title)
                    .put("position", entry.positionMs)
                    .put("duration", entry.durationMs)
                    .put("time", entry.timeMs)
                    .put("headers", JSONArray(entry.headers))
            )
        }
        prefs.edit().putString(KEY_VIDEO_HISTORY, SecurePrefs.encrypt(arr.toString())).apply()
    }

    // ------------------------------------------------------------ internals

    private fun readList(key: String): List<Entry> {
        val raw = prefs.getString(key, null)?.let { SecurePrefs.decrypt(it) } ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = obj.optString("url")
                if (url.isBlank()) null
                else Entry(
                    url = url,
                    title = obj.optString("title").ifBlank { url },
                    timeMs = obj.optLong("time", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeList(key: String, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(
                JSONObject()
                    .put("url", entry.url)
                    .put("title", entry.title)
                    .put("time", entry.timeMs)
            )
        }
        prefs.edit().putString(key, SecurePrefs.encrypt(arr.toString())).apply()
    }

    companion object {
        const val MAX_HISTORY = 200
        const val MAX_VIDEO_HISTORY = 100
        private const val KEY_HISTORY = "history"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_INCOGNITO = "incognito"
        private const val KEY_VIDEO_HISTORY = "video_history"
    }
}
