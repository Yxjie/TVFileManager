package com.tvnas.filebrowser.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvnas.filebrowser.data.ServerRepository
import com.tvnas.filebrowser.install.ApkInstaller
import com.tvnas.filebrowser.smb.SmbConnectionManager
import com.tvnas.filebrowser.smb.SmbFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    application: Application,
    private val repository: ServerRepository,
    private val smbManager: SmbConnectionManager
) : AndroidViewModel(application) {

    private val _files = MutableStateFlow<List<SmbFile>>(emptyList())
    val files = _files.asStateFlow()

    // 存储当前目录下全量文件列表，用于客户端分页及跨页媒体播放
    private var allFilesMaster: List<SmbFile> = emptyList()

    fun getAllFiles(): List<SmbFile> = allFilesMaster

    private val _uiState = MutableStateFlow<BrowserUiState>(BrowserUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath = _currentPath.asStateFlow()

    private val _downloadProgress = MutableStateFlow<DownloadState?>(null)
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _events = MutableSharedFlow<BrowserEvent>()
    val events = _events.asSharedFlow()

    private val pathStack = ArrayDeque<String>()
    private var loadJob: Job? = null
    
    var serverHost: String = ""
        private set
    var shareName: String = ""
        private set
    var serverPort: Int = 445
        private set

    fun init(serverId: String) {
        viewModelScope.launch {
            val config = repository.getServerById(serverId) ?: return@launch
            serverHost = config.serverAddress
            serverPort = config.port
            shareName = config.shareName
            
            _uiState.value = BrowserUiState.Loading
            try {
                if (!smbManager.isConnectedTo(config.serverAddress, config.port, config.shareName)) {
                    withContext(Dispatchers.IO) {
                        smbManager.connect(
                            config.serverAddress, config.port, config.shareName,
                            config.username, config.password, config.domain
                        )
                    }
                }
                loadFiles("")
            } catch (e: Exception) {
                _uiState.value = BrowserUiState.Error(e.message ?: "连接失败")
            }
        }
    }

    fun loadFiles(path: String) {
        loadJob?.cancel()
        _uiState.value = BrowserUiState.Loading
        loadJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    smbManager.listFiles(path)
                }
                _currentPath.value = path
                
                // 初始化分页状态
                allFilesMaster = result
                
                // 仅下发第一页数据
                val initialPage = result.take(PAGE_SIZE)
                _files.value = initialPage
                
                _uiState.value = if (result.isEmpty()) BrowserUiState.Empty else BrowserUiState.Success
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _uiState.value = BrowserUiState.Error(e.message ?: "加载失败")
                }
            }
        }
    }

    /**
     * 加载下一页数据并追加到当前列表
     */
    fun loadNextPage() {
        if (_uiState.value !is BrowserUiState.Success) return
        
        val totalCount = allFilesMaster.size
        val currentLoadedCount = _files.value.size
        
        if (currentLoadedCount >= totalCount) return // 已全部加载

        val nextBatch = allFilesMaster.drop(currentLoadedCount).take(PAGE_SIZE)
        if (nextBatch.isNotEmpty()) {
            _files.value = _files.value + nextBatch
        }
    }

    fun navigateInto(file: SmbFile) {
        pathStack.addLast(_currentPath.value)
        loadFiles(file.path)
    }

    fun navigateBack(): Boolean {
        if (pathStack.isNotEmpty()) {
            loadFiles(pathStack.removeLast())
            return true
        }
        return false
    }

    fun downloadAndInstallApk(file: SmbFile) {
        viewModelScope.launch {
            _downloadProgress.value = DownloadState("正在下载...", 0)
            try {
                val cacheDir = ApkInstaller.getApkCacheDir(getApplication())
                val apkFile = File(cacheDir, file.name)

                withContext(Dispatchers.IO) {
                    FileOutputStream(apkFile).use { outputStream ->
                        var lastPercent = -1
                        smbManager.downloadFile(file.path, outputStream) { downloaded, total ->
                            val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _downloadProgress.value = DownloadState("正在下载...", percent)
                            }
                        }
                    }
                }

                _downloadProgress.value = null
                _events.emit(BrowserEvent.StartInstall(apkFile))

            } catch (e: Exception) {
                _downloadProgress.value = null
                _events.emit(BrowserEvent.ShowToast("下载失败: ${e.message}"))
            }
        }
    }

    fun cleanupApk(apkFile: File) {
        viewModelScope.launch {
            delay(30_000)
            withContext(Dispatchers.IO) {
                ApkInstaller.deleteApk(apkFile)
            }
        }
    }

    sealed class BrowserUiState {
        object Idle : BrowserUiState()
        object Loading : BrowserUiState()
        object Success : BrowserUiState()
        object Empty : BrowserUiState()
        data class Error(val message: String) : BrowserUiState()
    }

    data class DownloadState(val message: String, val progress: Int)

    sealed class BrowserEvent {
        data class ShowToast(val message: String) : BrowserEvent()
        data class StartInstall(val file: File) : BrowserEvent()
    }

    companion object {
        private const val PAGE_SIZE = 60
    }
}
