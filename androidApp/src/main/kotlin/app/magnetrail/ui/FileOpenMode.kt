package app.magnetrail.ui

/**
 * How a file in the Files tab is opened.
 *
 * [DOWNLOAD] 下载（仅未下完的文件提供）：把该文件设为下载并回到任务页看进度
 * [STREAM]   边下边看（本地 HTTP 流，未下完也能播）
 * [BUFFER]   缓冲观看（临时/不保存，交给播放器收尾时清理）
 * [LOCAL]    本地播放（已经是完整文件）
 */
enum class FileOpenMode(val label: String) {
    DOWNLOAD("下载"),
    STREAM("边下边看"),
    BUFFER("缓冲观看"),
    LOCAL("本地播放")
}
