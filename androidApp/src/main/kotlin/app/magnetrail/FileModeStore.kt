package app.magnetrail

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import app.magnetrail.ui.FileOpenMode

/**
 * Remembers the "打开方式" (stream / buffer / local) chosen for each file path,
 * so a plain tap on the row keeps using the mode the user picked first.
 */
class FileModeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("file_modes", Context.MODE_PRIVATE)

    // Compose-observable: reading it in the Files tab updates the ⋮ check mark / subtitle at once
    private val cache = mutableStateMapOf<String, FileOpenMode>().apply {
        prefs.all.forEach { (path, raw) ->
            val mode = runCatching { FileOpenMode.valueOf(raw as String) }.getOrNull()
            if (mode != null) put(path, mode)
        }
    }

    /** null = 用户还没选过，由调用方按"文件是否下完"推断默认值。 */
    fun modeOf(path: String): FileOpenMode? = cache[path]

    fun set(path: String, mode: FileOpenMode) {
        cache[path] = mode
        prefs.edit().putString(path, mode.name).apply()
    }
}
