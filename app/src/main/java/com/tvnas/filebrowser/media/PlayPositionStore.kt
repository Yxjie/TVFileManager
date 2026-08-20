package com.tvnas.filebrowser.media

import android.content.Context
import com.tvnas.filebrowser.smb.SmbFile

/**
 * 视频播放位置记录（断点续播）
 *
 * 以 服务器/共享/路径 为键，值中附带文件大小+修改时间，
 * 读取时校验一致才返回位置 —— 避免远程文件被替换后错误续播。
 */
object PlayPositionStore {

    private const val PREFS_NAME = "video_positions"

    private fun key(server: String, share: String, file: SmbFile): String =
        "$server/$share/${file.path.replace('\\', '/')}"

    fun save(context: Context, server: String, share: String, file: SmbFile, positionMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key(server, share, file), "$positionMs|${file.size}|${file.lastModified}")
            .apply()
    }

    /**
     * 返回记录的播放位置（毫秒），无记录或文件已变化时返回 0
     */
    fun get(context: Context, server: String, share: String, file: SmbFile): Long {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key(server, share, file), null) ?: return 0L
        val parts = raw.split("|")
        if (parts.size != 3) return 0L
        val position = parts[0].toLongOrNull() ?: return 0L
        val size = parts[1].toLongOrNull() ?: return 0L
        val lastModified = parts[2].toLongOrNull() ?: return 0L
        return if (size == file.size && lastModified == file.lastModified) position else 0L
    }

    fun clear(context: Context, server: String, share: String, file: SmbFile) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key(server, share, file))
            .apply()
    }
}
