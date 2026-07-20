package com.tvnas.filebrowser.media

/**
 * 视频文件 MIME 类型映射
 */
object MimeTypeUtil {

    private val VIDEO_MIME_TYPES = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "wmv" to "video/x-ms-wmv",
        "flv" to "video/x-flv",
        "webm" to "video/webm",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "ts" to "video/mp2t",
        "3gp" to "video/3gpp",
        "vob" to "video/dvd"
    )

    fun getVideoMimeType(extension: String): String {
        return VIDEO_MIME_TYPES[extension.lowercase()] ?: "video/*"
    }
}
