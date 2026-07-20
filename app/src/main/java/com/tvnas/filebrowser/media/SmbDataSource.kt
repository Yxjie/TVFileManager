package com.tvnas.filebrowser.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.tvnas.filebrowser.smb.SmbConnectionManager
import com.tvnas.filebrowser.smb.SmbStreamHandle
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

    override fun open(dataSpec: DataSpec): Long {
        openedDataSpec = dataSpec
        val uri = dataSpec.uri

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

        transferInitializing(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining <= 0) return C.RESULT_END_OF_INPUT
        if (length == 0) return 0

        val handle = streamHandle ?: throw IOException("数据源未打开")
        val bytesToRead = minOf(length.toLong(), bytesRemaining).toInt()

        val bytesRead = try {
            handle.read(buffer, currentOffset, offset, bytesToRead)
        } catch (e: com.hierynomus.mssmb2.SMBApiException) {
            throw IOException("连接中断，请检查网络", e)
        } catch (e: Exception) {
            throw IOException("SMB 读取失败: ${e.message}", e)
        }

        if (bytesRead == 0) return C.RESULT_END_OF_INPUT

        currentOffset += bytesRead
        bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = openedDataSpec?.uri

    override fun close() {
        openedDataSpec = null
        streamHandle?.close()
        streamHandle = null
    }
}
