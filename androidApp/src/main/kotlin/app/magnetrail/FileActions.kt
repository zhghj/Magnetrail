package app.magnetrail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

object FileActions {

    private val VIDEO = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "m2ts", "3gp", "flv", "wmv", "mpg", "mpeg", "ogv"
    )
    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "mka")

    fun extensionOf(file: File): String = file.name.substringAfterLast('.', "").lowercase()

    fun isPlayable(file: File): Boolean {
        val ext = extensionOf(file)
        return ext in VIDEO || ext in AUDIO
    }

    fun isVideo(file: File): Boolean = extensionOf(file) in VIDEO

    fun isVideoName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO

    fun mimeOf(file: File): String {
        val ext = extensionOf(file)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun openExternal(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), mimeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun share(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeOf(file))
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun deleteFile(file: File) {
        runCatching {
            file.delete()
            // drop now-empty torrent folders
            var parent = file.parentFile
            while (parent != null && parent.listFiles()?.isEmpty() == true) {
                parent.delete()
                parent = parent.parentFile
            }
        }
    }

    fun listFiles(root: File): List<File> {
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .onEnter { it == root || (it.isDirectory && it.name != ".parts") }
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .toList()
    }
}
