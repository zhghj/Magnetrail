package app.magnetrail.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual fun createSubtitleEngine(modelRoot: String, workDir: String): SubtitleEngine =
    DesktopSubtitleEngineStub()

/** Desktop 没有 ASR 运行时，也没有播放器，所以永远返回「不支持」。 */
class DesktopSubtitleEngineStub : SubtitleEngine {

    override val available: Boolean = false

    private val _state = MutableStateFlow<SubtitleState>(
        SubtitleState.NeedsModel("Desktop 不支持 AI 字幕")
    )
    override val state: StateFlow<SubtitleState> = _state.asStateFlow()

    override suspend fun generateOffline(
        key: String,
        audio: String,
        language: String?,
        translateTo: String?
    ): SubtitleResult = SubtitleResult.NeedsModel

    override suspend fun installModels(): Boolean = false

    override fun cancel() = Unit
}
