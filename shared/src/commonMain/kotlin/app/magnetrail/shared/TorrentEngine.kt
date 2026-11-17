package app.magnetrail.shared

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform independent torrent session facade.
 *
 * Android implements this on top of libtorrent (frostwire jlibtorrent).
 * The desktop target ships a stub so shared code and tests still compile.
 */
interface TorrentEngine {

    /** Live list of torrents, ordered by the time they were added. */
    val items: StateFlow<List<TorrentItem>>

    /** Human readable diagnostics (last 200 lines). */
    val logs: StateFlow<List<String>>

    /** True once the native session is up and listening. */
    val running: StateFlow<Boolean>

    /** Starts (or re-attaches to) the native session. Safe to call repeatedly. */
    fun start(saveRoot: String, tempRoot: String)

    /** Stops the session and flushes resume data. */
    fun shutdown()

    /** Adds a magnet link. Returns false when the link was rejected (duplicate / invalid). */
    fun addMagnet(link: MagnetLink, savePath: String): Boolean

    fun pause(infoHash: String)

    fun resume(infoHash: String)

    fun remove(infoHash: String, deleteFiles: Boolean)

    /** 0 = don't download this file, 1 = normal priority. */
    fun setFileWanted(infoHash: String, fileIndex: Int, wanted: Boolean)

    /**
     * Local HTTP url that serves this file through the running session, so video/audio
     * can be played while it is still downloading. Null when the torrent/file is not
     * ready yet (magnet metadata missing, unknown index, ...).
     */
    fun streamUrl(infoHash: String, fileIndex: Int): String?

    /**
     * 某个文件的本地磁盘路径（边下边看时用来另路解码音频、边看边生成字幕）。
     * 默认 null：平台不支持或该文件不存在。
     */
    fun filePath(infoHash: String, fileIndex: Int): String? = null

    /**
     * Called by the player when it starts/stops reading a local stream. While any player
     * holds the stream open the download session must stay alive (otherwise the local
     * HTTP server is closed mid-playback and playback dies).
     */
    fun beginStreamingPlayback()

    fun endStreamingPlayback()

    /**
     * 流播结束（关播放器）：清掉这个任务的"顺序下载"，让 libtorrent 回到多片并行，
     * 否则一次边下边看之后整个任务都会按顺序慢慢下。
     */
    fun endStreamingFile(infoHash: String)

    /** true while a player uses a stream (or the stream server served data recently). */
    fun isStreamingActive(): Boolean

    /**
     * 全局上传限速（字节/秒），0 = 不限。会立即生效并用于下次会话启动。
     * 推荐最低 128 KB/s：再低会明显影响 BT 的互惠（别人不愿回传给你）。
     */
    fun setUploadRateLimit(bytesPerSecond: Int)

    /**
     * "缓冲观看" (buffer & watch, nothing kept): adds the torrent into a temp dir, waits for
     * metadata, picks the largest video file, and hands back a local stream url.
     * [onReady] is called with (url, title). Data is deleted by [stopTransient] when playback ends.
     */
    fun streamTransient(
        link: MagnetLink,
        onReady: (url: String, title: String) -> Unit,
        onError: (message: String) -> Unit
    )

    /** Stops a buffer-only stream and deletes its temporary data. No-op for normal tasks. */
    fun stopTransient(infoHash: String)

    /** Forces an immediate progress refresh (UI pull-to-refresh, notification tick). */
    fun refresh()
}

expect fun createTorrentEngine(): TorrentEngine
