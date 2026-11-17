package app.magnetrail

import android.app.Application
import app.magnetrail.shared.SubtitleEngine
import app.magnetrail.shared.TorrentEngine
import app.magnetrail.shared.createSubtitleEngine
import app.magnetrail.shared.createTorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class MagnetrailApp : Application() {

    lateinit var engine: TorrentEngine
        private set

    lateinit var store: TorrentStore
        private set

    /** remembered "打开方式" per file path (Files tab ⋮ menu). */
    lateinit var fileModes: FileModeStore
        private set

    /** app-wide prefs (upload rate limit). */
    lateinit var appSettings: AppSettings
        private set

    /** 浏览器：历史 / 收藏 / 无痕。 */
    lateinit var browserStore: BrowserStore
        private set

    /** AI 字幕（离线 ASR + 翻译）。 */
    lateinit var subtitleEngine: SubtitleEngine
        private set

    /** AI 字幕生成任务放 Application 作用域：关掉播放器也继续跑。 */
    val subtitleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 批量生成队列（文件页用）。 */
    lateinit var subtitleBatch: SubtitleBatchJob
        private set

    /** Where torrents are written: Android/data/<pkg>/files/Downloads (no storage permission needed). */
    lateinit var saveRoot: File
        private set

    /** Throw-away dir for "缓冲观看" (deleted when playback stops / next app start). */
    lateinit var tempRoot: File
        private set

    override fun onCreate() {
        super.onCreate()
        engine = createTorrentEngine()
        store = TorrentStore(this)
        fileModes = FileModeStore(this)
        appSettings = AppSettings(this)
        browserStore = BrowserStore(this)
        subtitleEngine = createSubtitleEngine(
            modelRoot = File(getExternalFilesDir(null) ?: filesDir, "models/sensevoice").absolutePath,
            workDir = File(cacheDir, "asr").absolutePath
        )
        subtitleBatch = SubtitleBatchJob(appSettings, subtitleEngine)
        saveRoot = File(getExternalFilesDir(null) ?: filesDir, "Downloads").apply { mkdirs() }
        tempRoot = File(cacheDir, "torrent-stream").apply {
            // stale buffer data from a previous process that died mid-playback
            listFiles()?.forEach { runCatching { it.deleteRecursively() } }
            mkdirs()
        }
        Notifications.createChannel(this)
    }
}
