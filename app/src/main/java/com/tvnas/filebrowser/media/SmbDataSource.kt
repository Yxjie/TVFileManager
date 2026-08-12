package com.tvnas.filebrowser.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.tvnas.filebrowser.smb.SmbConnectionManager
import com.tvnas.filebrowser.smb.SmbStreamHandle
import com.tencent.bugly.crashreport.BuglyLog
import com.tencent.bugly.crashreport.CrashReport
import java.io.IOException

/**
 * 自定义 SMB DataSource — ExoPlayer 通过此类直接从 SMB 共享流式读取视频
 *
 * 核心原理：
 * - 继承 BaseDataSource，实现 open/read/close 三个方法
 * - 通过 SmbStreamHandle 使用 smbj 的 File.read(buffer, fileOffset) 随机访问读取
 * - seek 操作只需改变 fileOffset 参数，无需关闭/重新打开文件句柄
 * - 每个 DataSource 实例持有独立的文件句柄，与 BrowserActivity 的文件列表操作互不干扰
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SmbDataSource : BaseDataSource(true) {

    private var streamHandle: SmbStreamHandle? = null
    private var currentOffset: Long = 0L
    private var bytesRemaining: Long = 0L
    private var openedDataSpec: DataSpec? = null

    /** 是否已调用 transferStarted —— close() 中据此成对调用 transferEnded() */
    private var transferHasStarted = false

    override fun open(dataSpec: DataSpec): Long {
        openedDataSpec = dataSpec
        val uri = dataSpec.uri

        // 通知监听器传输开始初始化（约定：在任何阻塞式 I/O 之前调用）
        transferInitializing(dataSpec)

        // 从 URI 提取 SMB 远程路径
        // URI 格式: smb://server/share/path/to/file
        // uri.path 返回: /share/path/to/file
        // smbj openFile 需要不含 share 名的路径: path\to\file
        val fullPath = uri.path?.removePrefix("/")
            ?: throw IOException("无法解析 SMB 路径: $uri")
        val remotePath = fullPath.substringAfter("/", "").replace("/", "\\")
        if (remotePath.isEmpty()) {
            throw IOException("无法解析 SMB 文件路径: $uri")
        }

        try {
            // 通过共享连接打开独立的文件句柄
            streamHandle = SmbConnectionManager.shared.openFileForStreaming(remotePath)
        } catch (e: Exception) {
            if (isCausedByInterruption(e)) {
                // 播放器在 open 阶段取消了加载（同 read() 中的说明），正常取消，不上报
                BuglyLog.i("SmbDataSource", "打开文件被取消（播放器中断加载线程）: $remotePath")
                throw IOException("打开已取消", e)
            }
            BuglyLog.e("SmbDataSource", "无法打开 SMB 文件: $remotePath, error=${e.message}")
            CrashReport.postCatchedException(e)
            throw IOException("无法打开 SMB 文件: ${e.message}", e)
        }

        val handle = streamHandle!!
        currentOffset = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            handle.fileSize - dataSpec.position
        }

        if (currentOffset >= handle.fileSize) {
            throw IOException("请求的位置超出文件范围")
        }

        // 通知监听器传输已正式开始。
        // 关键修复：BaseDataSource 只在此处记录 dataSpec 并回调
        // DefaultBandwidthMeter.onTransferStart；此前缺少该调用，导致 read() 中
        // bytesTransferred() 把 null DataSpec 传给 BandwidthMeter，
        // 触发 DefaultBandwidthMeter.isTransferAtFullNetworkSpeed 的
        // NullPointerException（Bugly 崩溃：DataSpec.isFlagSet on null）。
        transferStarted(dataSpec)
        transferHasStarted = true
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining <= 0) return C.RESULT_END_OF_INPUT
        if (length == 0) return 0

        val handle = streamHandle ?: throw IOException("数据源未打开")
        val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()

        val bytesRead = try {
            handle.read(buffer, currentOffset, offset, bytesToRead)
        } catch (e: Exception) {
            if (isCausedByInterruption(e)) {
                // ExoPlayer 在 seek / 切换视频 / 退出播放器时会取消正在运行的加载任务：
                // Loader.LoadTask.cancel() 中断加载线程，smbj 的阻塞等待（Promise.tryRetrieve）
                // 感知中断后抛出 InterruptedException（被逐层包装为 SMBRuntimeException）。
                // 这是正常的取消流程（media3 Loadable 契约允许 load() 以 IOException 退出），
                // 不是故障 —— 不上报 Bugly。Loader 收到异常后走 onLoadCanceled 分支，
                // 不会触发重试或播放错误。
                BuglyLog.i("SmbDataSource", "读取被取消（播放器中断加载线程）: offset=$currentOffset")
                throw IOException("读取已取消", e)
            }
            if (e is com.hierynomus.mssmb2.SMBApiException) {
                BuglyLog.e("SmbDataSource", "SMB 连接中断: offset=$currentOffset, bytesToRead=$bytesToRead, error=${e.message}")
                CrashReport.postCatchedException(e)
                throw IOException("连接中断，请检查网络", e)
            }
            BuglyLog.e("SmbDataSource", "SMB 读取失败: offset=$currentOffset, bytesToRead=$bytesToRead, error=${e.message}")
            CrashReport.postCatchedException(e)
            throw IOException("SMB 读取失败: ${e.message}", e)
        }

        if (bytesRead == 0) return C.RESULT_END_OF_INPUT

        currentOffset += bytesRead
        bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = openedDataSpec?.uri

    /**
     * 判断异常是否由当前线程被中断引起
     *
     * ExoPlayer 取消加载任务时会中断加载线程（见 read()/open() 中的说明）。
     * InterruptedException 抛出后线程的中断标志即被清除，因此不能只检查
     * Thread.isInterrupted，必须沿异常链查找（smbj 的包装层次：
     * SMBRuntimeException → TransportException → ExecutionException
     * → SMBRuntimeException → InterruptedException）。
     */
    private fun isCausedByInterruption(e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth < 16) {
            if (cause is InterruptedException) return true
            cause = cause.cause
            depth++
        }
        return Thread.currentThread().isInterrupted
    }

    override fun close() {
        // 与 transferStarted 成对：通知监听器传输结束，
        // 让 DefaultBandwidthMeter 正确结算带宽采样（streamCount 配对）
        if (transferHasStarted) {
            transferEnded()
            transferHasStarted = false
        }
        openedDataSpec = null
        streamHandle?.close()
        streamHandle = null
    }
}
