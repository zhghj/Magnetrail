package app.magnetrail

import app.magnetrail.shared.SubtitleEngine
import app.magnetrail.shared.SubtitleResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 批量生成字幕：按顺序把「还没有 `.asr.srt`」的视频逐个跑一遍。
 *
 * 单个 1 小时片要十几分钟，所以：
 *  - 队列跑在 Application 作用域（退出页面不中断）；
 *  - 每部片子跑完立即落盘，中途被系统杀掉也只会丢「当前这一部」；
 *  - 已有字幕的文件直接跳过，重复点击不会重跑。
 */
class SubtitleBatchJob(
    private val settings: AppSettings,
    private val engine: SubtitleEngine
) {

    data class State(
        val running: Boolean = false,
        val total: Int = 0,
        val index: Int = 0,
        val current: String = "",
        val ok: Int = 0,
        val failed: Int = 0,
        val finished: Boolean = false,
        val skipped: Int = 0
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** 还没有字幕、且体积像完整视频的文件；按体积升序，先出小片的成果。 */
    fun pending(videos: List<File>): List<File> = videos
        .filter { file -> file.exists() && file.length() > 256 * 1024 && !srtFor(file).exists() }
        .sortedBy { it.length() }

    fun srtFor(file: File): File =
        File(file.parentFile, "${file.nameWithoutExtension}.asr.srt")

    fun start(scope: CoroutineScope, videos: List<File>) {
        if (_state.value.running) return
        val work = pending(videos)
        if (work.isEmpty()) {
            _state.value = State(finished = true, skipped = videos.size)
            return
        }
        job = scope.launch {
            _state.value = State(running = true, total = work.size)
            var ok = 0
            var failed = 0
            for ((i, file) in work.withIndex()) {
                if (!isActive) break
                _state.value = _state.value.copy(index = i + 1, current = file.name)
                val target = if (settings.subtitleBilingual) settings.subtitleSecondLanguage else null
                val result = engine.generateOffline(file.name, file.absolutePath, null, target)
                if (result is SubtitleResult.Ok) ok++ else failed++
                _state.value = _state.value.copy(ok = ok, failed = failed)
            }
            _state.value = _state.value.copy(
                running = false,
                finished = true,
                current = ""
            )
        }
    }

    fun cancel() {
        engine.cancel()
        job?.cancel()
        _state.value = _state.value.copy(running = false, finished = true, current = "")
    }
}
