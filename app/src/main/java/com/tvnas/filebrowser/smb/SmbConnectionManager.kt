package com.tvnas.filebrowser.smb

import android.os.SystemClock
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
import com.tencent.bugly.crashreport.BuglyLog
import com.tencent.bugly.crashreport.CrashReport
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
 * - isConnected / isConnectedTo：无锁（仅读取 @Volatile 字段及不可变会话字段）
 *
 * 断线自动重连：
 * 连接可能被外部因素随时中断（NAS 空闲超时/重启、电视休眠、网络切换等）。
 * 所有文件操作都经过 ensureShare()：发现连接已断开时，用最近一次成功连接
 * 的参数透明重连后再返回 share，调用方无感知。这修复了 Bugly #2004
 * （"连接已断开"）背后真正的问题：断线后没有任何恢复路径，用户的每次
 * 刷新/导航都失败，只能退出页面重进。
 *
 * 重连失败冷却：
 * 重连失败后的 [RECONNECT_COOLDOWN_MS] 内，后续操作直接快速失败、不再重连。
 * 否则服务器真正不可达时，ThumbnailLoader 的并发下载与 ExoPlayer 的自动重试
 * 会各自触发完整重连（每次最长 30s 超时），串联起来长时间卡死。
 *
 * disconnect() 是主动断开，会清除保存的参数，不会触发自动重连。
 */
class SmbConnectionManager {

    @Volatile
    private var session: SmbSession? = null

    /**
     * 最近一次成功连接的参数，用于断线后自动重连（见 ensureShare）。
     * connect 成功后记录；disconnect（主动断开）时清除。
     */
    @Volatile
    private var lastParams: ConnectionParams? = null

    /**
     * 最近一次重连失败的时刻（elapsedRealtime），0 表示没有失败记录。
     * 仅在重连锁（reconnectAndGetShare）内读写。
     */
    private var lastReconnectFailureAt = 0L

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
                // 记住本次成功连接的参数，断线后由 ensureShare 透明自动重连
                this.lastParams = ConnectionParams(host, port, shareName, username, password, domain)
            } catch (e: Exception) {
                runCatching { connection.close() }
                throw e
            }
        } catch (e: Exception) {
            runCatching { client.close() }
            // 上报 SMB 连接失败到 Bugly（不含密码）
            BuglyLog.e("SmbConnection", "连接失败: host=$host, port=$port, share=$shareName, user=${username ?: "guest"}, error=${e.message}")
            CrashReport.postCatchedException(e)
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
            // 过滤隐藏的系统元数据文件：
            // - "._xxx.jpg"：macOS AppleDouble 资源分支文件（macOS 客户端访问 SMB 共享时产生），
            //   扩展名与真实媒体相同但内容是元数据，预览/播放必然失败（Bugly 上
            //   "图片预览失败: 解码返回 null" 的根源）
            // - ".DS_Store" / ".apdisk" / ".Spotlight-V100" 等：macOS Finder 元数据
            // - "Thumbs.db" / "desktop.ini"：Windows 系统文件
            // 这些文件对电视用户没有价值，且会导致图片查看器/播放器误报解码错误
            if (isSystemMetadataFile(fileName)) continue

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
     * 打开文件用于流式读取（用于 ExoPlayer 等媒体播放器）
     *
     * 返回 SmbStreamHandle，调用者负责在使用完毕后调用 handle.close() 释放资源。
     * 每个 handle 持有独立的文件句柄，可并发使用多个 handle。
     */
    @Throws(Exception::class)
    fun openFileForStreaming(remotePath: String): SmbStreamHandle {
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
        val fileSize = smbFile.fileInformation.standardInformation.endOfFile
        return SmbStreamHandle(smbFile, fileSize)
    }

    /**
     * 断开连接并释放所有资源
     * 安全调用：多次调用不会抛异常
     */
    @Synchronized
    fun disconnect() {
        session?.close()
        session = null
        // 主动断开：清除重连参数，之后的操作不应把连接"复活"
        lastParams = null
        lastReconnectFailureAt = 0L
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
     * 不加 @Synchronized：只读取 volatile 的 session 引用及不可变的会话字段。
     * 重连期间 this 锁可能被网络 IO 持有数十秒，而本方法在主线程被调用
     * （BrowserActivity.connectAndLoadFiles），若等锁会造成 ANR；
     * 读到过期状态的最坏后果只是多走一次 connect，ensureShare 仍会兜底
     */
    fun isConnectedTo(host: String, port: Int, shareName: String): Boolean {
        val s = session ?: return false
        if (!s.connection.isConnected) return false
        return s.host == host && s.port == port && s.shareName.equals(shareName, ignoreCase = true)
    }

    /**
     * 获取当前连接的 share；连接已断开时用保存的参数透明重连（见类注释）
     *
     * 快速路径无锁；重连路径加 this 锁并做双重检查——第一个发现断线的
     * 线程执行重连，其余线程拿到锁后直接复用新连接，不会重复连接。
     */
    private fun ensureShare(): DiskShare {
        val s = session
        if (s != null && s.connection.isConnected) {
            return s.share
        }
        return reconnectAndGetShare()
    }

    /**
     * 断线后自动重连（仅在 ensureShare 快速路径未命中时进入）
     *
     * 重连失败时抛出 connect 的原始异常（由调用方提示用户）；
     * 冷却期内不重连，直接抛异常快速失败。
     */
    @Synchronized
    private fun reconnectAndGetShare(): DiskShare {
        // 双重检查：等锁期间可能已有其他线程完成了重连
        val current = session
        if (current != null && current.connection.isConnected) {
            return current.share
        }

        val params = lastParams
            ?: throw IllegalStateException("未连接到共享")

        // 冷却期：刚重连失败过就不重复尝试。服务器真正不可达时，
        // ThumbnailLoader 的并发下载、ExoPlayer 的自动重试都会反复走到这里，
        // 没有冷却会排队做多次完整重连（每次最长 30s 超时）
        if (lastReconnectFailureAt != 0L) {
            val elapsed = SystemClock.elapsedRealtime() - lastReconnectFailureAt
            if (elapsed < RECONNECT_COOLDOWN_MS) {
                BuglyLog.i("SmbConnection", "连接已断开且处于重连冷却期（距上次失败 ${elapsed}ms），快速失败")
                throw IllegalStateException("连接已断开")
            }
        }

        BuglyLog.i(
            "SmbConnection",
            "连接已断开，自动重连: host=${params.host}, port=${params.port}, share=${params.shareName}"
        )
        try {
            connect(params.host, params.port, params.shareName, params.username, params.password, params.domain)
        } catch (e: Exception) {
            lastReconnectFailureAt = SystemClock.elapsedRealtime()
            throw e
        }
        lastReconnectFailureAt = 0L
        return session?.share ?: throw IllegalStateException("重连后仍无可用连接")
    }

    /**
     * 判断是否为应在列表中隐藏的系统/隐藏元数据文件
     *
     * 所有以 "." 开头的条目（含 macOS 的 "._" AppleDouble 资源分支文件、
     * .DS_Store 等）在桌面系统上默认就是隐藏的，电视端同样不展示；
     * 另过滤 Windows 的 Thumbs.db / desktop.ini。
     */
    private fun isSystemMetadataFile(name: String): Boolean {
        return name.startsWith(".") ||
            name.equals("Thumbs.db", ignoreCase = true) ||
            name.equals("desktop.ini", ignoreCase = true)
    }

    companion object {
        /**
         * 全局共享实例，跨 Activity 复用连接
         */
        val shared = SmbConnectionManager()

        /**
         * 重连失败后的冷却时间：窗口内的操作直接快速失败、不再重连，
         * 窗口过后的下一个操作会再次尝试（见 reconnectAndGetShare）
         */
        private const val RECONNECT_COOLDOWN_MS = 10_000L
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
 * 一次成功连接的参数快照，用于断线后自动重连
 */
private data class ConnectionParams(
    val host: String,
    val port: Int,
    val shareName: String,
    val username: String?,
    val password: String?,
    val domain: String?
)

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

    fun isVideo(): Boolean = getExtension() in VIDEO_EXTENSIONS

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

        val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v",          // MP4
            "mkv",                 // Matroska
            "avi",                 // AVI
            "mov",                 // QuickTime
            "wmv",                 // Windows Media
            "flv",                 // Flash Video
            "webm",                // WebM
            "mpg", "mpeg",         // MPEG
            "ts",                  // MPEG-TS
            "3gp",                 // 3GPP
            "vob"                  // DVD Video
        )
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav")
        private val MEDIA_EXTENSIONS = VIDEO_EXTENSIONS + AUDIO_EXTENSIONS
    }
}
