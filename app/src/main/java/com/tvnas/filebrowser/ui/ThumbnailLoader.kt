package com.tvnas.filebrowser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.tvnas.filebrowser.smb.SmbConnectionManager
import com.tvnas.filebrowser.smb.SmbFile
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * SMB 图片缩略图加载器
 * - 内存缓存 (LruCache) + 磁盘缓存
 * - 通过 SMB 下载原图到磁盘缓存，再解码为小缩略图
 * - Semaphore 允许多个并发下载（最多 3 个）
 * - MD5 哈希避免缓存文件名碰撞
 */
class ThumbnailLoader(
    private val context: Context,
    private val smbManager: SmbConnectionManager
) {
    private val memoryCache = LruCache<String, Bitmap>(60)
    private val diskCacheDir = File(context.cacheDir, "thumbnails").apply { mkdirs() }
    private val semaphore = Semaphore(3)  // 允许最多 3 个并发下载
    private val maxThumbnailSize = 128 // pixels

    /**
     * 加载缩略图（挂起函数，需在协程中调用）
     * 优先内存缓存 → 磁盘缓存 → SMB 下载
     */
    suspend fun loadThumbnail(file: SmbFile): Bitmap? {
        val key = file.path

        // 1. 内存缓存
        memoryCache.get(key)?.let { return it }

        // 2. 磁盘缓存（不阻塞 semaphore）
        val cacheFile = getCacheFile(file)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            val bitmap = decodeThumbnail(cacheFile)
            bitmap?.let { memoryCache.put(key, it) }
            return bitmap
        }

        // 3. 下载并缓存（Semaphore 限制并发数为 3）
        return semaphore.withPermit {
            // 双重检查：可能在等待 semaphore 时已被其他协程缓存
            if (cacheFile.exists() && cacheFile.length() > 0) {
                val bitmap = decodeThumbnail(cacheFile)
                bitmap?.let { memoryCache.put(key, it) }
                return@withPermit bitmap
            }

            try {
                // 下载到临时文件，完成后重命名（防止写入中断导致损坏）
                val tempFile = File(cacheFile.parent, "${cacheFile.name}.tmp")
                FileOutputStream(tempFile).use { out ->
                    smbManager.downloadFile(file.path, out)
                }
                tempFile.renameTo(cacheFile)

                val bitmap = decodeThumbnail(cacheFile)
                bitmap?.let { memoryCache.put(key, it) }
                bitmap
            } catch (e: Exception) {
                // 下载失败，清理临时文件和可能损坏的缓存
                runCatching { File(cacheFile.parent, "${cacheFile.name}.tmp").delete() }
                runCatching { cacheFile.delete() }
                null
            }
        }
    }

    /**
     * 清空内存缓存
     */
    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    /**
     * 清空磁盘缓存
     */
    fun clearDiskCache() {
        diskCacheDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * 使用 MD5 哈希生成缓存文件名，避免碰撞
     */
    private fun getCacheFile(file: SmbFile): File {
        val md5 = MessageDigest.getInstance("MD5")
            .digest(file.path.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val ext = file.getExtension()
        return File(diskCacheDir, "$md5.$ext")
    }

    private fun decodeThumbnail(file: File): Bitmap? {
        return try {
            // 先获取原始尺寸
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)

            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

            // 计算采样率
            val sampleSize = calculateInSampleSize(
                boundsOptions, maxThumbnailSize, maxThumbnailSize
            )

            // 解码缩略图
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight &&
                (halfWidth / inSampleSize) >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
