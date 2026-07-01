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
import com.hierynomus.smbj.share.File
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB 连接管理器
 */
class SmbConnectionManager {
    
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    
    /**
     * 连接到 SMB 服务器
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
        // 创建 SMB 配置
        val config = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()
        
        // 创建客户端
        client = SMBClient(config)
        
        // 建立连接
        connection = client!!.connect(host, port)
        
        // 认证
        val authContext = if (username.isNullOrBlank() && password.isNullOrBlank()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(
                username ?: "",
                password?.toCharArray() ?: charArrayOf(),
                domain ?: ""
            )
        }
        
        session = connection!!.authenticate(authContext)
        
        // 连接共享
        share = session!!.connectShare(shareName) as DiskShare
    }
    
    /**
     * 列出目录内容
     */
    @Throws(Exception::class)
    fun listFiles(path: String = ""): List<SmbFile> {
        val share = this.share ?: throw IllegalStateException("未连接到共享")
        
        val files = mutableListOf<SmbFile>()
        
        val entries = if (path.isEmpty()) {
            share.list("")
        } else {
            share.list(path)
        }
        
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
        
        // 排序：目录在前，然后按名称
        return files.sortedWith(compareByDescending<SmbFile> { it.isDirectory }.thenBy { it.name.lowercase() })
    }
    
    /**
     * 下载文件
     */
    @Throws(Exception::class)
    fun downloadFile(remotePath: String, outputStream: OutputStream, progressCallback: ((Long, Long) -> Unit)? = null) {
        val share = this.share ?: throw IllegalStateException("未连接到共享")
        
        val file = share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        
        val inputStream: InputStream = file.inputStream
        val totalSize: Long =  file.fileInformation.standardInformation.endOfFile

        val buffer = ByteArray(8192)
        var bytesRead: Long = 0
        var read: Int
        
        while (inputStream.read(buffer).also { read = it } != -1) {
            outputStream.write(buffer, 0, read)
            bytesRead += read
            progressCallback?.invoke(bytesRead, totalSize)
        }
        
        inputStream.close()
        file.close()
    }
    
    /**
     * 获取文件大小
     */
    @Throws(Exception::class)
    fun getFileSize(remotePath: String): Long {
        val share = this.share ?: throw IllegalStateException("未连接到共享")
        
        val file = share.openFile(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        
        val size: Long =  file.fileInformation.standardInformation.endOfFile
        file.close()
        
        return size
    }
    
    /**
     * 断开连接
     */
    fun disconnect() {
        try {
            share?.close()
            session?.close()
            connection?.close()
            client?.close()
        } catch (e: Exception) {
            // 忽略关闭异常
        } finally {
            share = null
            session = null
            connection = null
            client = null
        }
    }
    
    /**
     * 检查是否已连接
     */
    fun isConnected(): Boolean {
        return share != null && connection?.isConnected == true
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
    /**
     * 获取文件扩展名
     */
    fun getExtension(): String {
        return if (!isDirectory) {
            name.substringAfterLast('.', "").lowercase()
        } else {
            ""
        }
    }
    
    /**
     * 是否是 APK 文件
     */
    fun isApk(): Boolean {
        return getExtension() == "apk"
    }
    
    /**
     * 是否是图片文件
     */
    fun isImage(): Boolean {
        val imageExts = listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
        return getExtension() in imageExts
    }
    
    /**
     * 是否是媒体文件
     */
    fun isMedia(): Boolean {
        val mediaExts = listOf("mp4", "mkv", "avi", "mov", "mp3", "flac", "wav")
        return getExtension() in mediaExts
    }
    
    /**
     * 格式化文件大小
     */
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
}
