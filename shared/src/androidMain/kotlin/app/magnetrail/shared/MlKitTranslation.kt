package app.magnetrail.shared

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit

/**
 * 端侧翻译：Google ML Kit 的本地翻译模型。
 *
 * 特点：模型（每种语言约 30–40MB）由 Play 服务下载到本机，**之后完全离线**；
 * 只有下载时需要联网，音频和字幕都不会上传。
 *
 * 依赖 GMS：本机（MIUI + Play 服务 24.31）可用；没有 GMS 的机器会直接抛异常，
 * 上层会降级成「只生成原文」。
 */
object MlKitTranslation {

    private const val TAG = "MagnetrailMt"

    fun supported(code: String): Boolean = tagOf(code) != null

    /** 语言模型是否已经下载到本机。 */
    fun isDownloaded(code: String): Boolean = runCatching {
        val tag = tagOf(code) ?: return false
        val model = TranslateRemoteModel.Builder(tag).build()
        val downloaded = Tasks.await(
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java),
            20, TimeUnit.SECONDS
        )
        downloaded.contains(model)
    }.getOrElse {
        Log.w(TAG, "isDownloaded failed: ${it.message}")
        false
    }

    /**
     * 逐句翻译。ML Kit 没有批量接口，190 句大约 10–30 秒。
     *
     * 模型下载必须走 `Translator.downloadModelIfNeeded`（用 RemoteModelManager
     * 下完仍然会报 "Translation model files not found"）。失败直接抛异常，
     * 由调用方决定「只保留原文」，绝不写入假译文。
     */
    fun translate(
        texts: List<String>,
        sourceCode: String,
        targetCode: String,
        onStatus: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {}
    ): List<String> {
        val src = tagOf(sourceCode) ?: throw IllegalStateException("不支持的语言：$sourceCode")
        val dst = tagOf(targetCode) ?: throw IllegalStateException("不支持的语言：$targetCode")
        if (src == dst) return texts

        val client = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(dst).build()
        )
        return try {
            if (!isDownloaded(targetCode)) onStatus("下载翻译模型…")
            Tasks.await(
                client.downloadModelIfNeeded(DownloadConditions.Builder().build()),
                10, TimeUnit.MINUTES
            )
            onStatus("翻译中…")

            val out = ArrayList<String>(texts.size)
            texts.forEachIndexed { index, text ->
                val translated = if (text.isBlank()) text
                else Tasks.await(client.translate(text), 30, TimeUnit.SECONDS)
                out.add(translated)
                if (texts.isNotEmpty()) onProgress((index + 1).toFloat() / texts.size)
            }
            out
        } finally {
            runCatching { client.close() }
        }
    }

    /** SenseVoice 的 lang 值（ja/en/zh/ko/yue）→ ML Kit 语言 tag。 */
    private fun tagOf(code: String): String? = when (code.lowercase()) {
        "zh", "zh-cn", "zh_cn", "chinese" -> TranslateLanguage.CHINESE
        "en", "english" -> TranslateLanguage.ENGLISH
        "ja", "jp", "japanese" -> TranslateLanguage.JAPANESE
        "ko", "korean" -> TranslateLanguage.KOREAN
        else -> null
    }
}
