package com.tvnas.filebrowser.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.tvnas.filebrowser.R
import com.tvnas.filebrowser.databinding.ActivityBrowserBinding
import com.tvnas.filebrowser.install.ApkInstaller
import com.tvnas.filebrowser.smb.ServerStorage
import com.tvnas.filebrowser.smb.SmbConnectionManager
import com.tvnas.filebrowser.smb.SmbFile
import com.tencent.bugly.crashreport.BuglyLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    companion object {
        private const val REQUEST_INSTALL_PERMISSION = 1001
    }

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var adapter: FileAdapter
    private lateinit var serverStorage: ServerStorage
    private lateinit var thumbnailLoader: ThumbnailLoader

    // 使用全局共享的连接管理器，跨 Activity 复用连接
    private val smbManager = SmbConnectionManager.shared

    // 从设置页返回后需要安装的 APK 文件
    private var pendingInstallFile: SmbFile? = null

    private var serverId: String? = null
    private var server: String = ""
    private var port: Int = 445
    private var share: String = ""
    private var username: String = ""
    private var password: String = ""
    private var domain: String = ""

    private var currentPath: String = ""
    private val pathStack: ArrayDeque<String> = ArrayDeque()

    // 当前列表加载任务；快速进出目录时取消上一个，避免旧结果覆盖新目录
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverStorage = ServerStorage(this)

        // 从 serverId 加载配置
        val id = intent.getStringExtra("serverId")
        if (id != null) {
            serverId = id
            val serverConfig = serverStorage.getServerById(id)
            if (serverConfig != null) {
                server = serverConfig.serverAddress
                port = serverConfig.port
                share = serverConfig.shareName
                username = serverConfig.username
                password = serverConfig.password
                domain = serverConfig.domain
            } else {
                showToast(getString(R.string.server_config_missing))
                finish()
                return
            }
        } else {
            showToast(getString(R.string.server_config_missing))
            finish()
            return
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
                // 已连接到同一服务器时跳过重连（TCP+SMB 认证在电视上耗时数秒）
                if (!smbManager.isConnectedTo(server, port, share)) {
                    withContext(Dispatchers.IO) {
                        smbManager.connect(server, port, share, username, password, domain)
                    }
                }
                loadFiles(currentPath)
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                BuglyLog.e("Browser", "连接并加载文件失败: server=$server:$port, share=$share, path=$currentPath, error=${e.message}")
                showToast(getString(R.string.connect_failed_detail, e.message ?: ""))
                finish()
            }
        }
    }

    private fun loadFiles(path: String) {
        // 快速进出目录时取消上一个加载任务，避免旧结果覆盖新目录
        loadJob?.cancel()
        binding.progressBar.visibility = View.VISIBLE

        loadJob = lifecycleScope.launch {
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
                binding.tvItemCount.text = getString(R.string.items_count, files.size)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 被新 loadFiles 取消，不提示也不上报
                throw e
            } catch (e: Exception) {
                BuglyLog.e("Browser", "加载文件列表失败: server=$server:$port, share=$share, path=$path, error=${e.message}")
                showToast(getString(R.string.load_failed, e.message ?: ""))
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun onFileClick(file: SmbFile) {
        when {
            file.isDirectory -> {
                pathStack.addLast(currentPath)
                loadFiles(file.path)
            }
            file.isApk() -> showApkInstallDialog(file)
            file.isDecodableImage() -> openImageViewer(file)
            file.isVideo() -> openVideoPlayer(file)
            file.isImage() -> showToast(getString(R.string.unsupported_image_format, file.getExtension().uppercase()))
            else -> showToast(getString(R.string.unsupported_file_type))
        }
    }

    private fun onFileLongClick(file: SmbFile) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val modifiedDate = dateFormat.format(Date(file.lastModified))

        val message = buildString {
            appendLine(getString(R.string.detail_name, file.name))
            appendLine(getString(R.string.detail_path, file.path))
            appendLine(getString(R.string.detail_size, file.formatSize()))
            appendLine(getString(R.string.detail_modified, modifiedDate))
            if (!file.isDirectory) {
                appendLine(getString(R.string.detail_type, file.getExtension()))
            }
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.file_details))
            .setMessage(message)
            .setPositiveButton(getString(R.string.confirm), null)
            .show()
    }

    private fun showApkInstallDialog(file: SmbFile) {
        // Android O+ 需要先检查是否有安装未知来源应用的权限
        if (!ApkInstaller.canInstallUnknownApps(this)) {
            pendingInstallFile = file
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.install_permission_title))
                .setMessage(getString(R.string.install_permission_message))
                .setPositiveButton(getString(R.string.go_settings)) { _, _ ->
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    intent.data = Uri.parse("package:${packageName}")
                    startActivityForResult(intent, REQUEST_INSTALL_PERMISSION)
                }
                .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                    pendingInstallFile = null
                }
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.install_app_title))
            .setMessage(getString(R.string.install_app_message, file.name, file.formatSize()))
            .setPositiveButton(getString(R.string.download_and_install)) { _, _ ->
                downloadAndInstallApk(file)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_INSTALL_PERMISSION) {
            val file = pendingInstallFile
            pendingInstallFile = null
            if (file != null && ApkInstaller.canInstallUnknownApps(this)) {
                // 用户已授权，继续弹出安装确认对话框
                showApkInstallDialog(file)
            }
        }
    }

    private fun downloadAndInstallApk(file: SmbFile) {
        showDownloadProgress(getString(R.string.downloading), 0)

        lifecycleScope.launch {
            try {
                val cacheDir = ApkInstaller.getApkCacheDir(this@BrowserActivity)
                val apkFile = File(cacheDir, file.name)

                withContext(Dispatchers.IO) {
                    FileOutputStream(apkFile).use { outputStream ->
                        // 只在整数百分比变化时切回主线程更新 UI，避免每 64KB 一次的
                        // runOnUiThread 调用刷爆主线程消息队列（大 APK 上万次）
                        var lastPercent = -1
                        smbManager.downloadFile(file.path, outputStream) { downloaded, total ->
                            val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                            if (percent != lastPercent) {
                                lastPercent = percent
                                runOnUiThread {
                                    updateDownloadProgress(getString(R.string.downloading), percent)
                                }
                            }
                        }
                    }
                }

                // withContext(IO) 结束后，所有 runOnUiThread 已入队
                // 直接更新 UI 不会有竞态
                hideDownloadProgress()
                showToast(getString(R.string.download_start_install))

                val installed = ApkInstaller.installApk(this@BrowserActivity, apkFile)

                if (installed) {
                    // 使用 ProcessLifecycleOwner 确保 Activity 销毁后仍能清理 APK
                    ProcessLifecycleOwner.get().lifecycleScope.launch {
                        delay(30_000)
                        val deleted = withContext(Dispatchers.IO) { ApkInstaller.deleteApk(apkFile) }
                        if (!deleted) {
                            BuglyLog.w("Browser", "安装包删除失败: ${apkFile.name}")
                            // Activity 可能已销毁，使用 applicationContext 弹提示
                            Toast.makeText(applicationContext, getString(R.string.apk_delete_failed, apkFile.name), Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    showToast(getString(R.string.install_failed_check))
                    if (!ApkInstaller.deleteApk(apkFile)) {
                        BuglyLog.w("Browser", "安装失败后安装包删除失败: ${apkFile.name}")
                        showToast(getString(R.string.apk_delete_failed, apkFile.name))
                    }
                }

            } catch (e: Exception) {
                hideDownloadProgress()
                BuglyLog.e("Browser", "APK 下载失败: ${file.name}, 大小: ${file.formatSize()}, 路径: ${file.path}, error=${e.message}")
                showToast(getString(R.string.download_failed_detail, e.message ?: ""))
            }
        }
    }

    private fun openImageViewer(file: SmbFile) {
        val imageFiles = adapter.currentList.filter { it.isDecodableImage() }
        val currentIndex = imageFiles.indexOfFirst { it.path == file.path }

        if (currentIndex == -1) return

        // 只传 serverId，不传密码等敏感信息
        val intent = Intent(this, ImageViewerActivity::class.java).apply {
            putExtra("serverId", serverId)
            putExtra("currentIndex", currentIndex)
            putParcelableArrayListExtra("imageFiles", ArrayList(imageFiles))
        }
        startActivity(intent)
    }

    /**
     * 打开视频播放器（流式播放，与 ImageViewer 传参方式一致）
     */
    private fun openVideoPlayer(file: SmbFile) {
        val videoFiles = adapter.currentList.filter { it.isVideo() }
        val currentIndex = videoFiles.indexOfFirst { it.path == file.path }

        if (currentIndex == -1) return

        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra("serverId", serverId)
            putExtra("currentIndex", currentIndex)
            putParcelableArrayListExtra("videoFiles", ArrayList(videoFiles))
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
                    val previousPath = pathStack.removeLast()
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
        // 不断开共享连接 — 其他 Activity（如 ImageViewerActivity）可能还在使用
    }
}
