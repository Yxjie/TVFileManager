package com.tvnas.filebrowser.install

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装辅助类
 */
object ApkInstaller {
    
    private const val TAG = "ApkInstaller"
    
    /**
     * 安装 APK 文件
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile
                    )
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    val uri = Uri.fromFile(apkFile)
                    setDataAndType(uri, "application/vnd.android.package-archive")
                }
            }
            
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "安装 APK 失败", e)
            false
        }
    }
    
    /**
     * 创建 APK 缓存目录
     */
    fun getApkCacheDir(context: Context): File {
        val cacheDir = File(context.cacheDir, "apks")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }
    
    /**
     * 清理 APK 缓存
     */
    fun clearApkCache(context: Context) {
        try {
            val cacheDir = getApkCacheDir(context)
            cacheDir.listFiles()?.forEach { file ->
                if (file.extension == "apk") {
                    file.delete()
                    Log.d(TAG, "已删除: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理缓存失败", e)
        }
    }
    
    /**
     * 删除指定 APK 文件
     */
    fun deleteApk(apkFile: File): Boolean {
        return try {
            if (apkFile.exists()) {
                val deleted = apkFile.delete()
                Log.d(TAG, "删除 APK: ${apkFile.name}, 成功: $deleted")
                deleted
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "删除 APK 失败", e)
            false
        }
    }
    
    /**
     * 检查是否有安装未知来源应用的权限
     */
    fun canInstallUnknownApps(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }
    
    /**
     * 请求安装未知来源应用的权限
     */
    fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            intent.data = Uri.parse("package:${activity.packageName}")
            activity.startActivity(intent)
        }
    }
}
