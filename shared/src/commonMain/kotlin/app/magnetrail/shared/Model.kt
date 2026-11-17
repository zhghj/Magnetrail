package app.magnetrail.shared

/** What the torrent is currently doing. Maps libtorrent state_t onto a small, UI friendly set. */
enum class TorrentState {
    METADATA,      // fetching .torrent metadata from DHT/peers (magnet link stage)
    CHECKING,      // hashing files already on disk
    DOWNLOADING,
    SEEDING,       // finished, still sharing
    PAUSED,
    FINISHED,
    ERROR,
    QUEUED
}

data class TorrentFile(
    val index: Int,
    val path: String,
    val sizeBytes: Long,
    val progress: Double,
    val wanted: Boolean
) {
    val displayName: String get() = path.substringAfterLast('/')
}

data class TorrentItem(
    val infoHash: String,
    val name: String,
    val state: TorrentState,
    val progress: Double,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val downloadRate: Long,
    val uploadRate: Long,
    val peers: Int,
    val seeds: Int,
    val etaSeconds: Long,
    val isPaused: Boolean,
    val hasMetadata: Boolean,
    val error: String?,
    val savePath: String,
    val files: List<TorrentFile>,
    /** true = "缓冲观看" entry: data goes to the temp dir and is deleted when playback ends. */
    val isTransient: Boolean = false
) {
    val isActive: Boolean
        get() = state == TorrentState.DOWNLOADING || state == TorrentState.METADATA ||
            state == TorrentState.CHECKING || state == TorrentState.QUEUED

    val isDone: Boolean
        get() = state == TorrentState.FINISHED || state == TorrentState.SEEDING
}
