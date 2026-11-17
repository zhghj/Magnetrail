package app.magnetrail.shared

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网页视频的「旁路音频」：只取音频数据，拼成连续的 16k 单声道 PCM，供逐段字幕使用。
 *
 * 两条路：
 *  - **直链 mp4**：MediaExtractor 直接按 URL 打开（服务端支持 Range），按时间区间 seek 解码。
 *  - **HLS**：解析 m3u8，优先用独立的音频轨（`EXT-X-MEDIA:TYPE=AUDIO`），否则用**码率最低**的
 *    视频变体（字幕只需要音频，低清变体省流量）；按 `#EXTINF` 逐段下载、解码、拼接，
 *    段与段之间靠累积时长桥接（这就是「分段桥接」）。
 *
 * 不支持：SAMPLE-AES 加密、byte-range 分片、直播型（无完整 EXTINF 列表）。
 */
class RemoteAudioSource(
    private val url: String,
    headers: Array<String>?,
    private val workDir: File,
    /** 每次生成任务唯一：临时文件必须带这个后缀，否则两个任务会互相覆盖。 */
    private val sessionId: String = ""
) {
    private val headerMap: Map<String, String> = buildMap {
        headers?.forEach { line ->
            val at = line.indexOf(':')
            if (at > 0) put(line.substring(0, at).trim(), line.substring(at + 1).trim())
        }
    }

    private val isHls = url.contains(".m3u8", ignoreCase = true) ||
        url.contains("m3u8", ignoreCase = true)

    // ------------------------------------------------------------ HLS 状态
    private var playlistLoaded = false
    private var playlistError: String? = null
    private var segmentInit: String? = null
    private var segments: List<HlsSegment> = emptyList()
    private var nextSegment = 0
    private val keyCache = HashMap<String, ByteArray>()

    private data class HlsSegment(
        val uri: String,
        val seconds: Double,
        val keyUri: String?,
        val ivHex: String?
    )

    /** 当前解析相对路径用的基准（主列表或子列表的 URL）。 */
    private var baseUrl: String = url

    /** 站点源头，用作默认 Referer（不少 CDN 校验它）。 */
    private val origin: String = runCatching {
        val u = URL(url)
        "${u.protocol}://${u.host}/"
    }.getOrDefault("")

    /** 已解码的连续 PCM（媒体时间单调递增）。 */
    private val cache = ArrayList<PcmChunk>()
    private var cacheEndMs = 0L
    private var initBytes: ByteArray? = null

    private data class PcmChunk(val startMs: Long, val samples: FloatArray)

    /** 总时长；未知返回 0。 */
    var durationMs: Long = 0L
        private set

    /** 取 [startMs, startMs + maxMs) 的 16k 单声道音频；拿不到返回空数组。 */
    fun readChunk(startMs: Long, maxMs: Long): FloatArray {
        return if (isHls) readHls(startMs, maxMs) else readProgressive(startMs, maxMs)
    }

    // ------------------------------------------------------------ 直链 mp4

    private fun readProgressive(startMs: Long, maxMs: Long): FloatArray {
        val pcm = File(workDir.apply { mkdirs() }, "remote-chunk-$sessionId.pcm")
        try {
            val extractor = android.media.MediaExtractor()
            // 支持 http(s) 的 setDataSource(String)（内部走 MediaHTTPConnection）
            extractor.setDataSource(url, headerMap)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return FloatArray(0)
            if (durationMs == 0L) {
                val fmt = extractor.getTrackFormat(track)
                durationMs = runCatching {
                    fmt.getLong(android.media.MediaFormat.KEY_DURATION) / 1_000
                }.getOrDefault(0L)
            }
            extractor.release()

            val got = MediaAudioDecoder.extractRemoteRangeToPcm(url, headerMap, pcm, startMs, maxMs)
            if (got <= 0) return FloatArray(0)
            return PcmReader(pcm).read(0, got.toInt())
        } catch (t: Throwable) {
            Log.w(TAG, "progressive read failed: ${t.message}")
            return FloatArray(0)
        } finally {
            runCatching { pcm.delete() }
        }
    }

    // ------------------------------------------------------------ HLS

    private fun readHls(startMs: Long, maxMs: Long): FloatArray {
        if (!playlistLoaded && playlistError == null) loadPlaylist()
        playlistError?.let { return FloatArray(0) }
        if (segments.isEmpty()) return FloatArray(0)

        // 解码到覆盖 startMs + maxMs
        while (cacheEndMs < startMs + maxMs && nextSegment < segments.size) {
            val seg = segments[nextSegment]
            if (!decodeSegment(seg, nextSegment)) {
                // 单段失败：跳过（避免整条流水线卡死）
                Log.w(TAG, "segment failed: ${redact(seg.uri)}")
            }
            cacheEndMs += (seg.seconds * 1000).toLong()
            nextSegment++
        }
        // 丢掉用不到的旧数据
        while (cache.size > 1 && cache[0].startMs + cache[0].samples.size * 1000 / 16_000 < startMs) {
            cache.removeAt(0)
        }
        return slice(startMs, maxMs)
    }

    private fun slice(startMs: Long, maxMs: Long): FloatArray {
        val from = startMs
        val to = startMs + maxMs
        val out = ArrayList<Float>(maxMs.toInt() * 16)
        cache.forEach { chunk ->
            val chunkEnd = chunk.startMs + chunk.samples.size * 1000 / 16_000
            if (chunkEnd <= from || chunk.startMs >= to) return@forEach
            val begin = ((from - chunk.startMs).coerceAtLeast(0) * 16).toInt()
            val end = (((to - chunk.startMs).coerceAtMost(chunkEnd - chunk.startMs)) * 16).toInt()
            for (i in begin until end.coerceAtMost(chunk.samples.size)) out.add(chunk.samples[i])
        }
        val arr = FloatArray(out.size)
        for (i in arr.indices) arr[i] = out[i]
        return arr
    }

    private fun decodeSegment(seg: HlsSegment, sequence: Int): Boolean {
        val full = absolute(seg.uri) ?: return false
        val file = File(workDir.apply { mkdirs() }, "hls-seg-$sessionId.tmp")
        val pcm = File(workDir, "hls-seg-$sessionId.pcm")
        return try {
            var payload = download(full)
            val keyUri = seg.keyUri
            if (keyUri != null) {
                payload = decrypt(payload, keyUri, seg.ivHex, sequence) ?: return false
            }
            val init = initBytes
            file.outputStream().use { sink ->
                if (init != null) sink.write(init)
                sink.write(payload)
            }
            val got = MediaAudioDecoder.extractRangeToPcm(file, pcm, 0, 300_000)
            if (got <= 0) {
                val tracks = runCatching {
                    val ex = android.media.MediaExtractor()
                    ex.setDataSource(file.absolutePath)
                    val info = (0 until ex.trackCount).joinToString(",") { i ->
                        ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME) ?: "?"
                    }
                    ex.release()
                    info
                }.getOrDefault("?")
                Log.w(
                    TAG,
                    "segment decoded 0 samples (${file.length()}B, tracks=$tracks): ${redact(full)}"
                )
                return false
            }
            val samples = PcmReader(pcm).read(0, got.toInt())
            cache.add(PcmChunk(cacheEndMs, samples))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "decode segment failed: ${t.message}")
            false
        } finally {
            runCatching { file.delete() }
            runCatching { pcm.delete() }
        }
    }

    private fun loadPlaylist() {
        playlistLoaded = true
        try {
            val text = String(download(url), Charsets.UTF_8)
            if (!text.contains("#EXTM3U")) {
                playlistError = "不是 m3u8"
                return
            }
            if (text.contains("#EXT-X-STREAM-INF")) {
                // 1) 优先独立音频轨（只有声音、码率低）；byte-range 分片的音频轨不适用
                val audioUri = Regex("#EXT-X-MEDIA:TYPE=AUDIO[^\\n]*URI=\"([^\"]+)\"")
                    .find(text)?.groupValues?.get(1)
                if (audioUri != null) {
                    val childUrl = absolute(audioUri)
                    val child = childUrl?.let {
                        runCatching { String(download(it), Charsets.UTF_8) }.getOrNull()
                    }
                    if (childUrl != null && child != null && !child.contains("#EXT-X-BYTERANGE")) {
                        baseUrl = childUrl
                        Log.i(TAG, "hls source=audio rendition")
                        parseMedia(child)
                        if (segments.isNotEmpty()) return
                    } else {
                        Log.i(TAG, "audio rendition unusable, fallback to lowest variant")
                    }
                }
                // 2) 回退：最低码率视频变体
                val variant = parseVariants(text).minByOrNull { it.second }
                val target = variant?.first ?: return
                val childUrl = absolute(target) ?: return
                baseUrl = childUrl
                Log.i(TAG, "hls source=variant bw=${variant.second}")
                val child = String(download(childUrl), Charsets.UTF_8)
                parseMedia(child)
            } else {
                parseMedia(text)
            }
        } catch (t: Throwable) {
            playlistError = t.message
            Log.w(TAG, "playlist failed: ${t.message}")
        }
    }

    private fun parseVariants(text: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val lines = text.lines()
        lines.forEachIndexed { index, line ->
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val uri = lines.getOrNull(index + 1)?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("#") }
                if (uri != null) out.add(uri to bw)
            }
        }
        return out
    }

    private fun parseMedia(text: String) {
        playlistError = null
        if (text.contains("METHOD=SAMPLE-AES")) {
            playlistError = "SAMPLE-AES 加密暂不支持"
            Log.w(TAG, "sample-aes playlist skipped")
            return
        }
        if (text.contains("#EXT-X-BYTERANGE")) {
            playlistError = "byte-range 分片暂不支持"
            Log.w(TAG, "byte-range playlist skipped")
            return
        }
        val init = Regex("#EXT-X-MAP:URI=\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        if (init != null) initBytes = runCatching { download(absolute(init)!!) }.getOrNull()

        val segs = ArrayList<HlsSegment>()
        val lines = text.lines()
        var pendingDuration = 0.0
        var keyUri: String? = null
        var ivHex: String? = null
        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-KEY:") -> {
                    val method = Regex("METHOD=([A-Z0-9-]+)").find(trimmed)
                        ?.groupValues?.get(1)
                    if (method == "AES-128") {
                        keyUri = Regex("URI=\"([^\"]+)\"").find(trimmed)?.groupValues?.get(1)
                        ivHex = Regex("IV=0[xX]([0-9A-Fa-f]+)").find(trimmed)
                            ?.groupValues?.get(1)
                        Log.i(TAG, "hls aes-128 key=$keyUri")
                    } else {
                        keyUri = null
                        ivHex = null
                    }
                }

                trimmed.startsWith("#EXTINF:") -> {
                    pendingDuration = trimmed.removePrefix("#EXTINF:")
                        .substringBefore(',').toDoubleOrNull() ?: 0.0
                }

                trimmed.isNotEmpty() && !trimmed.startsWith("#") -> {
                    segs.add(HlsSegment(trimmed, pendingDuration, keyUri, ivHex))
                    pendingDuration = 0.0
                }
            }
        }
        segments = segs
        durationMs = (segs.sumOf { it.seconds } * 1000).toLong()
        Log.i(TAG, "hls segments=${segs.size} duration=${durationMs / 1000}s")
    }

    /** AES-128 分片解密；返回 null 表示失败。 */
    private fun decrypt(data: ByteArray, keyUri: String, ivHex: String?, sequence: Int): ByteArray? =
        runCatching {
            val keyUrl = absolute(keyUri) ?: return null
            val key = keyCache.getOrPut(keyUrl) { download(keyUrl) }
            val iv = if (ivHex != null) {
                val hex = ivHex.removePrefix("0x").padStart(32, '0')
                ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            } else {
                // RFC 8216：没写 IV 时用媒体序列号（128 位大端）
                ByteArray(16).also { b ->
                    var v = sequence.toLong()
                    for (i in 15 downTo 8) {
                        b[i] = (v and 0xFF).toByte()
                        v = v shr 8
                    }
                }
            }
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.IvParameterSpec(iv)
            )
            cipher.doFinal(data)
        }.getOrElse {
            Log.w(TAG, "decrypt failed: ${it.message}")
            null
        }

    private fun absolute(uri: String): String? = runCatching {
        URL(URL(baseUrl), uri).toString()
    }.getOrNull()

    private fun download(target: String): ByteArray {
        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            headerMap.forEach { (k, v) -> setRequestProperty(k, v) }
            // CDN 常常校验 UA / Referer；调用方没带就补一个浏览器默认值
            if (headerMap.keys.none { it.equals("User-Agent", true) }) {
                setRequestProperty("User-Agent", BROWSER_UA)
            }
            if (headerMap.keys.none { it.equals("Referer", true) } && origin.isNotBlank()) {
                setRequestProperty("Referer", origin)
            }
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "MagnetrailRemoteAudio"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** 日志里去掉 query（可能有 auth_key / token / sign）。 */
        private fun redact(url: String): String =
            if (url.contains('?')) url.substringBefore('?') + "?<redacted>" else url
    }
}
