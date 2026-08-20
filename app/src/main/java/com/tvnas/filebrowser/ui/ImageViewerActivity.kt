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
import com.tencent.bugly.crashreport.BuglyLog
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream

/**
 * 图片查看器 - 全屏大图，底部文件名+序号，左右键切换，支持轮播
 */
class ImageViewerActivity : AppCompatActivity() {

    companion object {
        private const val SLIDESHOW_INTERVAL_MS = 5_000L // 轮播间隔：当前图显示完成后再计时
    }

    private lateinit var binding: ActivityImageViewerBinding

    // 使用全局共享的连接管理器，复用 BrowserActivity 的连接
    private val smbManager = SmbConnectionManager.shared

    private var imageFiles: List<SmbFile> = emptyList()
    private var currentIndex: Int = 0
    private var tempImageFile: File? = null

    // 当前加载任务（切换时取消上一个，避免并发加载导致旧图覆盖新图）
    private var loadJob: Job? = null
    private var isLoading = false

    // 轮播状态
    private var isSlideshowOn = false
    private var slideshowJob: Job? = null

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

        binding.btnSlideshow.setOnClickListener { toggleSlideshow() }
        updateSlideshowButton()

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
     *
     * 切换时先取消上一个加载任务，避免并发下载时旧图晚到覆盖新图。
     * 加载完成（无论成败）后若轮播开启，从"显示完成"这一刻开始计时下一张。
     */
    private fun loadImage(index: Int) {
        if (index < 0 || index >= imageFiles.size) return

        currentIndex = index
        val file = imageFiles[index]

        binding.tvFileName.text = file.name
        binding.tvIndex.text = getString(R.string.image_index, index + 1, imageFiles.size)
        binding.progressBar.visibility = View.VISIBLE
        isLoading = true

        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            // 用局部变量持有本次任务的临时文件，避免与被取消的旧任务互相误删
            var localTempFile: File? = null
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    // 下载到临时文件（而非内存）
                    val ext = file.getExtension()
                    val tempFile = File.createTempFile("img_viewer_", ".$ext", cacheDir)
                    localTempFile = tempFile
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

                // 任务已被取消（用户切走了）则不再碰 UI，交由新任务处理
                if (!isActive) return@launch

                if (bitmap != null) {
                    binding.imageView.setImageBitmap(bitmap)
                } else {
                    // 解码返回 null：文件损坏 / 扩展名与实际格式不符 / 下载不完整
                    // 这是数据问题而非代码缺陷 —— 仅记录日志（随下次上报一并上传），
                    // 不再构造 IllegalStateException 作为异常上报（会在 Bugly 上
                    // 形成大量"崩溃"噪音）。注：macOS 的 ._ 资源分支等垃圾文件
                    // 已在 SmbConnectionManager.listFiles 中过滤，不会走到这里。
                    val downloaded = localTempFile?.length() ?: -1L
                    val reason = if (file.size > 0 && downloaded in 0 until file.size) {
                        "下载不完整(${downloaded}/${file.size}字节)"
                    } else {
                        "解码返回 null"
                    }
                    val detail = "图片预览失败: $reason, 文件=${file.name}, 格式=${file.getExtension()}, " +
                        "大小=${file.size}B, 路径=${file.path}"
                    BuglyLog.e("ImageViewer", detail)
                    showToast(getString(R.string.image_load_failed))
                }
            } catch (e: CancellationException) {
                // 用户切换图片/退出页面导致的协程正常取消，不属于错误，不上报
                throw e
            } catch (e: Exception) {
                // 下载或解码过程抛异常 —— 记录文件上下文并上报 Bugly
                BuglyLog.e(
                    "ImageViewer",
                    "图片加载失败: ${file.name}, 格式: ${file.getExtension()}, 大小: ${file.formatSize()}, " +
                        "路径: ${file.path}, error=${e.message}"
                )
                CrashReport.postCatchedException(e)
                showToast(getString(R.string.load_failed, e.message ?: ""))
            } finally {
                localTempFile?.delete()
                // 被取消的任务不碰共享 UI 状态（进度条/isLoading），由新任务接管
                if (isActive) {
                    if (tempImageFile == localTempFile) tempImageFile = null
                    binding.progressBar.visibility = View.GONE
                    isLoading = false
                    // 轮播计时从"本张显示完成"开始，加载慢也不会盲目跳张
                    if (isSlideshowOn) scheduleNextSlide()
                }
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

    /**
     * 轮播开关（底部按钮 / MENU 键）
     */
    private fun toggleSlideshow() {
        if (!isSlideshowOn && imageFiles.size < 2) {
            showToast(getString(R.string.slideshow_need_images))
            return
        }

        isSlideshowOn = !isSlideshowOn
        updateSlideshowButton()

        if (isSlideshowOn) {
            showToast(getString(R.string.slideshow_started))
            // 当前图正在加载时由 loadImage 完成后计时；否则立即开始计时
            if (!isLoading) scheduleNextSlide()
        } else {
            showToast(getString(R.string.slideshow_stopped))
            slideshowJob?.cancel()
            slideshowJob = null
        }
    }

    /**
     * 安排下一张轮播：延迟从调用时刻（即当前图显示完成后）开始计算，
     * 加载再慢也不会出现"没加载出来就跳下一张"
     */
    private fun scheduleNextSlide() {
        if (!isSlideshowOn) return
        slideshowJob?.cancel()
        slideshowJob = lifecycleScope.launch {
            delay(SLIDESHOW_INTERVAL_MS)
            if (isSlideshowOn) {
                // 到末尾循环回第一张
                if (currentIndex < imageFiles.size - 1) {
                    loadImage(currentIndex + 1)
                } else {
                    loadImage(0)
                }
            }
        }
    }

    private fun updateSlideshowButton() {
        binding.btnSlideshow.text = getString(
            if (isSlideshowOn) R.string.slideshow_stop else R.string.slideshow_start
        )
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
            KeyEvent.KEYCODE_MENU -> {
                toggleSlideshow()
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
