package com.tvnas.filebrowser.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.tvnas.filebrowser.R
import com.tvnas.filebrowser.databinding.ActivityBrowserBinding
import com.tvnas.filebrowser.install.ApkInstaller
import com.tvnas.filebrowser.smb.ServerStorage
import com.tvnas.filebrowser.smb.SmbConnectionManager
import com.tvnas.filebrowser.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * 文件浏览器页面
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var adapter: FileAdapter
    private lateinit var serverStorage: ServerStorage
    private lateinit var thumbnailLoader: ThumbnailLoader
    private val smbManager = SmbConnectionManager()

    private var server: String = ""
    private var port: Int = 445
    private var share: String = ""
    private var username: String = ""
    private var password: String = ""
    private var domain: String = ""

    private var currentPath: String = ""
    private var pathStack: MutableList<String> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverStorage = ServerStorage(this)

        // 获取服务器 ID 并加载配置
        val serverId = intent.getStringExtra("serverId")
        if (serverId != null) {
            val serverConfig = serverStorage.getServerById(serverId)
            if (serverConfig != null) {
                server = serverConfig.serverAddress
                port = serverConfig.port
                share = serverConfig.shareName
                username = serverConfig.username
                password = serverConfig.password
                domain = serverConfig.domain
            } else {
                showToast("服务器配置不存在")
                finish()
                return
            }
        } else {
            // 兼容旧版本，直接从 intent 获取连接信息
            server = intent.getStringExtra("server") ?: ""
            port = intent.getIntExtra("port", 445)
            share = intent.getStringExtra("share") ?: ""
            username = intent.getStringExtra("username") ?: ""
            password = intent.getStringExtra("password") ?: ""
            domain = intent.getStringExtra("domain") ?: ""
        }

        setupRecyclerView()
        setupClickListeners()
        connectAndLoadFiles()
    }

    private fun setupClickListeners() {
        binding.btnRefresh.setOnClickListener {
            loadFiles(currentPath)
        }
    }

    private fun setupRecyclerView() {
        thumbnailLoader = ThumbnailLoader(this, smbManager)
        adapter = FileAdapter(
            thumbnailLoader = thumbnailLoader,
            lifecycleScope = lifecycleScope,
            onItemClick = { file -> onFileClick(file) },
            onItemLongClick = { file -> onFileLongClick(file) }
        )

        binding.recyclerView.layoutManager = GridLayoutManager(this, 4)
        binding.recyclerView.adapter = adapter
    }

    private fun connectAndLoadFiles() {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (!smbManager.isConnected()) {
                        smbManager.connect(server, port, share, username, password, domain)
                    }
                }
                loadFiles(currentPath)
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                showToast("连接失败: ${e.message}")
                finish()
            }
        }
    }

    private fun loadFiles(path: String) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    smbManager.listFiles(path)
                }

                currentPath = path
                adapter.submitList(files)

                if (files.isEmpty()) {
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.tvEmpty.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                }

                updatePathDisplay()
                binding.tvItemCount.text = "${files.size} 个项目"
            } catch (e: Exception) {
                showToast("加载失败: ${e.message}")
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun onFileClick(file: SmbFile) {
        if (file.isDirectory) {
            pathStack.add(currentPath)
            loadFiles(file.path)
        } else if (file.isApk()) {
            showApkInstallDialog(file)
        } else if (file.isImage()) {
            openImageViewer(file)
        } else {
            showToast("暂不支持打开此文件类型")
        }
    }

    private fun onFileLongClick(file: SmbFile) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val modifiedDate = dateFormat.format(Date(file.lastModified))

        val message = buildString {
            appendLine("名称: ${file.name}")
            appendLine("路径: ${file.path}")
            appendLine("大小: ${file.formatSize()}")
            appendLine("修改时间: $modifiedDate")
            if (!file.isDirectory) {
                appendLine("类型: ${file.getExtension()}")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("文件详情")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showApkInstallDialog(file: SmbFile) {
        AlertDialog.Builder(this)
            .setTitle("安装应用")
            .setMessage("是否下载并安装 ${file.name}?\n\n大小: ${file.formatSize()}")
            .setPositiveButton("下载安装") { _, _ ->
                downloadAndInstallApk(file)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun downloadAndInstallApk(file: SmbFile) {
        showDownloadProgress("正在下载 ${file.name}...", 0)

        lifecycleScope.launch {
            try {
                val cacheDir = ApkInstaller.getApkCacheDir(this@BrowserActivity)
                val apkFile = File(cacheDir, file.name)

                withContext(Dispatchers.IO) {
                    FileOutputStream(apkFile).use { outputStream ->
                        smbManager.downloadFile(file.path, outputStream) { downloaded, total ->
                            val progress = if (total > 0) (downloaded * 100 / total).toInt() else 0
                            runOnUiThread {
                                updateDownloadProgress("正在下载 ${file.name}...", progress)
                            }
                        }
                    }
                }

                hideDownloadProgress()
                showToast("下载完成，开始安装")

                val installed = ApkInstaller.installApk(this@BrowserActivity, apkFile)

                if (installed) {
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(30000)
                        ApkInstaller.deleteApk(apkFile)
                    }
                } else {
                    showToast("安装失败，请检查系统设置")
                    ApkInstaller.deleteApk(apkFile)
                }

            } catch (e: Exception) {
                hideDownloadProgress()
                showToast("下载失败: ${e.message}")
            }
        }
    }

    private fun openImageViewer(file: SmbFile) {
        val imageFiles = adapter.currentList.filter { it.isImage() }
        val currentIndex = imageFiles.indexOfFirst { it.path == file.path }

        if (currentIndex == -1) return

        val imageListJson = ArrayList(imageFiles.map {
            "${it.name}|${it.path}|${it.size}|${it.lastModified}"
        })

        val intent = Intent(this, ImageViewerActivity::class.java).apply {
            putExtra("server", server)
            putExtra("port", port)
            putExtra("share", share)
            putExtra("username", username)
            putExtra("password", password)
            putExtra("domain", domain)
            putExtra("currentIndex", currentIndex)
            putStringArrayListExtra("imageFiles", imageListJson)
        }
        startActivity(intent)
    }

    private fun showDownloadProgress(message: String, progress: Int) {
        binding.layoutDownloadProgress.visibility = View.VISIBLE
        binding.tvDownloadStatus.text = message
        binding.downloadProgressBar.progress = progress
    }

    private fun updateDownloadProgress(message: String, progress: Int) {
        binding.tvDownloadStatus.text = message
        binding.downloadProgressBar.progress = progress
    }

    private fun hideDownloadProgress() {
        binding.layoutDownloadProgress.visibility = View.GONE
    }

    private fun updatePathDisplay() {
        val displayPath = if (currentPath.isEmpty()) {
            "smb://$server/$share/"
        } else {
            "smb://$server/$share/${currentPath.replace("\\", "/")}"
        }
        binding.tvPath.text = displayPath
        binding.tvServerInfo.text = "$server:$port"
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (pathStack.isNotEmpty()) {
                    val previousPath = pathStack.removeAt(pathStack.lastIndex)
                    loadFiles(previousPath)
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                loadFiles(currentPath)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbnailLoader.clearMemoryCache()
        if (smbManager.isConnected()) {
            smbManager.disconnect()
        }
    }
}
