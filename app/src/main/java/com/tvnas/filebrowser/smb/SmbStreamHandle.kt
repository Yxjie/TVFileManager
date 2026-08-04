package com.tvnas.filebrowser.smb

import com.hierynomus.smbj.share.File

/**
 * SMB 流式读取句柄
 *
 * 封装 smbj 的 File 对象，提供随机访问读取能力。
 * 用于 ExoPlayer 等媒体播放器的流式读取场景。
 *
 * 线程安全：单个实例仅限单线程使用（ExoPlayer 的 DataSource 是单线程调用）
 */
class SmbStreamHandle(
    private val file: File,
    val fileSize: Long
) {
    companion object {
        /**
         * 单次 SMB2 READ 请求的最大字节数
         *
         * NAS 服务器的 MaxReadSize 通常为 64KB（SMB2 默认值）。
         * 如果请求超过此限制，服务器会返回 STATUS_INVALID_PARAMETER 导致读取失败。
         * ExoPlayer 播放 MP4 时可能一次请求读取整个 moov atom（100KB~数MB），
         * 必须在此分块以确保每次 SMB READ 请求不超过服务器限制。
         */
        private const val MAX_SMB_READ_SIZE = 64 * 1024  // 64KB
    }

    /**
     * 随机访问读取（自动分块）
     *
     * 将大块读取请求拆分为不超过 MAX_SMB_READ_SIZE 的小块，
     * 逐块发送 SMB2 READ 请求，避免超出 NAS 服务器的 MaxReadSize 限制。
     *
     * @param buffer 目标缓冲区
     * @param fileOffset 文件中的读取位置
     * @param bufferOffset 缓冲区中的写入起始位置
     * @param length 要读取的字节数
     * @return 实际读取的字节数，0 表示已到达文件末尾
     */
    fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
        if (fileOffset >= fileSize) return 0
        val totalBytesToRead = minOf(length.toLong(), fileSize - fileOffset).toInt()
        var totalRead = 0
        var currentFileOffset = fileOffset
        var currentBufferOffset = bufferOffset

        while (totalRead < totalBytesToRead) {
            val chunkSize = minOf(totalBytesToRead - totalRead, MAX_SMB_READ_SIZE)
            val bytesRead = file.read(buffer, currentFileOffset, currentBufferOffset, chunkSize)
            if (bytesRead <= 0) break
            totalRead += bytesRead
            currentFileOffset += bytesRead
            currentBufferOffset += bytesRead
        }
        return totalRead
    }

    /**
     * 关闭文件句柄，释放 SMB 资源
     */
    fun close() {
        try {
            file.close()
        } catch (_: Exception) {
            // 忽略关闭异常
        }
    }
}
