package com.tvnas.filebrowser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.tvnas.filebrowser.smb.SmbConnectionManager
import com.tvnas.filebrowser.smb.SmbFile
import com.tvnas.filebrowser.util.BitmapSampler
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * SMB 图片缩略图加载器
 * - 内存缓存 (LruCache，按字节计容量) + 磁盘缓存（带总量上限的自动淘汰）
 * - 优先读取 EXIF 内嵌缩略图（仅下载文件前缀，省流量）；无内嵌缩略图时回退到整图下载解码
 * - Semaphore 允许多个并发下载（最多 3 个）
 * - MD5 哈希避免缓存文件名碰撞
 */
class ThumbnailLoader(
    private val context: Context,
    private val smbManager: SmbConnectionManager
) {
    // 内存缓存按字节计容量，能更真实反映内存占用（约 24MB）
    private val memoryCache = object : LruCache<String, Bitmap>(MEMORY_CACHE_MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val diskCacheDir = File(context.cacheDir, "thumbnails").apply { mkdirs() }
    private val semaphore = Semaphore(3)  // 允许最多 3 个并发下载
    private val maxThumbnailSize = 128 // pixels

    /**
     * EXIF 内嵌缩略图读取上限：绝大多数手机/相机的 EXIF 缩略图都在文件前 512KB 内。
     * 只下载这一前缀即可拿到缩略图，避免为 128px 缩略图下载整张多 MB 原图。
     */
    private val exifReadCap = 512 * 1024

    /**
     * 加载缩略图（挂起函数，需在协程中调用）
     * 优先内存缓存 → 磁盘缓存 → EXIF 前缀读取 → 整图下载
     */
    suspend fun loadThumbnail(file: SmbFile): Bitmap? {
        // 内存缓存 key 含 size/lastModified，文件被替换后不会命中旧缩略图
        val key = "${file.path}|${file.size}|${file.lastModified}"

        // 1. 内存缓存
        memoryCache.get(key)?.let { return it }

        // 2. 磁盘缓存（不阻塞 semaphore）
        val cacheFile = getCacheFile(file)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            val bitmap = decodeThumbnail(cacheFile)
            bitmap?.let { memoryCache.put(key, it) }
            return bitmap
        }

        // 3. 生成并缓存（Semaphore 限制并发数为 3）
        return semaphore.withPermit {
            // 双重检查：可能在等待 semaphore 时已被其他协程缓存
            if (cacheFile.exists() && cacheFile.length() > 0) {
                val bitmap = decodeThumbnail(cacheFile)
                bitmap?.let { memoryCache.put(key, it) }
                return@withPermit bitmap
            }

            // 优先尝试 EXIF 内嵌缩略图（仅下载文件前缀），失败再整图下载。
            // 视频：不落盘，从 SMB 随机读取句柄直接抽帧（见 loadVideoThumbnail）
            // HEIC 整图下载解码仅 API 28+ 有效（老系统无 HEIF 解码器，
            // 下载几 MB 原图后 decodeThumbnail 必然返回 null，纯属浪费流量）；
            // 老设备靠 EXIF 内嵌缩略图，没有则回退图标
            val bitmap = when {
                file.isVideo() -> loadVideoThumbnail(file)
                file.isHeicImage() && Build.VERSION.SDK_INT < 28 -> tryExtractExifThumbnail(file)
                else -> tryExtractExifThumbnail(file) ?: downloadAndDecode(file)
            }

            if (bitmap != null) {
                // 将缩略图压缩为 JPEG 写入缓存（~5-15KB/张），下次直接命中磁盘
                runCatching {
                    FileOutputStream(cacheFile).use { out ->
                        bitmap.compress(CompressFormat.JPEG, 80, out)
                    }
                    // 写完后校验磁盘缓存总量，超出上限时淘汰最旧的一批
                    evictDiskCacheIfOverLimit()
                }
                memoryCache.put(key, bitmap)
            }
            bitmap
        }
    }

    /**
     * 尝试从 EXIF 内嵌缩略图解码（仅下载文件前 exifReadCap 字节）
     *
     * 仅对 JPEG/HEIC/HEIF 有效（这几种格式把缩略图嵌在 EXIF 中）。
     * 用 androidx ExifInterface(InputStream) 直接解析内存字节，无需落临时文件；
     * androidx 版本自带 ISO-BMFF 解析，minSdk 24 的老设备也能读 HEIC 内嵌缩略图
     * （framework 版 ExifInterface 要 API 28 才认识 HEIF）。
     * 返回 null 表示无内嵌缩略图或格式不支持，调用方应回退到整图下载。
     */
    private fun tryExtractExifThumbnail(file: SmbFile): Bitmap? {
        val ext = file.getExtension()
        if (ext != "jpg" && ext != "jpeg" && ext != "heic" && ext != "heif") return null

        // 仅下载文件前缀：复用 openFileForStreaming 的随机读取句柄，
        // 只读前 exifReadCap 字节，避免下载整张多 MB 原图
        val cap = if (file.size in 1..exifReadCap) file.size.toInt() else exifReadCap
        return try {
            val handle = smbManager.openFileForStreaming(file.path)
            try {
                val buffer = ByteArray(cap)
                val read = handle.read(buffer, 0, 0, cap)
                if (read <= 0) return null
                val exif = ExifInterface(ByteArrayInputStream(buffer, 0, read))
                exif.thumbnail?.let { raw -> decodeByteArray(raw) }
            } finally {
                runCatching { handle.close() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 视频抽帧生成缩略图（不落盘：通过 MediaDataSource 直接从 SMB 随机读取）
     *
     * - setDataSource(MediaDataSource) 为 API 23+，覆盖 minSdk 24 全部设备；
     *   retriever 抽帧只读 moov 头和关键帧附近的数据，不会下载整个视频
     * - 取 1s 处最近关键帧（OPTION_CLOSEST_SYNC 最快）；个别视频该处无帧时回退 0s
     * - API 27+ 用 getScaledFrameAtTime 直接按目标尺寸出图（省内存）；
     *   低版本 getFrameAtTime 返回原始分辨率帧，缩放后再进缓存
     * - 失败返回 null，FileAdapter 现有 null 分支回退视频图标
     */
    private fun loadVideoThumbnail(file: SmbFile): Bitmap? {
        val source = smbManager.openMediaDataSource(file.path)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source)
            if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(
                    1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    maxThumbnailSize, maxThumbnailSize
                )
            } else {
                val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                frame?.let { f ->
                    val scale = maxThumbnailSize.toFloat() / maxOf(f.width, f.height)
                    if (scale >= 1f) f
                    else Bitmap.createScaledBitmap(
                        f,
                        (f.width * scale).toInt().coerceAtLeast(1),
                        (f.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            // MediaDataSource 的 close 由框架在 release 时回调，此处兜底防句柄泄漏
            //（smbj 的 File.close() 幂等，runCatching 保证双重关闭安全）
            runCatching { retriever.release() }
            runCatching { source.close() }
        }
    }

    /**
     * 回退路径：下载原图到临时文件，解码出采样缩略图
     */
    private fun downloadAndDecode(file: SmbFile): Bitmap? {
        val tempFile = File(diskCacheDir, "full_${System.nanoTime()}.tmp")
        return try {
            FileOutputStream(tempFile).use { out ->
                smbManager.downloadFile(file.path, out)
            }
            decodeThumbnail(tempFile)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private fun decodeByteArray(data: ByteArray): Bitmap? = try {
        BitmapFactory.decodeByteArray(data, 0, data.size)
    } catch (e: Exception) {
        null
    }

    /**
     * 清空内存缓存
     */
    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    /**
     * 校验磁盘缓存总量，超出上限时按修改时间淘汰最旧的一批文件。
     *
     * 缩略图每张 5-15KB，浏览大型相册会累积到几百 MB，需设置总量上限。
     * 调用方（写入缩略图后）触发，删除最旧的文件直至回落到上限内。
     */
    private fun evictDiskCacheIfOverLimit() {
        val files = diskCacheDir.listFiles() ?: return
        var total = 0L
        for (f in files) total += f.length()

        if (total <= DISK_CACHE_MAX_BYTES) return

        // 按最后修改时间升序（最旧在前），优先删除旧的
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= DISK_CACHE_MAX_BYTES) return
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    /**
     * 缓存 key 含 size + lastModified：远程文件被同名替换后（大小或修改时间变化），
     * key 改变，磁盘缓存自然失效，不再命中旧缩略图。MD5 哈希避免文件名碰撞。
     */
    private fun getCacheFile(file: SmbFile): File {
        val cacheKey = "${file.path}|${file.size}|${file.lastModified}"
        val md5 = MessageDigest.getInstance("MD5")
            .digest(cacheKey.toByteArray())
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
            val sampleSize = BitmapSampler.calculateInSampleSize(
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

    companion object {
        /** 内存缓存容量上限（字节）：约可容纳 2000+ 张 128px 缩略图 */        private const val MEMORY_CACHE_MAX_BYTES = 24 * 1024 * 1024

        /** 磁盘缓存总量上限（字节）：50MB，超出时淘汰最旧 */
        private const val DISK_CACHE_MAX_BYTES = 50L * 1024 * 1024
    }
}
