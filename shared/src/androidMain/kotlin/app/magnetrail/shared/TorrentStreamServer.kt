package app.magnetrail.shared

import android.util.Log
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Tiny HTTP/1.1 range server bound to 127.0.0.1 that serves a torrent file which
 * may still be downloading ("边下边播").
 *
 * For each byte range request it maps file offsets to libtorrent pieces, waits for
 * the piece to be available (giving it a piece deadline so the downloader fetches it
 * first), flushes the write cache and streams the bytes from disk.
 *
 * The player (ExoPlayer) sees a normal progressive HTTP resource with the final
 * Content-Length; HTTP reads simply block until the needed piece lands.
 */
class TorrentStreamServer(
    private val sessionProvider: () -> SessionManager?,
    private val log: (String) -> Unit = {}
) {

    private var server: ServerSocket? = null
    private var port = 0
    private var pool: ExecutorService? = null

    /** 最近一次有人从这里读数据的时间：DownloadService 用它判断"还在播，别把会话停了"。 */
    @Volatile
    private var lastServedAtMs = 0L

    fun servedRecently(windowMs: Long = 120_000L): Boolean {
        val at = lastServedAtMs
        return at > 0 && System.currentTimeMillis() - at < windowMs
    }

    /** 每次起服务生成一次性令牌：只有本 App 拿到的 URL 能读流（本机其它 App 也能访问 127.0.0.1）。 */
    @Volatile
    private var token: String = ""

    fun currentToken(): String = token

    @Synchronized
    fun ensureStarted(): Int {
        server?.let { if (!it.isClosed) return port }

        token = java.util.UUID.randomUUID().toString().replace("-", "").take(20)
        val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = ss
        port = ss.localPort
        val executor = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "magnetrail-stream").apply { isDaemon = true }
        }
        pool = executor

        Thread({
            while (!ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (t: Throwable) {
                    break
                }
                executor.execute {
                    runCatching { serve(client) }
                        .onFailure { Log.w(TAG, "serve failed: ${it.message}") }
                }
            }
        }, "magnetrail-stream-accept").apply {
            isDaemon = true
            start()
        }

        Log.i(TAG, "stream server listening on 127.0.0.1:$port")
        log("stream server listening on 127.0.0.1:$port")
        return port
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        runCatching { pool?.shutdownNow() }
        pool = null
    }

    // ------------------------------------------------------------------ serving

    private fun serve(socket: Socket) {
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return error(s, 400, "Bad Request")

            val method = parts[0].uppercase()
            val target = parts[1]
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val key = line.substringBefore(':').trim().lowercase()
                headers[key] = line.substringAfter(':', "").trim()
            }

            val path = target.substringBefore('?')
            val segments = path.trim('/').split('/')
            // /stream/<token>/<infohash>/<index>
            if (segments.size != 4 || segments[0] != "stream") {
                return error(s, 404, "Not Found")
            }
            if (segments[1] != token) {
                Log.w(TAG, "rejected stream request with bad token")
                return error(s, 403, "Forbidden")
            }
            val infoHash = segments[2]
            val fileIndex = segments[3].toIntOrNull()
            if (fileIndex == null) return error(s, 400, "Bad file index")

            val manager = sessionProvider()
            if (manager == null) return error(s, 503, "Session not ready")

            val handle = runCatching { manager.find(Sha1Hash(infoHash)) }.getOrNull()
            if (handle == null || !handle.isValid()) return error(s, 404, "Torrent not found")

            val ti = runCatching { handle.torrentFile() }.getOrNull()
            if (ti == null) return error(s, 425, "Metadata not ready")

            val fs = runCatching { ti.files() }.getOrNull() ?: return error(s, 404, "No files")
            if (fileIndex < 0 || fileIndex >= fs.numFiles()) return error(s, 404, "Bad file index")

            val fileSize = fs.fileSize(fileIndex)
            if (fileSize <= 0) return error(s, 404, "Empty file")
            val fileOffset = fs.fileOffset(fileIndex)
            val pieceLength = ti.pieceLength().toLong()
            if (pieceLength <= 0) return error(s, 500, "Bad piece length")

            val name = fs.filePath(fileIndex)
            val file = File(handle.savePath() ?: "", name)

            // streaming should always pull this file at normal priority, in order
            runCatching { handle.filePriority(fileIndex, Priority.NORMAL) }
            runCatching { handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD, TorrentFlags.SEQUENTIAL_DOWNLOAD) }

            val rangeHeader = headers["range"]
            var start = 0L
            var end = fileSize - 1
            var partial = false
            if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
                val spec = rangeHeader.removePrefix("bytes=").substringBefore(',')
                val s = spec.substringBefore('-').trim()
                val e = spec.substringAfter('-', "").trim()
                if (s.isNotEmpty()) start = s.toLongOrNull() ?: 0L
                if (s.isEmpty() && e.isNotEmpty()) {
                    // suffix range: last N bytes
                    val n = e.toLongOrNull() ?: 0L
                    start = (fileSize - n).coerceAtLeast(0L)
                } else if (e.isNotEmpty()) {
                    end = (e.toLongOrNull() ?: (fileSize - 1)).coerceAtMost(fileSize - 1)
                }
                partial = true
            }
            if (start >= fileSize || end < start) return error(s, 416, "Range Not Satisfiable")

            val length = end - start + 1
            val head = buildString {
                append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: ").append(mimeOf(name)).append("\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: ").append(length).append("\r\n")
                if (partial) {
                    append("Content-Range: bytes ").append(start).append('-').append(end)
                        .append('/').append(fileSize).append("\r\n")
                }
                append("Cache-Control: no-cache\r\n")
                append("Connection: close\r\n\r\n")
            }

            val out = BufferedOutputStream(s.getOutputStream())
            out.write(head.toByteArray())
            out.flush()
            Log.i(TAG, "GET $infoHash#$fileIndex bytes=$start-$end/$fileSize")
            if (method == "HEAD") return

            streamRange(handle, file, fileOffset, pieceLength, start, end, out)
            out.flush()
        }
    }

    private fun streamRange(
        handle: TorrentHandle,
        file: File,
        fileOffset: Long,
        pieceLength: Long,
        start: Long,
        end: Long,
        out: BufferedOutputStream
    ) {
        var raf: RandomAccessFile? = null
        try {
            var pos = start
            while (pos <= end) {
                val piece = ((fileOffset + pos) / pieceLength).toInt()
                awaitPiece(handle, piece)
                // pull the next pieces first so playback keeps flowing
                runCatching { handle.setPieceDeadline(piece + 1, 8_000) }
                runCatching { handle.setPieceDeadline(piece + 2, 12_000) }

                if (raf == null) raf = RandomAccessFile(file, "r")
                val pieceLastOffset = (piece.toLong() + 1) * pieceLength - 1 - fileOffset
                val chunkEnd = minOf(end, pieceLastOffset)
                val size = (chunkEnd - pos + 1).toInt()
                val buffer = ByteArray(size)
                raf.seek(pos)
                raf.readFully(buffer)
                out.write(buffer)
                out.flush()
                lastServedAtMs = System.currentTimeMillis()
                pos = chunkEnd + 1
            }
        } finally {
            runCatching { raf?.close() }
        }
    }

    private fun awaitPiece(handle: TorrentHandle, piece: Int, timeoutMs: Long = 60_000) {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            if (!handle.isValid()) throw IOException("torrent removed")
            if (runCatching { handle.havePiece(piece) }.getOrDefault(false)) {
                // make sure the bytes left libtorrent's write cache before reading the file
                runCatching { handle.flushCache() }
                return
            }
            runCatching { handle.setPieceDeadline(piece, 4_000) }
            Thread.sleep(100)
        }
        throw IOException("piece $piece not available")
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var c = input.read()
        if (c < 0) return null
        while (c >= 0 && c != '\n'.code) {
            if (c != '\r'.code) sb.append(c.toChar())
            c = input.read()
        }
        return sb.toString()
    }

    private fun error(socket: Socket, code: Int, message: String) {
        runCatching {
            val body = "$code $message"
            val head = "HTTP/1.1 $code $message\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Content-Type: text/plain\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write((head + body).toByteArray())
            socket.getOutputStream().flush()
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "m2ts" -> "video/mp2t"
        "flv" -> "video/x-flv"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "opus", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    private companion object {
        const val TAG = "MagnetrailStream"
    }
}
