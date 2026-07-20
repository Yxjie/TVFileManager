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
    /**
     * 随机访问读取
     *
     * @param buffer 目标缓冲区
     * @param fileOffset 文件中的读取位置
     * @param bufferOffset 缓冲区中的写入起始位置
     * @param length 要读取的字节数
     * @return 实际读取的字节数，0 表示已到达文件末尾
     */
    fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
        if (fileOffset >= fileSize) return 0
        val bytesToRead = minOf(length.toLong(), fileSize - fileOffset).toInt()
        return file.read(buffer, fileOffset, bufferOffset, bytesToRead)
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
