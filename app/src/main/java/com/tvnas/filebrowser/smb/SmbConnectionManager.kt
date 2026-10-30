package com.tvnas.filebrowser.smb

import android.os.Build
import android.os.Parcelable
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
import kotlinx.parcelize.Parcelize
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB 连接管理器
 *
 * 线程安全策略（细粒度锁）：
 * - connect / disconnect：修改连接状态，短期操作，只持 [stateLock] 的短临界区
 * - listFiles：无锁（smbj DiskShare.list 内部独立打开/关闭文件，线程安全）
 * - downloadFile / getFileSize / openFileForStreaming / openInputStream：
 *   在 [stateLock] 短临界区内 openFile（获取句柄），数据传输阶段不加锁，
 *   允许多个下载并发执行
 * - isConnected / isConnectedTo：无锁（仅读取 @Volatile 字段及不可变会话字段）
 *
 * 断线自动重连：
 * 连接可能被外部因素随时中断（NAS 空闲超时/重启、电视休眠、网络切换等）。
 * 所有文件操作都经过 ensureShare()：发现连接已断开时，用最近一次成功连接
 * 的参数透明重连后再返回 share，调用方无感知。这修复了 Bugly #2004
 * （"连接已断开"）背后真正的问题：断线后没有任何恢复路径，用户的每次
 * 刷新/导航都失败，只能退出页面重进。
 *
 * 重连不与全局锁共享：
 * 完整重连的 TCP 握手 + SMB 认证在电视上可能耗时长达 30s。早期版本在
 * reconnectAndGetShare 上加 @Synchronized(this)，某个下载/播放线程触发重连时
 * 会持有全局锁数十秒，导致其他操作（即使是连接仍健康的操作）也被阻塞。
 * 现在网络 IO 阶段只持独立的 [reconnectLock] 串行化并发重连，绝不持有
 * [stateLock]；只有替换会话/更新状态的极短临界区才用 [stateLock]。
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
     * 在 [stateLock] 内写，读取用 @Volatile。
     */
    @Volatile
    private var lastParams: ConnectionParams? = null

    /**
     * 最近一次重连失败的时刻（elapsedRealtime），0 表示没有失败记录。
     * 仅在 [stateLock] 临界区内读写。
     */
    private var lastReconnectFailureAt = 0L

    /**
     * 串行化完整重连的网络 IO。重连本身是长耗时（可达 30s），
     * 必须与 [stateLock] 分离，避免长阻塞拖垮其他轻量操作。
     */
    private val reconnectLock = Any()

    /**
     * 保护 session/lastParams 的读改写，以及 openFile 与 disconnect 的互斥。
     * 只在极短临界区内持锁（读字段、替换会话、获取文件句柄），
     * 任何网络 IO 不得在此期间进行。
     */
    private val stateLock = Any()

    /**
     * 连接到 SMB 服务器
     * 如果已有连接会先断开
     *
     * 网络 IO 阶段不持 [stateLock]（可能耗时数十秒），仅在替换会话的
     * 极短临界区内持 [stateLock]，保证与并发文件操作/断开的正确互斥。
     * 旧会话的关闭在锁外执行：smbj 的 close() 是网络 IO（Tree Disconnect/
     * Logoff/TCP 关闭），网络异常时可阻塞至 soTimeout，若放在 [stateLock] 内
     * 会拖住所有 openFileForOperation。
     */
    @Throws(Exception::class)
    fun connect(
        host: String,
        port: Int = 445,
        shareName: String,
        username: String?,
        password: String?,
        domain: String? = null
    ) {
        val newSession = establish(host, port, shareName, username, password, domain)
        var oldSession: SmbSession? = null
        synchronized(stateLock) {
            oldSession = session
            session = newSession
            lastParams = ConnectionParams(host, port, shareName, username, password, domain)
            lastReconnectFailureAt = 0L
        }
        runCatching { oldSession?.close() }
    }

    /**
     * 建立底层 SMB 连接并返回会话（不做任何加锁，允许长时间的握手/认证）。
     * 成功返回新 [SmbSession]；失败时关闭资源并抛出异常。
     */
    @Throws(Exception::class)
    private fun establish(
        host: String,
        port: Int,
        shareName: String,
        username: String?,
        password: String?,
        domain: String?
    ): SmbSession {
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

                return SmbSession(client, connection, smbSession, share, host, port, shareName)
            } catch (e: Exception) {
                runCatching { connection.close() }
                throw e
            }
        } catch (e: Exception) {
            runCatching { client.close() }
            // 上报 SMB 连接失败到 Bugly（不含密码）。
            // 连接失败属于业务失败（网络/凭据问题），不是代码崩溃，
            // 用 BuglyLog.e 记录而非 postCatchedException，避免污染 Bugly 崩溃统计。
            BuglyLog.e("SmbConnection", "连接失败: host=$host, port=$port, share=$shareName, user=${username ?: "guest"}, error=${e.message}")
            throw e
        }
    }

    /**
     * 列出目录内容
     * 不加锁：DiskShare 支持并发文件操作
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
     * 仅在 openFile 时持 [stateLock] 短临界区（毫秒级），数据传输阶段不加锁，
     * 允许多个下载并发
     */
    @Throws(Exception::class)
    fun downloadFile(
        remotePath: String,
        outputStream: OutputStream,
        progressCallback: ((Long, Long) -> Unit)? = null
    ) {
        // 仅在获取句柄时持锁（快速操作）；ensureShare 的重连期只持 reconnectLock
        val smbFile = openFileForOperation(remotePath)
        // 数据传输阶段不加锁，允许并发
        try {
            val totalSize = smbFile.fileInformation.standardInformation.endOfFile
            val inputStream = smbFile.inputStream
            try {
                // 64KB 缓冲，与 SmbStreamHandle.MAX_SMB_READ_SIZE 对齐，提升下载吞吐
                val buffer = ByteArray(64 * 1024)
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
     * 获取文件大小（仅在 openFile 时持 [stateLock] 短临界区）
     */
    @Throws(Exception::class)
    fun getFileSize(remotePath: String): Long {
        val smbFile = openFileForOperation(remotePath)
        try {
            return smbFile.fileInformation.standardInformation.endOfFile
        } finally {
            runCatching { smbFile.close() }
        }
    }

    /**
     * 删除 SMB 服务器上的文件或文件夹
     * 仅调用 ensureShare() 获取句柄，网络 IO（folderExists/rmdir/rm）在锁外执行，
     * 避免大目录递归删除长时间阻塞全局锁引发 ANR
     */
    @Throws(Exception::class)
    fun delete(remotePath: String) {
        val share = ensureShare()
        if (share.folderExists(remotePath)) {
            share.rmdir(remotePath, true)
        } else {
            share.rm(remotePath)
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
        val smbFile = openFileForOperation(remotePath)
        val fileSize = smbFile.fileInformation.standardInformation.endOfFile
        return SmbStreamHandle(smbFile, fileSize)
    }

    /**
     * 打开文件并返回顺序输入流（用于图片查看器流式解码，避免整图落临时文件）
     *
     * 返回的 InputStream 关闭时会同时释放底层 SMB 文件句柄。
     * 仅在 openFile 时持锁；数据传输阶段不加锁，与 downloadFile 一致。
     */
    @Throws(Exception::class)
    fun openInputStream(remotePath: String): java.io.InputStream {
        val smbFile = openFileForOperation(remotePath)
        val inner = smbFile.inputStream
        return object : java.io.InputStream() {
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, length: Int): Int = inner.read(b, off, length)
            override fun close() {
                try { inner.close() } finally { runCatching { smbFile.close() } }
            }
        }
    }

    /**
     * 打开视频文件并返回随机访问句柄（用于 MediaMetadataRetriever 抽帧生成缩略图）
     *
     * 返回的 MediaDataSource 关闭时同时释放底层 SMB 文件句柄。
     * 与 openFileForStreaming/openInputStream 一致：仅在 openFile 时持 [stateLock]
     * 短临界区，数据传输阶段（readAt 的网络 IO）不加锁。
     */
    @Throws(Exception::class)
    fun openMediaDataSource(remotePath: String): android.media.MediaDataSource {
        val smbFile = openFileForOperation(remotePath)
        val fileSize = smbFile.fileInformation.standardInformation.endOfFile
        return object : android.media.MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= fileSize) return -1
                return smbFile.read(buffer, position, offset, size)
            }
            override fun getSize(): Long = fileSize
            override fun close() {
                runCatching { smbFile.close() }
            }
        }
    }

    /**
     * 连接保证 + 获取文件句柄的统一入口。
     *
     * 1) 先调用 ensureShare()：若已断开会触发重连。重连的网络 IO 只持
     *    [reconnectLock] 串行化并发重连，不持 [stateLock]，因此长耗时期间
     *    其他轻量操作（如 isConnectedTo、仍健康的读操作）不会被阻塞。
     * 2) 再在 [stateLock] 短临界区内获取句柄，与 disconnect/会话替换互斥，
     *    避免在已关闭/被替换的 share 上 openFile。
     */
    @Throws(Exception::class)
    private fun openFileForOperation(remotePath: String): com.hierynomus.smbj.share.File {
        ensureShare()
        return synchronized(stateLock) {
            val s = session ?: throw IllegalStateException("未连接到共享")
            s.share.openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
        }
    }

    /**
     * 断开连接并释放所有资源
     * 安全调用：多次调用不会抛异常
     *
     * 与 connect 一致：stateLock 内只做状态替换，旧会话的 close（网络 IO）
     * 放到锁外执行，避免网络异常时长时间占用 stateLock。
     */
    fun disconnect() {
        var oldSession: SmbSession? = null
        synchronized(stateLock) {
            oldSession = session
            session = null
            // 主动断开：清除重连参数，之后的操作不应把连接"复活"
            lastParams = null
            lastReconnectFailureAt = 0L
        }
        runCatching { oldSession?.close() }
    }

    /**
     * 检查是否已连接（快速检查，不涉及网络 IO）
     * 不加锁：只读取 volatile 字段
     */
    fun isConnected(): Boolean {
        val s = session ?: return false
        return s.connection.isConnected
    }

    /**
     * 检查是否已连接到指定的 SMB 服务器
     * 用于避免不必要的重连（TCP 握手 + SMB 认证在电视上耗时数秒）
     * 不加锁：只读取 volatile 的 session 引用及不可变的会话字段。
     * 重连期间网络 IO 只持 [reconnectLock]，本方法不参与，故不会阻塞；
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
     * 快速路径无锁；重连路径持 [reconnectLock] 并做双重检查——第一个发现断线的
     * 线程执行重连，其余线程拿到 [reconnectLock] 后直接复用新连接，不会重复连接。
     * 重连的网络 IO 不持 [stateLock]，避免长阻塞拖垮其他操作。
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
     * 持 [reconnectLock] 串行化并发重连；网络 IO（establish）期间不持 [stateLock]，
     * 只在替换会话的极短临界区内持 [stateLock]。
     * 重连失败时抛出 connect 的原始异常（由调用方提示用户）；
     * 冷却期内不重连，直接抛异常快速失败。
     *
     * 失效重连防护：establish 最长耗时 30s，期间用户可能已通过 [connect] 切换到
     * 其他服务器（lastParams 被替换为新的 ConnectionParams 实例）。慢重连返回后
     * 不得覆盖/关闭新会话——替换会话前在 [stateLock] 内校验 [lastParams] 仍是
     * 发起重连时的参数（不可变对象，引用比较即可）；不一致则丢弃刚建立的会话，
     * 复用当前会话。
     */
    private fun reconnectAndGetShare(): DiskShare {
        return synchronized(reconnectLock) {
            // 双重检查：等锁期间可能已有其他线程完成了重连
            val current = session
            if (current != null && current.connection.isConnected) {
                return@synchronized current.share
            }

            val params = lastParams
                ?: throw IllegalStateException("未连接到共享")

            // 冷却期：刚重连失败过就不重复尝试。服务器真正不可达时，
            // ThumbnailLoader 的并发下载、ExoPlayer 的自动重试都会反复走到这里，
            // 没有冷却会排队做多次完整重连（每次最长 30s 超时）
            synchronized(stateLock) {
                if (lastReconnectFailureAt != 0L) {
                    val elapsed = SystemClock.elapsedRealtime() - lastReconnectFailureAt
                    if (elapsed < RECONNECT_COOLDOWN_MS) {
                        BuglyLog.i("SmbConnection", "连接已断开且处于重连冷却期（距上次失败 ${elapsed}ms），快速失败")
                        throw IllegalStateException("连接已断开")
                    }
                }
            }

            BuglyLog.i(
                "SmbConnection",
                "连接已断开，自动重连: host=${params.host}, port=${params.port}, share=${params.shareName}"
            )
            try {
                val newSession = establish(
                    params.host, params.port, params.shareName,
                    params.username, params.password, params.domain
                )
                var superseded = false
                var oldSession: SmbSession? = null
                synchronized(stateLock) {
                    // 失效重连防护：establish 期间 connect 可能已换到新服务器
                    //（lastParams 指向新的 ConnectionParams 实例）或 disconnect
                    // 已清空参数。此时本次重连已过时：不得覆盖/关闭新会话
                    if (lastParams !== params) {
                        superseded = true
                    } else {
                        oldSession = session
                        session = newSession
                        lastReconnectFailureAt = 0L
                    }
                }
                if (superseded) {
                    BuglyLog.i("SmbConnection", "重连完成前连接参数已被更换，丢弃本次过时的重连会话")
                    // 新建会话未被其他线程引用，在锁外安全关闭（网络 IO 不持 stateLock）
                    runCatching { newSession.close() }
                    val s = session ?: throw IllegalStateException("未连接到共享")
                    return@synchronized s.share
                }
                // 被替换的旧会话已断线且不再被引用，在锁外关闭（网络 IO 不持 stateLock）
                runCatching { oldSession?.close() }
                return@synchronized newSession.share
            } catch (e: Exception) {
                // 仅当参数未被更换时才记录失败冷却：establish 期间用户可能已
                // connect 到新服务器，本次失败属于旧服务器，不应让新连接背上
                // 10s 冷却期（否则切换后首个操作会被误快速失败）
                var supersededFailure = false
                synchronized(stateLock) {
                    if (lastParams === params) {
                        lastReconnectFailureAt = SystemClock.elapsedRealtime()
                    } else {
                        supersededFailure = true
                    }
                }
                if (supersededFailure) {
                    BuglyLog.i("SmbConnection", "重连失败前连接参数已被更换，冷却期不适用于新连接")
                }
                throw e
            }
        }
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
@Parcelize
data class SmbFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
) : Parcelable {
    fun getExtension(): String {
        return if (!isDirectory) {
            name.substringAfterLast('.', "").lowercase()
        } else {
            ""
        }
    }

    fun isApk(): Boolean = getExtension() == "apk"

    fun isImage(): Boolean = getExtension() in IMAGE_EXTENSIONS

    /** 是否是 BitmapFactory 可解码的图片格式（用于缩略图生成和全屏查看） */
    fun isDecodableImage(): Boolean = getExtension() in DECODABLE_IMAGE_EXTENSIONS

    /**
     * 是否值得走缩略图加载流程（FileAdapter 列表预览用）
     *
     * = 可解码格式 + HEIC/HEIF。HEIC 缩略图有两条路径：
     * - EXIF 内嵌缩略图（androidx ExifInterface 纯 Java 解析，全 API 版本可用）
     * - API 28+ 系统解码器直接解整图
     * TIFF/RAW 不在此列：老设备既不能解码，整图下载也拿不到缩略图，只会浪费流量。
     */
    fun isThumbnailCandidate(): Boolean =
        isDecodableImage() || getExtension() in HEIC_EXTENSIONS

    /** HEIC/HEIF（iPhone 照片） */
    fun isHeicImage(): Boolean = getExtension() in HEIC_EXTENSIONS

    /**
     * 全屏查看器可打开的图片（BrowserActivity 点击路由 + 查看器列表过滤用）
     *
     * = 可解码格式 + HEIC/HEIF（仅 API 28+，老系统无 HEIF 解码器，
     * BitmapFactory 解码 HEIC 必然返回 null，点了只会看到加载失败）。
     */
    fun isViewableImage(): Boolean =
        isDecodableImage() || (isHeicImage() && Build.VERSION.SDK_INT >= 28)

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

        /** HEIC/HEIF：EXIF 内嵌缩略图全 API 可读；整图解码需 API 28+ 系统 HEIF 解码器 */
        private val HEIC_EXTENSIONS = setOf("heic", "heif")

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
