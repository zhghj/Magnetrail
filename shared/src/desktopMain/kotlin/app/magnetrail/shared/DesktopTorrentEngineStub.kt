package app.magnetrail.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop (JVM) placeholder. Keeps :shared compiling for a future desktop app;
 * the actual libtorrent session is only wired on Android for now.
 */
class DesktopTorrentEngineStub : TorrentEngine {

    private val _items = MutableStateFlow<List<TorrentItem>>(emptyList())
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    private val _running = MutableStateFlow(false)

    override val items: StateFlow<List<TorrentItem>> = _items.asStateFlow()
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    override fun start(saveRoot: String, tempRoot: String) {
        _running.value = false
        _logs.value = _logs.value + "desktop target: torrent engine not implemented yet"
    }

    override fun shutdown() = Unit

    override fun addMagnet(link: MagnetLink, savePath: String): Boolean = false

    override fun pause(infoHash: String) = Unit

    override fun resume(infoHash: String) = Unit

    override fun remove(infoHash: String, deleteFiles: Boolean) = Unit

    override fun setFileWanted(infoHash: String, fileIndex: Int, wanted: Boolean) = Unit

    override fun streamUrl(infoHash: String, fileIndex: Int): String? = null

    override fun beginStreamingPlayback() = Unit

    override fun endStreamingPlayback() = Unit

    override fun endStreamingFile(infoHash: String) = Unit

    override fun setUploadRateLimit(bytesPerSecond: Int) = Unit

    override fun isStreamingActive(): Boolean = false

    override fun streamTransient(
        link: MagnetLink,
        onReady: (String, String) -> Unit,
        onError: (String) -> Unit
    ) {
        onError("desktop target: streaming is not implemented")
    }

    override fun stopTransient(infoHash: String) = Unit

    override fun refresh() = Unit
}

actual fun createTorrentEngine(): TorrentEngine = DesktopTorrentEngineStub()
