package app.magnetrail.shared

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.floor

/**
 * 把容器里的音频轨解码成 **16kHz 单声道 PCM16 文件**。
 *
 * 为什么不直接读整个 FloatArray：
 *  - 1 小时片在 16k 下也有 57.6M 个样本（230MB），手机内存扛不住；
 *  - 写盘后可以按块读，识别和分段都能流式做，失败了也能重试某一段。
 *
 * 解码用 MediaCodec（AAC/AC3/Opus 都由系统解码器处理），重采样用线性插值 ——
 * 对 16k 单声道 ASR 来说，线性插值带来的高频损失完全可以接受。
 */
object MediaAudioDecoder {

    const val TARGET_RATE = 16_000

    /**
     * @param onProgress 0..1（按容器时长估算；拿不到时长就不回调）
     * @return 写出的样本数；0 表示没有音频轨/解码失败
     */
    fun extractToPcm(
        source: File,
        out: File,
        onProgress: (Float) -> Unit = {}
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return 0
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return 0
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)

            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val resampler = StreamResampler(sourceRate, TARGET_RATE)
            var totalSamples = 0L
            out.parentFile?.mkdirs()
            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { sink ->
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                var pcmFloat = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex) ?: continue
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = codec.outputFormat
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                .coerceAtLeast(1)
                            val encoding = runCatching {
                                outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }.getOrDefault(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            pcmFloat = encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        else -> if (outIndex >= 0) {
                            val buffer = codec.getOutputBuffer(outIndex)
                            if (buffer != null && info.size > 0) {
                                val mono = toMono(buffer, info, channels, pcmFloat)
                                val resampled = resampler.process(mono)
                                totalSamples += writePcm16(sink, resampled)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (durationUs > 0 && info.presentationTimeUs > 0) {
                                onProgress(
                                    (info.presentationTimeUs.toFloat() / durationUs)
                                        .coerceIn(0f, 1f)
                                )
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
            return totalSamples
        } catch (t: Throwable) {
            return 0
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * 只解码 [startMs, startMs+durationMs) 这一段音频到 PCM 文件（边看边生成用）。
     *
     * 对还没下载完的文件：seek 到未下载区域会读不到数据，返回 0 —— 调用方隔几秒重试，
     * 这就是「按缓冲窗口推进」的实现方式。
     */
    fun extractRangeToPcm(
        source: File,
        out: File,
        startMs: Long,
        durationMs: Long
    ): Long = extractRangeInternal({ it.setDataSource(source.absolutePath) }, out, startMs, durationMs)

    /** 直链 http(s) 版本（服务端需支持 Range）。 */
    fun extractRemoteRangeToPcm(
        url: String,
        headers: Map<String, String>,
        out: File,
        startMs: Long,
        durationMs: Long
    ): Long = extractRangeInternal({ it.setDataSource(url, headers) }, out, startMs, durationMs)

    private fun extractRangeInternal(
        configure: (MediaExtractor) -> Unit,
        out: File,
        startMs: Long,
        durationMs: Long
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            configure(extractor)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return 0
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return 0
            val sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            extractor.selectTrack(trackIndex)
            val startUs = startMs * 1_000
            val endUs = startUs + durationMs * 1_000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val resampler = StreamResampler(sourceRate, TARGET_RATE)
            var written = 0L
            var produced = false
            out.parentFile?.mkdirs()
            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { sink ->
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                var pcmFloat = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(5_000)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex) ?: continue
                            val size = extractor.readSampleData(buffer, 0)
                            val timeUs = extractor.sampleTime
                            if (size < 0 || timeUs > endUs) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, timeUs, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outIndex = codec.dequeueOutputBuffer(info, 5_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = codec.outputFormat
                            channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                .coerceAtLeast(1)
                            val encoding = runCatching {
                                outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }.getOrDefault(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            pcmFloat = encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        else -> if (outIndex >= 0) {
                            val buffer = codec.getOutputBuffer(outIndex)
                            if (buffer != null && info.size > 0) {
                                val mono = toMono(buffer, info, channels, pcmFloat)
                                val resampled = resampler.process(mono)
                                written += writePcm16(sink, resampled)
                                produced = true
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
            return if (produced) written else 0L
        } catch (t: Throwable) {
            return 0
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun toMono(
        buffer: java.nio.ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
        float: Boolean
    ): FloatArray {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val frameBytes = if (float) 4 * channels else 2 * channels
        val frames = info.size / frameBytes
        val out = FloatArray(frames)
        if (float) {
            val fb = buffer.asFloatBuffer()
            for (i in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += fb.get(i * channels + c)
                out[i] = sum / channels
            }
        } else {
            val sb = buffer.asShortBuffer()
            for (i in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) sum += sb.get(i * channels + c) / 32768f
                out[i] = sum / channels
            }
        }
        return out
    }

    private fun writePcm16(sink: BufferedOutputStream, samples: FloatArray): Int {
        if (samples.isEmpty()) return 0
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        sink.write(bytes)
        return samples.size
    }

    /** 流式线性重采样：每块只带一个样本的衔接，避免整轨常驻内存。 */
    private class StreamResampler(private val srcRate: Int, private val dstRate: Int) {
        private val step = if (dstRate > 0) srcRate.toDouble() / dstRate else 1.0
        private var carry = 0.0
        private var tail = 0f
        private var initialized = false

        fun process(input: FloatArray): FloatArray {
            if (input.isEmpty()) return input
            if (srcRate == dstRate) return input

            val buf = FloatArray(input.size + 1)
            buf[0] = if (initialized) tail else input[0]
            input.copyInto(buf, 1)
            tail = input[input.size - 1]
            initialized = true

            val out = ArrayList<Float>((buf.size / step).toInt() + 2)
            var pos = carry
            while (pos < buf.size - 1) {
                val i = floor(pos).toInt()
                val frac = (pos - i).toFloat()
                out.add(buf[i] * (1 - frac) + buf[i + 1] * frac)
                pos += step
            }
            carry = pos - (buf.size - 1)
            val result = FloatArray(out.size)
            for (i in result.indices) result[i] = out[i]
            return result
        }
    }
}

/** 按块读 16k 单声道 PCM16 文件（识别阶段用）。 */
class PcmReader(val file: File) {

    val sampleCount: Long = file.length() / 2

    fun read(startSample: Long, count: Int): FloatArray {
        if (startSample >= sampleCount || count <= 0) return FloatArray(0)
        val n = minOf(count.toLong(), sampleCount - startSample).toInt()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(startSample * 2)
            val bytes = ByteArray(n * 2)
            raf.readFully(bytes)
            val out = FloatArray(n)
            for (i in 0 until n) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                val v = (hi shl 8) or lo
                out[i] = v / 32768f
            }
            return out
        }
    }
}
