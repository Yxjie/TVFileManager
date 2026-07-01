package com.tvnas.filebrowser.util

import android.content.Context
import java.io.File

/**
 * 缓存管理工具
 * 管理 thumbnails 和 apks 两个缓存目录
 */
object CacheManager {
    
    private const val THUMBNAILS_DIR = "thumbnails"
    private const val APK_CACHE_DIR = "apks"
    
    /**
     * 获取缓存总大小（字节）
     */
    fun getCacheSize(context: Context): Long {
        var totalSize = 0L
        
        // 缩略图缓存
        val thumbnailsDir = File(context.cacheDir, THUMBNAILS_DIR)
        if (thumbnailsDir.exists()) {
            totalSize += getDirSize(thumbnailsDir)
        }
        
        // APK 缓存
        val apkDir = File(context.cacheDir, APK_CACHE_DIR)
        if (apkDir.exists()) {
            totalSize += getDirSize(apkDir)
        }
        
        return totalSize
    }
    
    /**
     * 获取格式化的缓存大小字符串
     */
    fun getFormattedCacheSize(context: Context): String {
        val size = getCacheSize(context)
        return formatFileSize(size)
    }
    
    /**
     * 清除所有缓存
     * @return 清除的文件数量
     */
    fun clearCache(context: Context): Int {
        var count = 0
        
        // 清除缩略图
        val thumbnailsDir = File(context.cacheDir, THUMBNAILS_DIR)
        if (thumbnailsDir.exists()) {
            count += deleteDir(thumbnailsDir)
        }
        
        // 清除 APK
        val apkDir = File(context.cacheDir, APK_CACHE_DIR)
        if (apkDir.exists()) {
            count += deleteDir(apkDir)
        }
        
        return count
    }
    
    /**
     * 递归计算目录大小
     */
    private fun getDirSize(dir: File): Long {
        var size = 0L
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                size += if (file.isDirectory) {
                    getDirSize(file)
                } else {
                    file.length()
                }
            }
        }
        return size
    }
    
    /**
     * 递归删除目录内容，保留目录本身
     * @return 删除的文件数量
     */
    private fun deleteDir(dir: File): Int {
        var count = 0
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    count += deleteDir(file)
                    file.delete()
                } else {
                    if (file.delete()) {
                        count++
                    }
                }
            }
        }
        return count
    }
    
    /**
     * 格式化文件大小为可读字符串
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
