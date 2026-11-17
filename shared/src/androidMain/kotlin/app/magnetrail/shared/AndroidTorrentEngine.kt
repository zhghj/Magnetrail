package app.magnetrail.shared

import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.FileStorage
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentStatus
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.swig.remove_flags_t
import com.frostwire.jlibtorrent.swig.torrent_flags_t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

actual fun createTorrentEngine(): TorrentEngine = AndroidTorrentEngine()

/**
 * libtorrent session wrapper.
 *
 * Rules of the house:
 *  - every SWIG object call is guarded with runCatching: a magnet without metadata,
 *    a removed handle or a busy disk must never crash the UI.
 *  - one coroutine polls every torrent and publishes an immutable snapshot.
 */
class AndroidTorrentEngine : TorrentEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _items = MutableStateFlow<List<TorrentItem>>(emptyList())
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    private val _running = MutableStateFlow(false)

    override val items: StateFlow<List<TorrentItem>> = _items.asStateFlow()
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    private var session: SessionManager? = null
    private var pollJob: Job? = null
    private var saveRoot: String = ""
    private var tempRoot: String = ""
    private val knownNames = HashMap<String, String>()
    private val streamServer = TorrentStreamServer(sessionProvider = { session }, log = { addLog(it) })

    /** bytes/sec, 0 = unlimited. Applied to the session settings on start. */
    @Volatile
    private var uploadLimitBytes = 0

    /** number of players currently reading a local stream (keeps the session alive). */
    private val streamingPlayers = java.util.concurrent.atomic.AtomicInteger(0)

    /** hashes added through "缓冲观看" (temp dir, deleted after playback). */
    private val transientHashes = HashSet<String>()

    /**
     * 已经套用过"仅下载视频"默认规则的任务（内存内去重）。
     * 套用后用户手动打开的非视频文件不会被反复 IGNORE。
     */
    private val videoOnlyApplied = HashSet<String>()

    private data class PendingStream(
        val onReady: (url: String, title: String) -> Unit,
        val onError: (message: String) -> Unit,
        val deleteWhenDone: Boolean
    )

    private val pendingStreams = HashMap<String, PendingStream>()

    @Synchronized
    override fun start(saveRoot: String, tempRoot: String) {
        if (session != null) return
        this.saveRoot = saveRoot
        this.tempRoot = tempRoot
        try {
            File(saveRoot).mkdirs()
            File(tempRoot).mkdirs()

            val settings = SettingsPack()
                .connectionsLimit(200)
                .alertQueueSize(1000)
            settings.setEnableDht(true)
            settings.setEnableLsd(true)
            // 0 = unlimited; 应用里默认 128 KB/s（全局上传限速）
            settings.uploadRateLimit(uploadLimitBytes)
            // IPv4 only: [::] listening fails on many Android ROMs (enum_route) and
            // only produces listen_failed noise, IPv6 peers still connect outbound.
            settings.listenInterfaces("0.0.0.0:6881")

            val manager = SessionManager(false)
            manager.start(SessionParams(settings))
            manager.addListener(object : AlertListener {
                override fun types(): IntArray = intArrayOf(
                    AlertType.TORRENT_ERROR.swig(),
                    AlertType.METADATA_FAILED.swig(),
                    AlertType.FILE_ERROR.swig(),
                    AlertType.TRACKER_ERROR.swig(),
                    AlertType.LISTEN_FAILED.swig()
                )

                override fun alert(alert: Alert<*>) {
                    val text = safeWhat(alert)
                    val detail = runCatching { alert.message() }.getOrNull()
                    addLog(if (detail.isNullOrBlank()) "alert: $text" else "alert: $text — $detail")
                }
            })
            session = manager
            _running.value = true
            addLog("libtorrent session started, save dir: $saveRoot")
            pollJob = scope.launch {
                while (isActive) {
                    refresh()
                    delay(600)
                }
            }
        } catch (t: Throwable) {
            addLog("session start failed: ${t.javaClass.simpleName}: ${t.message}")
            session = null
            _running.value = false
        }
    }

    override fun shutdown() {
        val job = pollJob
        pollJob = null
        job?.cancel()
        streamServer.stop()
        // drop buffer-only torrents and their temp files before closing the session
        transientHashes.toList().forEach { runCatching { removeTransientNow(it) } }
        transientHashes.clear()
        pendingStreams.clear()
        val manager = session
        session = null
        _running.value = false
        _items.value = emptyList()
        if (manager != null) {
            scope.launch {
                runCatching {
                    manager.saveState()
                    manager.stop()
                }
                addLog("libtorrent session stopped")
            }
        }
    }

    override fun addMagnet(link: MagnetLink, savePath: String): Boolean {
        val manager = session ?: run {
            addLog("add rejected: session not started")
            return false
        }
        return runCatching {
            val hash = Sha1Hash(link.infoHash)
            if (manager.find(hash) != null) {
                addLog("add skipped, already in session: ${link.infoHash}")
                return false
            }
            File(savePath).mkdirs()
            knownNames[link.infoHash] = link.displayName ?: link.shortHash
            manager.download(link.uri, File(savePath), torrent_flags_t())
            addLog("queued ${link.displayName ?: link.infoHash} (dht nodes: ${runCatching { manager.dhtNodes() }.getOrDefault(-1)})")
            refresh()
            true
        }.getOrElse { t ->
            addLog("add failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    override fun pause(infoHash: String) {
        handle(infoHash)?.let { th ->
            runCatching { th.pause() }
                .onFailure { addLog("pause failed: ${it.message}") }
            refresh()
        }
    }

    override fun resume(infoHash: String) {
        handle(infoHash)?.let { th ->
            runCatching { th.resume() }
                .onFailure { addLog("resume failed: ${it.message}") }
            refresh()
        }
    }

    override fun remove(infoHash: String, deleteFiles: Boolean) {
        val manager = session ?: return
        val th = handle(infoHash)
        if (th == null) {
            addLog("remove: handle not found for $infoHash")
            return
        }
        val name = knownNames[infoHash]
        runCatching {
            manager.remove(th, remove_flags_t.from_int(if (deleteFiles) 1 else 0))
        }.onFailure { addLog("remove failed: ${it.message}") }

        if (deleteFiles) {
            try {
                val dir = File(saveRoot, name ?: infoHash)
                if (dir.exists()) dir.deleteRecursively()
            } catch (t: Throwable) {
                addLog("delete failed: ${t.message}")
            }
        }
        knownNames.remove(infoHash)
        videoOnlyApplied.remove(infoHash)
        refresh()
    }

    override fun setFileWanted(infoHash: String, fileIndex: Int, wanted: Boolean) {
        handle(infoHash)?.let { th ->
            runCatching {
                th.filePriority(fileIndex, if (wanted) Priority.NORMAL else Priority.IGNORE)
            }.onFailure { addLog("file priority failed: ${it.message}") }
            refresh()
        }
    }

    override fun beginStreamingPlayback() {
        streamingPlayers.incrementAndGet()
    }

    override fun endStreamingPlayback() {
        streamingPlayers.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    override fun endStreamingFile(infoHash: String) {
        // buffer-only 任务在关闭播放器时整个移除，不需要恢复并行
        if (infoHash in transientHashes) return
        handle(infoHash)?.let { th ->
            runCatching { th.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
            addLog("stream closed, back to parallel piece download: ${infoHash.take(8)}")
        }
    }

    override fun isStreamingActive(): Boolean =
        streamingPlayers.get() > 0 || streamServer.servedRecently()

    override fun setUploadRateLimit(bytesPerSecond: Int) {
        uploadLimitBytes = bytesPerSecond.coerceAtLeast(0)
        session?.let { sm ->
            runCatching {
                sm.applySettings(SettingsPack().uploadRateLimit(uploadLimitBytes))
                addLog(
                    "upload limit = " +
                        if (uploadLimitBytes == 0) "unlimited"
                        else "${uploadLimitBytes / 1024} KB/s"
                )
            }.onFailure { addLog("upload limit apply failed: ${it.message}") }
        }
    }

    override fun streamUrl(infoHash: String, fileIndex: Int): String? {
        val th = handle(infoHash) ?: return null
        val ti = runCatching { th.torrentFile() }.getOrNull() ?: return null
        val fs = runCatching { ti.files() }.getOrNull() ?: return null
        if (fileIndex < 0 || fileIndex >= fs.numFiles()) return null

        return runCatching {
            th.filePriority(fileIndex, Priority.NORMAL)
            th.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            val port = streamServer.ensureStarted()
            val url = "http://127.0.0.1:$port/stream/${streamServer.currentToken()}/$infoHash/$fileIndex"
            addLog("stream url ready")
            url
        }.getOrElse { t ->
            addLog("stream url failed: ${t.message}")
            null
        }
    }

    override fun filePath(infoHash: String, fileIndex: Int): String? {
        val th = handle(infoHash) ?: return null
        val ti = runCatching { th.torrentFile() }.getOrNull() ?: return null
        val fs = runCatching { ti.files() }.getOrNull() ?: return null
        if (fileIndex < 0 || fileIndex >= fs.numFiles()) return null
        val item = items.value.firstOrNull { it.infoHash.equals(infoHash, true) } ?: return null
        val entry = item.files.firstOrNull { it.index == fileIndex } ?: return null
        val file = java.io.File(item.savePath, entry.path)
        return file.absolutePath.takeIf { it.isNotBlank() }
    }

    override fun streamTransient(
        link: MagnetLink,
        onReady: (url: String, title: String) -> Unit,
        onError: (message: String) -> Unit
    ) {
        val manager = session ?: run {
            onError("下载会话还没启动，请稍后再试")
            return
        }
        val inSession = runCatching { manager.find(Sha1Hash(link.infoHash)) != null }
            .getOrDefault(false)
        // A magnet already running as a normal task keeps its files; a second "缓冲观看"
        // on the same magnet still ends with the temp data being deleted.
        val isSavedTask = inSession && link.infoHash !in transientHashes

        pendingStreams[link.infoHash] = PendingStream(
            onReady = onReady,
            onError = onError,
            deleteWhenDone = !isSavedTask
        )
        if (!inSession) {
            transientHashes.add(link.infoHash)
            File(tempRoot, link.infoHash).mkdirs()
            runCatching {
                manager.download(link.uri, File(tempRoot, link.infoHash), torrent_flags_t())
            }.onFailure { t ->
                pendingStreams.remove(link.infoHash)
                transientHashes.remove(link.infoHash)
                onError("无法开始缓冲：${t.message}")
                return
            }
            addLog("buffer-only stream queued: ${link.displayName ?: link.shortHash}")
        } else if (isSavedTask) {
            addLog("watch from an existing task, files are kept: ${link.shortHash}")
        } else {
            addLog("buffer-only stream re-attached: ${link.shortHash}")
        }
        refresh()
    }

    override fun stopTransient(infoHash: String) {
        pendingStreams.remove(infoHash)
        if (!transientHashes.remove(infoHash)) return
        // called from the player's onDestroy: delete the buffer off the main thread
        scope.launch {
            removeTransientNow(infoHash)
            addLog("buffer-only stream stopped, temp data removed: ${infoHash.take(8)}")
            refresh()
        }
    }

    override fun refresh() {
        val manager = session ?: return
        try {
            val handles = manager.getTorrentHandles()
            val snapshot = ArrayList<TorrentItem>(handles.size)
            for (h in handles) {
                // 新任务拿到元数据后：视频文件保留、非视频文件默认暂停（用户可再手动打开）
                runCatching { h.infoHash().toHex() }.getOrNull()?.let { hash ->
                    applyVideoOnlyDefaultIfNeeded(hash, h)
                }
                val result = runCatching { toItem(h) }
                val item = result.getOrNull()
                if (item != null) {
                    snapshot.add(item)
                } else {
                    val reason = result.exceptionOrNull()
                    val hash = runCatching { h.infoHash().toHex() }.getOrNull() ?: "?"
                    addLog(
                        "handle $hash not shown: " +
                            (reason?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "toItem returned null")
                    )
                }
            }
            _items.value = snapshot
            pumpPendingStreams(manager)
        } catch (t: Throwable) {
            addLog("refresh failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- internals

    private fun pumpPendingStreams(manager: SessionManager) {
        if (pendingStreams.isEmpty()) return
        val iterator = pendingStreams.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val hash = entry.key
            val request = entry.value

            val th = runCatching { manager.find(Sha1Hash(hash)) }.getOrNull() ?: continue
            val hasMeta = runCatching { th.status().hasMetadata() }.getOrDefault(false)
            if (!hasMeta) continue

            val ti = runCatching { th.torrentFile() }.getOrNull() ?: continue
            val fs = runCatching { ti.files() }.getOrNull() ?: continue

            val index = pickVideoIndex(fs)
            if (index == null) {
                iterator.remove()
                runCatching { request.onError("这个种子里没有可播放的视频文件") }
                if (request.deleteWhenDone) removeTransientNow(hash)
                continue
            }

            if (request.deleteWhenDone) {
                // buffer-only: download nothing but the video, in playback order
                for (i in 0 until fs.numFiles()) {
                    runCatching { th.filePriority(i, if (i == index) Priority.NORMAL else Priority.IGNORE) }
                }
                runCatching { th.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD, TorrentFlags.SEQUENTIAL_DOWNLOAD) }
            }

            val port = streamServer.ensureStarted()
            val url = "http://127.0.0.1:$port/stream/${streamServer.currentToken()}/$hash/$index"
            iterator.remove()
            addLog("buffer stream ready")
            runCatching { request.onReady(url, runCatching { fs.fileName(index) }.getOrDefault("视频")) }
        }
    }

    /**
     * 默认只下视频：拿到元数据后把非视频文件设为 IGNORE，视频文件保持 NORMAL。
     * 只在每个任务第一次拿到元数据时套用一次，用户之后手动打开的文件不会被再次忽略。
     * 种子里没有任何视频文件时保持原样（否则会把整个任务都暂停）。
     */
    private fun applyVideoOnlyDefaultIfNeeded(hash: String, th: TorrentHandle) {
        if (hash in videoOnlyApplied || hash in transientHashes) return
        val hasMeta = runCatching { th.status().hasMetadata() }.getOrDefault(false)
        if (!hasMeta) return
        val fs = runCatching { th.torrentFile()?.files() }.getOrNull() ?: return
        val count = runCatching { fs.numFiles() }.getOrDefault(0)
        if (count <= 0) return

        val videos = (0 until count).filter { i ->
            runCatching { fs.filePath(i) }.getOrDefault("")
                .substringAfterLast('.', "").lowercase() in VIDEO_EXT
        }
        videoOnlyApplied.add(hash)
        if (videos.isEmpty()) {
            addLog("video-only: no video file in ${hash.take(8)}, downloading all $count files")
            return
        }
        val videoSet = videos.toSet()
        for (i in 0 until count) {
            runCatching {
                th.filePriority(i, if (i in videoSet) Priority.NORMAL else Priority.IGNORE)
            }
        }
        addLog(
            "video-only default: ${hash.take(8)} keep ${videos.size} video file(s), " +
                "pause ${count - videos.size} non-video file(s)"
        )
    }

    private fun pickVideoIndex(fs: FileStorage): Int? {
        var best = -1
        var bestSize = 0L
        for (i in 0 until fs.numFiles()) {
            val path = runCatching { fs.filePath(i) }.getOrDefault("")
            if (path.substringAfterLast('.', "").lowercase() !in VIDEO_EXT) continue
            val size = runCatching { fs.fileSize(i) }.getOrDefault(0L)
            if (size > bestSize) {
                best = i
                bestSize = size
            }
        }
        return if (best >= 0) best else null
    }

    private fun removeTransientNow(infoHash: String) {
        // note: pendingStreams is managed by the caller (iterator.remove / stopTransient)
        transientHashes.remove(infoHash)
        handle(infoHash)?.let { th ->
            runCatching { session?.remove(th, remove_flags_t.from_int(1)) }
        }
        runCatching { File(tempRoot, infoHash).deleteRecursively() }
        knownNames.remove(infoHash)
    }

    private fun handle(infoHash: String): TorrentHandle? =
        session?.let { runCatching { it.find(Sha1Hash(infoHash)) }.getOrNull() }

    private fun toItem(th: TorrentHandle): TorrentItem? {
        if (!th.isValid()) return null
        val st = th.status()
        val hash = runCatching { th.infoHash().toHex() }.getOrElse { return null }
        val paused = runCatching { st.flags().and_(TorrentFlags.PAUSED).nonZero() }.getOrDefault(false)
        val hasMeta = runCatching { st.hasMetadata() }.getOrDefault(false)

        // TorrentStatus.name() is often empty on Android in this libtorrent build;
        // TorrentInfo.name() is reliable once metadata is in.
        val infoName = if (hasMeta) {
            runCatching { th.torrentFile()?.name() }.getOrNull()
        } else null
        val name = infoName?.takeUnless { it.isNullOrBlank() }
            ?: runCatching { st.name() }.getOrNull()?.takeUnless { it.isNullOrBlank() }
            ?: knownNames[hash]
            ?: hash.take(8)

        if (hasMeta && !infoName.isNullOrBlank()) {
            knownNames[hash] = infoName
        } else if (knownNames[hash] == null) {
            knownNames[hash] = name
        }

        val total = runCatching { st.totalWanted() }.getOrDefault(0L).takeIf { it > 0 }
            ?: runCatching { st.total() }.getOrDefault(0L)
        val done = runCatching { st.totalWantedDone() }.getOrDefault(0L)
        val rate = runCatching { st.downloadRate() }.getOrDefault(0).toLong()
        val eta = if (rate > 0 && total > done) (total - done) / rate else 0L

        val files = if (hasMeta) readFiles(th) else emptyList()

        val error = runCatching {
            val ec = st.errorCode()
            if (ec != null && ec.value() != 0) ec.message() else null
        }.getOrNull()

        return TorrentItem(
            infoHash = hash,
            name = name,
            state = mapState(st, paused),
            progress = runCatching { st.progress().toDouble() }.getOrDefault(0.0),
            totalBytes = total,
            downloadedBytes = done,
            downloadRate = rate,
            uploadRate = runCatching { st.uploadRate() }.getOrDefault(0).toLong(),
            peers = runCatching { st.numPeers() }.getOrDefault(0),
            seeds = runCatching { st.numSeeds() }.getOrDefault(0),
            etaSeconds = eta,
            isPaused = paused,
            hasMetadata = hasMeta,
            error = error,
            savePath = if (hash in transientHashes) File(tempRoot, hash).absolutePath else saveRoot,
            files = files,
            isTransient = hash in transientHashes
        )
    }

    private fun readFiles(th: TorrentHandle): List<TorrentFile> {
        val info = th.torrentFile() ?: return emptyList()
        val fs = info.files() ?: return emptyList()
        val count = fs.numFiles()
        if (count <= 0) return emptyList()

        val progress = runCatching { th.fileProgress() }.getOrNull()
        val priorities = runCatching { th.filePriorities() }.getOrNull()

        val out = ArrayList<TorrentFile>(count)
        for (i in 0 until count) {
            val size = runCatching { fs.fileSize(i) }.getOrDefault(0L)
            val perFile = if (progress != null && i < progress.size && size > 0) {
                (progress[i].toDouble() / size.toDouble()).coerceIn(0.0, 1.0)
            } else 0.0
            val wanted = priorities?.getOrNull(i)?.let { it != Priority.IGNORE } ?: true
            out.add(
                TorrentFile(
                    index = i,
                    path = runCatching { fs.filePath(i) }.getOrElse { "file $i" },
                    sizeBytes = size,
                    progress = perFile,
                    wanted = wanted
                )
            )
        }
        return out
    }

    private fun mapState(st: TorrentStatus, paused: Boolean): TorrentState {
        if (paused) return TorrentState.PAUSED
        return when (runCatching { st.state() }.getOrNull()) {
            TorrentStatus.State.CHECKING_FILES,
            TorrentStatus.State.CHECKING_RESUME_DATA -> TorrentState.CHECKING
            TorrentStatus.State.DOWNLOADING_METADATA -> TorrentState.METADATA
            TorrentStatus.State.DOWNLOADING -> TorrentState.DOWNLOADING
            TorrentStatus.State.FINISHED -> TorrentState.FINISHED
            TorrentStatus.State.SEEDING -> TorrentState.SEEDING
            else -> TorrentState.QUEUED
        }
    }

    private fun safeWhat(alert: Alert<*>): String =
        runCatching { alert.what() }.getOrElse { alert.javaClass.simpleName }

    private fun addLog(line: String) {
        val current = _logs.value
        val next = current + line
        _logs.value = if (next.size > 200) next.takeLast(200) else next
    }

    private companion object {
        val VIDEO_EXT = setOf(
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "m2ts",
            "3gp", "flv", "wmv", "mpg", "mpeg", "ogv"
        )
    }
}
