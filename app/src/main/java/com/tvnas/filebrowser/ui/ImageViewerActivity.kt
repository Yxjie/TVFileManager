package com.tvnas.filebrowser.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tvnas.filebrowser.databinding.ActivityImageViewerBinding
import com.tvnas.filebrowser.smb.SmbConnectionManager
import com.tvnas.filebrowser.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 图片查看器 - 全屏大图，底部文件名+序号，左右键切换
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding
    private val smbManager = SmbConnectionManager()

    private var imageFiles: List<SmbFile> = emptyList()
    private var currentIndex: Int = 0

    // Connection info
    private var server: String = ""
    private var port: Int = 445
    private var share: String = ""
    private var username: String = ""
    private var password: String = ""
    private var domain: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hide system UI for fullscreen
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        // Get intent data
        server = intent.getStringExtra("server") ?: ""
        port = intent.getIntExtra("port", 445)
        share = intent.getStringExtra("share") ?: ""
        username = intent.getStringExtra("username") ?: ""
        password = intent.getStringExtra("password") ?: ""
        domain = intent.getStringExtra("domain") ?: ""
        currentIndex = intent.getIntExtra("currentIndex", 0)

        // Get image file list from JSON
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
            showToast("没有图片")
            finish()
            return
        }

        connectAndShowImage()
    }

    private fun connectAndShowImage() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (!smbManager.isConnected()) {
                        smbManager.connect(server, port, share, username, password, domain)
                    }
                }
                loadImage(currentIndex)
            } catch (e: Exception) {
                showToast("连接失败: ${e.message}")
                finish()
            }
        }
    }

    private fun loadImage(index: Int) {
        if (index < 0 || index >= imageFiles.size) return

        currentIndex = index
        val file = imageFiles[index]

        // Update info bar
        binding.tvFileName.text = file.name
        binding.tvIndex.text = "${index + 1} / ${imageFiles.size}"

        // Show loading
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val outputStream = ByteArrayOutputStream()
                    smbManager.downloadFile(file.path, outputStream)
                    val bytes = outputStream.toByteArray()
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }

                if (bitmap != null) {
                    binding.imageView.setImageBitmap(bitmap)
                } else {
                    showToast("无法加载图片")
                }
            } catch (e: Exception) {
                showToast("加载失败: ${e.message}")
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun showNext() {
        if (currentIndex < imageFiles.size - 1) {
            loadImage(currentIndex + 1)
        } else {
            showToast("已经是最后一张")
        }
    }

    private fun showPrevious() {
        if (currentIndex > 0) {
            loadImage(currentIndex - 1)
        } else {
            showToast("已经是第一张")
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
        smbManager.disconnect()
    }
}
