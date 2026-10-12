package com.tvnas.filebrowser.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvnas.filebrowser.R
import com.tvnas.filebrowser.databinding.ActivityBrowserBinding
import com.tvnas.filebrowser.install.ApkInstaller
import com.tvnas.filebrowser.smb.SmbFile
import com.tvnas.filebrowser.ui.viewmodel.BrowserViewModel
import com.tencent.bugly.crashreport.BuglyLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 文件浏览器页面
 */
@AndroidEntryPoint
class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private val viewModel: BrowserViewModel by viewModels()
    private lateinit var adapter: FileAdapter

    @Inject
    lateinit var thumbnailLoader: ThumbnailLoader

    // 从设置页返回后需要安装的 APK 文件
    private var pendingInstallFile: SmbFile? = null
    private var serverId: String? = null

    companion object {
        private const val REQUEST_INSTALL_PERMISSION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverId = intent.getStringExtra("serverId")
        if (serverId == null) {
            showToast(getString(R.string.server_config_missing))
            finish()
            return
        }

        setupRecyclerView()
        setupClickListeners()
        observeViewModel()

        viewModel.init(serverId!!)
    }

    private fun setupClickListeners() {
        binding.btnRefresh.setOnClickListener {
            viewModel.loadFiles(viewModel.currentPath.value)
        }
    }

    private fun setupRecyclerView() {
        adapter = FileAdapter(
            thumbnailLoader = thumbnailLoader,
            lifecycleScope = lifecycleScope,
            onItemClick = { file -> onFileClick(file) },
            onItemLongClick = { file -> onFileLongClick(file) }
        )

        val layoutManager = GridLayoutManager(this, 4)
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter

        // 添加分页加载监听
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                
                // dy > 0 表示向下滑动；canScrollVertically(1) 兜底 TV 遥控器惯性滚到底部时无 dy 事件
                if (dy > 0 || !recyclerView.canScrollVertically(1)) {
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                    // 提前加载：剩余 2 行（8 个）时开始加载下一页
                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 8) {
                        viewModel.loadNextPage()
                    }
                }
            }
        })
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.files.collect { files ->
                        adapter.submitList(files)
                    }
                }
                launch {
                    viewModel.uiState.collect { state ->
                        handleUiState(state)
                    }
                }
                launch {
                    viewModel.currentPath.collect { path ->
                        updatePathDisplay(path)
                    }
                }
                launch {
                    viewModel.downloadProgress.collect { state ->
                        if (state != null) {
                            showDownloadProgress(state.message, state.progress)
                        } else {
                            hideDownloadProgress()
                        }
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is BrowserViewModel.BrowserEvent.ShowToast -> showToast(event.message)
                            is BrowserViewModel.BrowserEvent.StartInstall -> startApkInstall(event.file)
                        }
                    }
                }
            }
        }
    }

    private fun handleUiState(state: BrowserViewModel.BrowserUiState) {
        binding.progressBar.visibility = if (state is BrowserViewModel.BrowserUiState.Loading) View.VISIBLE else View.GONE
        binding.tvEmpty.visibility = if (state is BrowserViewModel.BrowserUiState.Empty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (state is BrowserViewModel.BrowserUiState.Success) View.VISIBLE else View.GONE

        if (state is BrowserViewModel.BrowserUiState.Error) {
            showToast(state.message)
        }
    }

    private fun onFileClick(file: SmbFile) {
        when {
            file.isDirectory -> viewModel.navigateInto(file)
            file.isApk() -> showApkInstallDialog(file)
            file.isViewableImage() -> openImageViewer(file)
            file.isVideo() -> openVideoPlayer(file)
            file.isImage() -> showToast(getString(R.string.unsupported_image_format, file.getExtension().uppercase()))
            else -> showToast(getString(R.string.unsupported_file_type))
        }
    }

    private fun onFileLongClick(file: SmbFile) {
        val message = buildString {
            appendLine(getString(R.string.detail_name, file.name))
            appendLine(getString(R.string.detail_path, file.path))
            appendLine(getString(R.string.detail_size, file.formatSize()))
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            appendLine(getString(R.string.detail_modified, dateFormat.format(Date(file.lastModified))))
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
        if (!ApkInstaller.canInstallUnknownApps(this)) {
            pendingInstallFile = file
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.install_permission_title))
                .setMessage(getString(R.string.install_permission_message))
                .setPositiveButton(getString(R.string.go_settings)) { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
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
                viewModel.downloadAndInstallApk(file)
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
                showApkInstallDialog(file)
            }
        }
    }

    private fun startApkInstall(apkFile: File) {
        showToast(getString(R.string.download_start_install))
        val installed = ApkInstaller.installApk(this, apkFile)
        if (installed) {
            viewModel.cleanupApk(apkFile)
        } else {
            showToast(getString(R.string.install_failed_check))
            ApkInstaller.deleteApk(apkFile)
        }
    }

    private fun openImageViewer(file: SmbFile) {
        val allFiles = viewModel.getAllFiles()
        val imageFiles = allFiles.filter { it.isViewableImage() }
        val currentIndex = imageFiles.indexOfFirst { it.path == file.path }
        if (currentIndex == -1) return

        val intent = Intent(this, ImageViewerActivity::class.java).apply {
            putExtra("serverId", serverId)
            putExtra("currentIndex", currentIndex)
            putParcelableArrayListExtra("imageFiles", ArrayList(imageFiles))
        }
        startActivity(intent)
    }

    private fun openVideoPlayer(file: SmbFile) {
        val allFiles = viewModel.getAllFiles()
        val videoFiles = allFiles.filter { it.isVideo() }
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

    private fun hideDownloadProgress() {
        binding.layoutDownloadProgress.visibility = View.GONE
    }

    private fun updatePathDisplay(currentPath: String) {
        val displayPath = if (currentPath.isEmpty()) {
            "smb://${viewModel.serverHost}/${viewModel.shareName}/"
        } else {
            "smb://${viewModel.serverHost}/${viewModel.shareName}/${currentPath.replace("\\", "/")}"
        }
        binding.tvPath.text = displayPath
        binding.tvServerInfo.text = "${viewModel.serverHost}:${viewModel.serverPort}"
        binding.tvItemCount.text = getString(R.string.items_count, viewModel.getAllFiles().size)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (viewModel.navigateBack()) {
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                viewModel.loadFiles(viewModel.currentPath.value)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::thumbnailLoader.isInitialized) {
            thumbnailLoader.clearMemoryCache()
        }
    }
}
