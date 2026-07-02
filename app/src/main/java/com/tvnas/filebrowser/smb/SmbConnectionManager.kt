package com.tvnas.filebrowser.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB 连接管理器
 *
 * 线程安全策略（细粒度锁）：
 * - connect / disconnect：@Synchronized 全局锁（修改连接状态，低频操作）
 * - listFiles：无锁（smbj DiskShare.list 内部独立打开/关闭文件，线程安全）
 * - downloadFile / getFileSize：仅在 openFile（获取句柄）时加锁，
 *   数据传输阶段不加锁，允许多个下载并发执行
 * - isConnected：无锁（仅读取 @Volatile 字段）
 */
class SmbConnectionManager {

    @Volatile
    private var session: SmbSession? = null

    /**
     * 连接到 SMB 服务器
     * 如果已有连接会先断开
     */
    @Synchronized
    @Throws(Exception::class)
    fun connect(
        host: String,
        port: Int = 445,
        shareName: String,
        username: String?,
        password: String?,
        domain: String? = null
    ) {
        session?.close()

        val config = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()

        val client = SMBClient(config)
        try {
            val connection = client.connect(host, port)
            try {
                val authContext = when {
                    username.isNullOrBlank() && password.isNullOrBlank() ->
                        AuthenticationContext.guest()
                    else -> AuthenticationContext(
                        username ?: "",
                        password?.toCharArray() ?: charArrayOf(),
                        domain ?: ""
                    )
                }

                val smbSession = connection.authenticate(authContext)
                val share = smbSession.connectShare(shareName) as? DiskShare
                    ?: throw IllegalStateException("无法连接到共享 '$shareName'（可能不是磁盘共享）")

                this.session = SmbSession(client, connection, smbSession, share, host, port, shareName)
            } catch (e: Exception) {
                runCatching { connection.close() }
                throw e
            }
        } catch (e: Exception) {
            runCatching { client.close() }
            throw e
        }
    }

    /**
     * 列出目录内容
     * 不加 @Synchronized：DiskShare 支持并发文件操作
     */
    @Throws(Exception::class)
    fun listFiles(path: String = ""): List<SmbFile> {
        val share = ensureShare()

        val entries = share.list(path)

        val files = mutableListOf<SmbFile>()
        for (entry in entries) {
            val fileName = entry.fileName
            if (fileName == "." || fileName == "..") continue

            val isDirectory = entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value > 0
            val size = entry.endOfFile
            val lastModified = entry.changeTime.toEpochMillis()
            val fullPath = if (path.isEmpty()) fileName else "$path\\$fileName"

            files.add(
                SmbFile(
                    name = fileName,
                    path = fullPath,
                    isDirectory = isDirectory,
                    size = size,
                    lastModified = lastModified
                )
            )
        }

        return files.sortedWith(
            compareByDescending<SmbFile> { it.isDirectory }.thenBy { it.name.lowercase() }
        )
    }

    /**
     * 下载文件（安全的资源管理，try/finally 保证句柄关闭）
     * 仅在 openFile 时加锁（毫秒级），数据传输阶段不加锁，允许多个下载并发
     */
    @Throws(Exception::class)
    fun downloadFile(
        remotePath: String,
        outputStream: OutputStream,
        progressCallback: ((Long, Long) -> Unit)? = null
    ) {
        // 仅在获取句柄时加锁（快速操作）
        val smbFile = synchronized(this) {
            ensureShare().openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
        }
        // 数据传输阶段不加锁，允许并发
        try {
            val totalSize = smbFile.fileInformation.standardInformation.endOfFile
            val inputStream = smbFile.inputStream
            try {
                val buffer = ByteArray(8192)
                var bytesRead = 0L
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    bytesRead += read
                    progressCallback?.invoke(bytesRead, totalSize)
                }
            } finally {
                runCatching { inputStream.close() }
            }
        } finally {
            runCatching { smbFile.close() }
        }
    }

    /**
     * 获取文件大小（仅在 openFile 时加锁）
     */
    @Throws(Exception::class)
    fun getFileSize(remotePath: String): Long {
        val smbFile = synchronized(this) {
            ensureShare().openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
        }
        try {
            return smbFile.fileInformation.standardInformation.endOfFile
        } finally {
            runCatching { smbFile.close() }
        }
    }

    /**
     * 断开连接并释放所有资源
     * 安全调用：多次调用不会抛异常
     */
    @Synchronized
    fun disconnect() {
        session?.close()
        session = null
    }

    /**
     * 检查是否已连接（快速检查，不涉及网络 IO）
     * 不加 @Synchronized：只读取 volatile 字段
     */
    fun isConnected(): Boolean {
        val s = session ?: return false
        return s.connection.isConnected
    }

    /**
     * 检查是否已连接到指定的 SMB 服务器
     * 用于避免不必要的重连（TCP 握手 + SMB 认证在电视上耗时数秒）
     */
    @Synchronized
    fun isConnectedTo(host: String, port: Int, shareName: String): Boolean {
        val s = session ?: return false
        if (!s.connection.isConnected) return false
        return s.host == host && s.port == port && s.shareName.equals(shareName, ignoreCase = true)
    }

    /**
     * 获取当前连接的 share，如果已断开则抛出异常
     */
    private fun ensureShare(): DiskShare {
        val s = session ?: throw IllegalStateException("未连接到共享")
        if (!s.connection.isConnected) {
            throw IllegalStateException("连接已断开")
        }
        return s.share
    }

    companion object {
        /**
         * 全局共享实例，跨 Activity 复用连接
         */
        val shared = SmbConnectionManager()
    }
}

/**
 * 内部 SMB 会话，持有完整的连接链
 */
private class SmbSession(
    val client: SMBClient,
    val connection: Connection,
    val session: Session,
    val share: DiskShare,
    val host: String,
    val port: Int,
    val shareName: String
) {
    fun close() {
        try { share.close() } catch (_: Exception) {}
        try { session.close() } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
        try { client.close() } catch (_: Exception) {}
    }
}

/**
 * SMB 文件模型
 */
data class SmbFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
) {
    fun getExtension(): String {
        return if (!isDirectory) {
            name.substringAfterLast('.', "").lowercase()
        } else {
            ""
        }
    }

    fun isApk(): Boolean = getExtension() == "apk"

    fun isImage(): Boolean = getExtension() in IMAGE_EXTENSIONS

    /** 是否是 BitmapFactory 可解码的图片格式（用于缩略图和查看器） */
    fun isDecodableImage(): Boolean = getExtension() in DECODABLE_IMAGE_EXTENSIONS

    fun isMedia(): Boolean = getExtension() in MEDIA_EXTENSIONS

    fun formatSize(): String {
        if (isDirectory) return "文件夹"

        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = size.toDouble()
        var unitIndex = 0

        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }

        return if (unitIndex == 0) {
            "${size.toLong()} ${units[unitIndex]}"
        } else {
            String.format("%.2f %s", size, units[unitIndex])
        }
    }

    companion object {
        /** BitmapFactory 可解码的格式（用于缩略图生成和全屏查看） */
        private val DECODABLE_IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "bmp", "webp"
        )

        /** 所有图片格式（含 TIFF/HEIC/RAW 等不可解码的格式） */
        private val IMAGE_EXTENSIONS = DECODABLE_IMAGE_EXTENSIONS + setOf(
            // TIFF
            "tif", "tiff",
            // HEIC/HEIF（iPhone 照片）
            "heic", "heif",
            // RAW（相机原始文件）
            "raw", "cr2", "cr3",       // Canon
            "nef", "nrw",               // Nikon
            "arw", "srf", "sr2",        // Sony
            "dng",                      // Adobe Digital Negative
            "orf",                      // Olympus
            "rw2",                      // Panasonic
            "pef",                      // Pentax
            "raf",                      // Fujifilm
            "3fr",                      // Hasselblad
        )

        private val MEDIA_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "mp3", "flac", "wav")
    }
}
