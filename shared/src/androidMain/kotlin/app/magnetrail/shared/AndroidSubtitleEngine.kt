package app.magnetrail.shared

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

actual fun createSubtitleEngine(modelRoot: String, workDir: String): SubtitleEngine =
    AndroidSubtitleEngine(modelRoot, workDir)

/**
 * 离线字幕：媒体音频 → 16k 单声道 PCM → 能量分段 → SenseVoice 识别 → SRT。
 *
 * 模型（228MB）不进 APK，首次使用时下载到 `modelRoot`；下载源用 hf-mirror，
 * 因为 huggingface.co 在目标网络不可达。
 *
 * 中文/日文/英文/韩文/粤语共用 SenseVoice-small，语言由模型自动判断（language=""）。
 */
class AndroidSubtitleEngine(
    private val modelRoot: String,
    private val workDir: String
) : SubtitleEngine {

    private val modelDir = File(modelRoot)
    private val modelFile = File(modelDir, "model.int8.onnx")
    private val tokensFile = File(modelDir, "tokens.txt")

    override val available: Boolean
        get() = modelFile.length() > 50L * 1024 * 1024 && tokensFile.length() > 1_000

    private val _state = MutableStateFlow<SubtitleState>(
        if (available) SubtitleState.Idle else SubtitleState.NeedsModel(MODEL_HINT)
    )
    override val state: StateFlow<SubtitleState> = _state.asStateFlow()

    @Volatile
    private var cancelled = false

    /** 世代号：只允许最新一次生成继续跑，旧任务自动退出（避免两个任务抢同一批临时文件）。 */
    private val generationSeq = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var activeGeneration = 0

    /** 用户手动指定的源语言（"ja"/"en"/"zh"）；null = 自动识别。 */
    @Volatile
    var forcedSourceLang: String? = null

    private fun nextGeneration(): Int = generationSeq.incrementAndGet().also { activeGeneration = it }

    private fun ensureCurrent(seq: Int) {
        if (seq != activeGeneration) throw IllegalStateException("已被新的生成任务接管")
    }

    // ------------------------------------------------------------ 模型

    /** 下载模型（约 228MB）。成功返回 true，状态会同步推送进度。 */
    override suspend fun installModels(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            modelDir.mkdirs()
            download(MODEL_URL, modelFile, "识别模型", 0f, 0.95f)
            download(TOKENS_URL, tokensFile, "词表", 0.95f, 1f)
            if (!available) throw IllegalStateException("模型下载不完整")
            _state.value = SubtitleState.Idle
            Log.i(TAG, "models ready: ${modelFile.length() / (1024 * 1024)}MB")
            true
        }.getOrElse {
            _state.value = SubtitleState.Error("模型下载失败：${it.message}")
            false
        }
    }

    private fun download(
        url: String,
        target: File,
        label: String,
        from: Float,
        to: Float
    ) {
        val tmp = File(target.absolutePath + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            conn.connect()
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var written = 0L
                    while (true) {
                        if (cancelled) throw IllegalStateException("已取消")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) {
                            val p = from + (to - from) * (written.toFloat() / total)
                            _state.value = SubtitleState.Working(
                                p,
                                "$label ${written / (1024 * 1024)}MB / ${total / (1024 * 1024)}MB"
                            )
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) throw IllegalStateException("无法写入 ${target.name}")
        } finally {
            conn.disconnect()
            runCatching { tmp.delete() }
        }
    }

    // ------------------------------------------------------------ 生成

    override suspend fun generateOffline(
        key: String,
        audio: String,
        language: String?,
        translateTo: String?
    ): SubtitleResult = withContext(Dispatchers.IO) {
        generateBlocking(key, audio, language, translateTo)
    }

    /** 边看边生成：识别完一块就把累计字幕推给播放器。 */
    override suspend fun generateProgressive(
        key: String,
        audio: String,
        onCues: (List<SubtitleCue>) -> Unit
    ): SubtitleResult = withContext(Dispatchers.IO) {
        generateBlocking(key, audio, null, null, emit = onCues)
    }

    /**
     * 逐段识别（每 60 秒一段）+ 实时翻译，适合「边下边看」：
     * 已下载的部分先算，算不到就等 3 秒重试，所以字幕跟着下载进度走。
     */
    override suspend fun generateIncremental(
        key: String,
        audio: String,
        translateTo: String?,
        onCues: (List<SubtitleCue>) -> Unit
    ): SubtitleResult = withContext(Dispatchers.IO) {
        incrementalBlocking(key, audio, translateTo, onCues)
    }

    /** 网页视频（HLS / 直链 mp4）的逐段字幕：旁路取音频，不占播放器带宽之外的额外视频流量。 */
    suspend fun generateIncrementalRemote(
        key: String,
        url: String,
        headers: Array<String>?,
        translateTo: String?,
        onCues: (List<SubtitleCue>) -> Unit
    ): SubtitleResult = withContext(Dispatchers.IO) {
        if (!available) return@withContext SubtitleResult.NeedsModel
        val seq = nextGeneration()
        val source = RemoteAudioSource(url, headers, File(workDir), "g$seq")
        val recognizer = buildRecognizer(null)
        val all = ArrayList<SubtitleCue>()
        var cursor = 0L
        var detected = ""
        var videoLang = ""
        var misses = 0
        cancelled = false
        try {
            while (!cancelled) {
                ensureCurrent(seq)
                if (source.durationMs > 0 && cursor >= source.durationMs - 500) break
                val readStart = (cursor - OVERLAP_MS).coerceAtLeast(0L)
                val block = source.readChunk(readStart, CHUNK_MS + OVERLAP_MS)
                if (block.isEmpty()) {
                    if (++misses > 40) break
                    _state.value = SubtitleState.Working(0.5f, "等待网页缓冲… 已识别 ${all.size} 句")
                    Thread.sleep(3_000)
                    continue
                }
                misses = 0
                val raw = ArrayList<SubtitleCue>()
                SpeechSegments.detect(block).forEach { span ->
                    if (cancelled) throw IllegalStateException("已取消")
                    val clip = SpeechSegments.slice(block, span)
                    val (text, lang) = recognizeClip(recognizer, clip)
                    if (detected.isEmpty() && lang.isNotBlank()) detected = lang
                    if (isMeaningful(text)) {
                        raw.add(
                            SubtitleCue(readStart + span.startMs, readStart + span.endMs, text)
                        )
                    }
                }
                // 识别器没给语言时按文字推断，避免翻译被静默跳过
                if (detected.isBlank()) {
                    detected = raw.firstOrNull()?.let { inferLang(it.text) } ?: ""
                    if (detected.isNotBlank()) Log.i(TAG, "lang inferred: $detected")
                }
                // 合句（更自然的翻译）+ 去掉重叠区重复
                val fresh = SubtitleCues.mergeSentences(raw)
                    .filter { it.endMs > cursor }
                    .toMutableList()
                // 一旦在整段里看到日语特征，就锁定本片源语言，避免后续被误判成中文
                if (inferLang(fresh.joinToString("") { it.text }) == "ja") videoLang = "ja"
                val src = forcedSourceLang ?: if (videoLang.isNotBlank()) videoLang
                else sourceLang(detected, fresh, translateTo)
                if (translateTo != null && src.isNotBlank() &&
                    !translateTo.equals(src, ignoreCase = true) &&
                    MlKitTranslation.supported(src) && MlKitTranslation.supported(translateTo)
                ) {
                    val texts = MlKitTranslation.translate(
                        fresh.map { it.text }, src, translateTo
                    )
                    for (i in fresh.indices) fresh[i] = fresh[i].copy(translated = texts.getOrNull(i))
                }
                all.addAll(fresh)
                onCues(all.toList())
                cursor = readStart + block.size * 1000L / 16_000
                Log.i(
                    TAG,
                    "remote chunk @${cursor}ms +${fresh.size} (total ${all.size}) " +
                        "tr=${fresh.count { it.translated != null }}/${fresh.size} " +
                        "src=$detected first=${fresh.firstOrNull()?.text?.take(14)}"
                )
                _state.value = SubtitleState.Working(
                    0.5f, "网页字幕：已到 ${cursor / 1000}s · ${all.size} 句"
                )
            }
            _state.value = SubtitleState.Idle
            SubtitleResult.Ok
        } catch (t: Throwable) {
            Log.w(TAG, "remote incremental failed", t)
            _state.value = SubtitleState.Error(t.message ?: "网页字幕中断")
            SubtitleResult.Failed(t.message ?: "网页字幕中断")
        } finally {
            runCatching { recognizer.release() }
        }
    }

    private fun incrementalBlocking(        key: String,
        audio: String,
        translateTo: String?,
        onCues: (List<SubtitleCue>) -> Unit
    ): SubtitleResult {
        val media = File(audio)
        if (!media.exists()) return SubtitleResult.Failed("找不到文件：$audio")
        if (!available) return SubtitleResult.NeedsModel
        cancelled = false
        val seq = nextGeneration()
        val recognizer = buildRecognizer(null)
        val pcm = File(File(workDir).apply { mkdirs() }, "live-${safeName(key)}-g$seq.pcm")
        val all = ArrayList<SubtitleCue>()
        var cursor = 0L
        var detected = ""
        var videoLang = ""
        var misses = 0
        val totalMs = runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(media.absolutePath)
            val d = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            retriever.release()
            d
        }.getOrDefault(0L)
        return try {
            while (!cancelled) {
                ensureCurrent(seq)
                if (totalMs > 0 && cursor >= totalMs - 500) break
                val readStart = (cursor - OVERLAP_MS).coerceAtLeast(0L)
                val got = MediaAudioDecoder.extractRangeToPcm(
                    media, pcm, readStart, CHUNK_MS + OVERLAP_MS
                )
                if (got <= 0L) {
                    Log.w(TAG, "chunk @${cursor}ms decoded 0 samples (miss=$misses)")
                    // 这一段还没下载到：等一会儿再来（最多等 2 分钟）
                    if (++misses > 40) break
                    _state.value = SubtitleState.Working(
                        0.5f, "等待缓冲… 已识别 ${all.size} 句"
                    )
                    Thread.sleep(3_000)
                    continue
                }
                misses = 0
                val reader = PcmReader(pcm)
                val block = reader.read(0, reader.sampleCount.toInt())
                if (block.isNotEmpty()) {
                    val raw = ArrayList<SubtitleCue>()
                    SpeechSegments.detect(block).forEach { span ->
                        if (cancelled) throw IllegalStateException("已取消")
                        val clip = SpeechSegments.slice(block, span)
                        val (text, lang) = recognizeClip(recognizer, clip)
                        if (detected.isEmpty() && lang.isNotBlank()) detected = lang
                        if (isMeaningful(text)) {
                            raw.add(
                                SubtitleCue(readStart + span.startMs, readStart + span.endMs, text)
                            )
                        }
                    }
                    if (detected.isBlank()) {
                        detected = raw.firstOrNull()?.let { inferLang(it.text) } ?: ""
                        if (detected.isNotBlank()) Log.i(TAG, "lang inferred: $detected")
                    }
                    // 合句 + 去掉重叠区里已经出现过的部分（按媒体时序追加）
                    val fresh = SubtitleCues.mergeSentences(raw)
                        .filter { it.endMs > cursor }
                        .toMutableList()
                    if (inferLang(fresh.joinToString("") { it.text }) == "ja") videoLang = "ja"
                    val src = forcedSourceLang ?: if (videoLang.isNotBlank()) videoLang
                    else sourceLang(detected, fresh, translateTo)
                    if (translateTo != null && src.isNotBlank() &&
                        !translateTo.equals(src, ignoreCase = true) &&
                        MlKitTranslation.supported(src) &&
                        MlKitTranslation.supported(translateTo)
                    ) {
                        val texts = MlKitTranslation.translate(
                            fresh.map { it.text }, src, translateTo
                        )
                        for (i in fresh.indices) {
                            fresh[i] = fresh[i].copy(translated = texts.getOrNull(i))
                        }
                    }
                    all.addAll(fresh)
                    onCues(all.toList())
                    Log.i(TAG, "chunk @${cursor}ms -> +${fresh.size} cues (total ${all.size})")
                }
                cursor = readStart + got * 1000 / 16_000
                _state.value = SubtitleState.Working(
                    0.5f, "边看边生成：已到 ${cursor / 1000}s · ${all.size} 句"
                )
            }
            if (all.isNotEmpty()) {
                val srt = File(media.parentFile, "${media.nameWithoutExtension}.asr.srt")
                srt.writeText(SubtitleSrt.format(all))
                if (translateTo != null && all.any { it.translated != null }) {
                    val t = File(
                        media.parentFile,
                        "${media.nameWithoutExtension}.${langTag(translateTo)}.srt"
                    )
                    t.writeText(SubtitleSrt.format(all, translated = true))
                }
                Log.i(TAG, "incremental wrote ${all.size} cues for ${media.name}")
            }
            _state.value = SubtitleState.Idle
            SubtitleResult.Ok
        } catch (t: Throwable) {
            Log.w(TAG, "incremental failed", t)
            _state.value = SubtitleState.Error(t.message ?: "生成中断")
            SubtitleResult.Failed(t.message ?: "生成中断")
        } finally {
            runCatching { recognizer.release() }
            runCatching { pcm.delete() }
        }
    }

    /** 解码 + 识别都是阻塞调用，必须在 IO 线程（放主线程会 ANR）。 */
    private fun generateBlocking(
        key: String,
        audio: String,
        language: String?,
        translateTo: String?,
        emit: ((List<SubtitleCue>) -> Unit)? = null
    ): SubtitleResult {
        val media = File(audio)
        if (!media.exists()) return SubtitleResult.Failed("找不到文件：$audio")
        if (!available) {
            _state.value = SubtitleState.NeedsModel(MODEL_HINT)
            return SubtitleResult.NeedsModel
        }
        cancelled = false

        val pcmFile = File(workDir.apply { File(this).mkdirs() }, "asr-${safeName(key)}.pcm")
        var recognizer: OfflineRecognizer? = null
        return try {
            _state.value = SubtitleState.Working(0f, "解码音频…")
            val samples = MediaAudioDecoder.extractToPcm(media, pcmFile) { p ->
                _state.value = SubtitleState.Working(p * 0.35f, "解码音频 ${(p * 100).toInt()}%")
            }
            if (samples <= 0) return SubtitleResult.Failed("这个文件没有可解码的音轨")

            recognizer = buildRecognizer(language)
            val reader = PcmReader(pcmFile)
            val recognized = recognize(reader, recognizer, emit)
            // 合句：碎片合成整句后再写盘/翻译，译文更自然
            val cues = SubtitleCues.mergeSentences(recognized.first)
            val detected = recognized.second
            if (cues.isEmpty()) {
                _state.value = SubtitleState.Error("没有识别到人声")
                return SubtitleResult.Failed("没有识别到人声")
            }

            val srt = File(media.parentFile, "${media.nameWithoutExtension}.${langTag(language)}.srt")
            srt.writeText(SubtitleSrt.format(cues))
            Log.i(TAG, "wrote ${srt.name}: ${cues.size} cues, ${samples / 16000}s audio, lang=$detected")

            // 需要译文才跑翻译；先纠正误判的源语言，再判断"是否同语言"
            val srcLang = sourceLang(detected, cues, translateTo)
            if (translateTo != null && srcLang.isNotBlank() &&
                !translateTo.equals(srcLang, ignoreCase = true) &&
                MlKitTranslation.supported(srcLang) && MlKitTranslation.supported(translateTo)
            ) {
                translateAndWrite(media, cues, srcLang, translateTo)
            }
            _state.value = SubtitleState.Idle
            SubtitleResult.Ok
        } catch (t: Throwable) {
            Log.w(TAG, "generate failed", t)
            _state.value = SubtitleState.Error(t.message ?: "生成失败")
            SubtitleResult.Failed(t.message ?: "生成失败")
        } finally {
            runCatching { recognizer?.release() }
            runCatching { pcmFile.delete() }
        }
    }

    override fun cancel() {
        cancelled = true
    }

    /** 逐句翻译并写成 `<名字>.<目标语言>.srt`；失败不影响原文已生成的成果。 */
    private fun translateAndWrite(
        media: File,
        cues: List<SubtitleCue>,
        source: String,
        target: String
    ) {
        val out = File(media.parentFile, "${media.nameWithoutExtension}.${langTag(target)}.srt")
        // 旧译文先删掉：本次翻译失败时不能留下上一版/假译文
        runCatching { out.delete() }
        _state.value = SubtitleState.Working(0.98f, "检查翻译模型…")
        try {
            val translated = MlKitTranslation.translate(
                cues.map { it.text }, source, target,
                onStatus = { msg -> _state.value = SubtitleState.Working(0.98f, msg) },
                onProgress = { p ->
                    _state.value = SubtitleState.Working(0.98f, "翻译中 ${(p * 100).toInt()}%")
                }
            )
            val merged = cues.mapIndexed { index, cue ->
                cue.copy(translated = translated.getOrNull(index))
            }
            out.writeText(SubtitleSrt.format(merged, translated = true))
            Log.i(TAG, "wrote ${out.name}: ${merged.size} cues, $source -> $target")
        } catch (t: Throwable) {
            Log.w(TAG, "translate failed", t)
            _state.value = SubtitleState.Error(
                "原文已生成，翻译失败：${t.message?.take(60) ?: "未知错误"}"
            )
        }
    }

    private fun buildRecognizer(language: String?): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = modelFile.absolutePath,
                    language = language ?: "",
                    useInverseTextNormalization = true
                ),
                tokens = tokensFile.absolutePath,
                numThreads = 4,
                debug = false,
                provider = "cpu"
            ),
            decodingMethod = "greedy_search"
        )
        return OfflineRecognizer(config = config)
    }

    /** 按 2 分钟一块读 PCM，块内分段、块内识别，时间轴加上块偏移。 */
    private fun recognize(
        reader: PcmReader,
        recognizer: OfflineRecognizer,
        emit: ((List<SubtitleCue>) -> Unit)? = null
    ): Pair<List<SubtitleCue>, String> {
        val blockSamples = 120 * 16_000
        val cues = ArrayList<SubtitleCue>()
        var blockStart = 0L
        var detected = ""
        val total = reader.sampleCount.coerceAtLeast(1L)

        while (blockStart < reader.sampleCount) {
            if (cancelled) throw IllegalStateException("已取消")
            val block = reader.read(blockStart, blockSamples)
            if (block.isEmpty()) break
            val spans = SpeechSegments.detect(block)
            spans.forEach { span ->
                if (cancelled) throw IllegalStateException("已取消")
                val clip = SpeechSegments.slice(block, span)
                val result = recognizeClip(recognizer, clip)
                if (detected.isEmpty() && result.second.isNotBlank()) detected = result.second
                val text = result.first
                if (isMeaningful(text)) {
                    val offset = blockStart * 1000 / 16_000
                    cues.add(SubtitleCue(offset + span.startMs, offset + span.endMs, text))
                }
                val p = 0.35f + 0.63f * ((blockStart + block.size).toFloat() / total)
                _state.value = SubtitleState.Working(p.coerceIn(0.35f, 0.98f), "识别中 ${cues.size} 句")
            }
            // 每 2 分钟一块：块结束就把累计结果推出去，播放器可以立刻用上
            emit?.invoke(cues.toList())
            blockStart += block.size
        }
        return cues to detected
    }

    /** 返回（去掉标签的文本, SenseVoice 判定的语言）。 */
    private fun recognizeClip(
        recognizer: OfflineRecognizer,
        clip: FloatArray
    ): Pair<String, String> {
        if (clip.size < 1_000) return "" to ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples = clip, sampleRate = 16_000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            // SenseVoice 会带 <|ja|><|NEUTRAL|> 这类标签，字幕要剥掉；语言码在标签里面
            result.text.replace(TAG_PATTERN, "").trim() to parseLang(result.lang)
        } finally {
            stream.release()
        }
    }

    /** 纯标点/语气符号不算一句话（SenseVoice 在噪声段会吐 "." 这种东西）。 */
    private fun isMeaningful(text: String): Boolean =
        text.any { it.isLetterOrDigit() }

    /** `<|ja|>` → `ja`；没有标签时按文字推断，避免翻译被静默跳过。 */
    private fun parseLang(raw: String): String {
        val fromTag = LANG_TAG.find(raw)?.groupValues?.getOrNull(1)
        val tag = (fromTag ?: raw).trim().lowercase()
        if (tag.isNotBlank()) return tag
        return ""
    }

    /** 语言标签为空时，按文字脚本粗略判断（日语/韩语/其余当英语）。 */
    private fun inferLang(text: String): String = when {
        // 假名、长音符、日式引号：基本只出现在日语里
        text.any { it in '\u3040'..'\u30FF' } || text.contains('ー') ||
            text.contains('「') || text.contains('」') -> "ja"
        text.any { it in '\uAC00'..'\uD7AF' } -> "ko"
        text.any { it in '\u4E00'..'\u9FFF' } -> "zh"
        text.any { it.isLetter() } -> "en"
        else -> ""
    }

    /**
     * 翻译用的源语言：优先用识别器给的标签；
     * 但如果它和译文语言相同（常见误判），而文字明显是另一种语言（例如有假名），以文字为准。
     */
    private fun sourceLang(
        detected: String,
        cues: List<SubtitleCue>,
        target: String?
    ): String {
        // 用整段文本判断，别只看第一句（第一句可能全是汉字，会被判成中文）
        val sample = cues.joinToString("") { it.text }.take(240)
        if (sample.isBlank()) return detected
        if (detected.isBlank()) return inferLang(sample)
        if (target != null && detected.equals(target, ignoreCase = true)) {
            val inferred = inferLang(sample)
            if (inferred.isNotBlank() && !inferred.equals(target, ignoreCase = true)) {
                return inferred
            }
        }
        return detected
    }

    private fun safeName(key: String): String =
        key.take(24).replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun langTag(language: String?): String = when (language?.lowercase()) {
        "ja", "jp" -> "ja"
        "en" -> "en"
        "zh" -> "zh"
        "ko" -> "ko"
        else -> "asr"
    }

    companion object {
        private const val TAG = "MagnetrailAsr"
        /** 边看边生成的分段长度。 */
        private const val CHUNK_MS = 60_000L

        /** 相邻两段的重叠：跨段句子不会被切断两次（去重后只留一份）。 */
        private const val OVERLAP_MS = 1_500L
        private const val BASE = "https://hf-mirror.com/csukuangfj/" +
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"
        const val MODEL_URL = "$BASE/model.int8.onnx"
        const val TOKENS_URL = "$BASE/tokens.txt"
        const val MODEL_HINT = "AI 字幕需要先下载模型（约 228MB）"
        private val TAG_PATTERN = Regex("<\\|[^|]{1,16}\\|>")
        private val LANG_TAG = Regex("<\\|([A-Za-z]{2,8})\\|>")
    }
}
