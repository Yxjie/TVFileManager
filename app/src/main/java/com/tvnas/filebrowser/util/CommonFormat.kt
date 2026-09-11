package com.tvnas.filebrowser.util

/**
 * 文件大小与图片采样等通用工具方法，供多处复用（避免重复实现）。
 */
object CommonFormat {

    /**
     * 格式化字节数为可读字符串（B/KB/MB/GB/TB）
     */
    fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
