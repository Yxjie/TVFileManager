package com.tvnas.filebrowser.ui

import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.tvnas.filebrowser.R
import com.tvnas.filebrowser.databinding.ActivityImageViewerBinding
import com.tvnas.filebrowser.smb.ServerStorage
import com.tvnas.filebrowser.smb.SmbConnectionManager
import com.tvnas.filebrowser.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 图片查看器 - 全屏大图，底部文件名+序号，左右键切换
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding

    // 使用全局共享的连接管理器，复用 BrowserActivity 的连接
    private val smbManager = SmbConnectionManager.shared

    private var imageFiles: List<SmbFile> = emptyList()
    private var currentIndex: Int = 0
    private var tempImageFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFullscreen()

        // 从 serverId 加载（不再从 Intent 获取密码）
        val serverId = intent.getStringExtra("serverId")
        if (serverId != null) {
            val serverConfig = ServerStorage(this).getServerById(serverId)
            if (serverConfig == null) {
                showToast(getString(R.string.server_config_missing))
                finish()
                return
            }
            // 使用共享连接管理器，如果已连接则直接复用
        }

        currentIndex = intent.getIntExtra("currentIndex", 0)

        val imageListJson = intent.getStringArrayListExtra("imageFiles")
        if (imageListJson != null) {
            imageFiles = imageListJson.map { json ->
                val parts = json.split("|")
                SmbFile(
                    name = parts.getOrElse(0) { "" },
                    path = parts.getOrElse(1) { "" },
                    isDirectory = false,
                    size = parts.getOrElse(2) { "0" }.toLongOrNull() ?: 0,
                    lastModified = parts.getOrElse(3) { "0" }.toLongOrNull() ?: 0
                )
            }
        }

        if (imageFiles.isEmpty()) {
            showToast(getString(R.string.image_not_found))
            finish()
            return
        }

        loadImage(currentIndex)
    }

    /**
     * 使用现代 API 设置全屏沉浸模式
     */
    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    /**
     * 加载图片：下载到临时文件 → inSampleSize 采样解码 → 显示
     * 避免将大图完整加载到内存（防止 OOM）
     */
    private fun loadImage(index: Int) {
        if (index < 0 || index >= imageFiles.size) return

        currentIndex = index
        val file = imageFiles[index]

        binding.tvFileName.text = file.name
        binding.tvIndex.text = getString(R.string.image_index, index + 1, imageFiles.size)
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    // 下载到临时文件（而非内存）
                    val ext = file.getExtension()
                    val tempFile = File.createTempFile("img_viewer_", ".$ext", cacheDir)
                    tempImageFile = tempFile
                    FileOutputStream(tempFile).use { out ->
                        smbManager.downloadFile(file.path, out)
                    }

                    // 获取屏幕尺寸用于计算采样率
                    val displayMetrics = resources.displayMetrics
                    val screenWidth = displayMetrics.widthPixels
                    val screenHeight = displayMetrics.heightPixels

                    // 先获取原始尺寸
                    val boundsOptions = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFile(tempFile.absolutePath, boundsOptions)

                    // 计算采样率，避免加载超出屏幕大小的图片
                    val sampleSize = calculateInSampleSize(
                        boundsOptions, screenWidth, screenHeight
                    )

                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    BitmapFactory.decodeFile(tempFile.absolutePath, decodeOptions)
                }

                if (bitmap != null) {
                    binding.imageView.setImageBitmap(bitmap)
                } else {
                    showToast(getString(R.string.image_load_failed))
                }
            } catch (e: Exception) {
                showToast(getString(R.string.load_failed, e.message ?: ""))
            } finally {
                // 清理临时文件
                tempImageFile?.delete()
                tempImageFile = null
                binding.progressBar.visibility = View.GONE
            }
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

    private fun showNext() {
        if (currentIndex < imageFiles.size - 1) {
            loadImage(currentIndex + 1)
        } else {
            showToast(getString(R.string.last_image))
        }
    }

    private fun showPrevious() {
        if (currentIndex > 0) {
            loadImage(currentIndex - 1)
        } else {
            showToast(getString(R.string.first_image))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                showNext()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                showPrevious()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        tempImageFile?.delete()
        // 不断开共享连接 — 返回 BrowserActivity 后仍可使用
    }
}
