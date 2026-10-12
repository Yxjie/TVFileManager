package com.tvnas.filebrowser.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvnas.filebrowser.data.ServerRepository
import com.tvnas.filebrowser.smb.ServerConfig
import com.tvnas.filebrowser.smb.SmbConnectionManager
import com.tvnas.filebrowser.util.CacheManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ServerListViewModel @Inject constructor(
    application: Application,
    private val repository: ServerRepository,
    private val smbManager: SmbConnectionManager
) : AndroidViewModel(application) {

    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    private val _cacheSize = MutableStateFlow("")
    val cacheSize: StateFlow<String> = _cacheSize.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>()
    val events = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.migrateIfNeeded()
            loadServers()
        }
        updateCacheSize()
    }

    fun loadServers() {
        viewModelScope.launch {
            repository.getServers().collect {
                _servers.value = it
            }
        }
    }

    fun updateCacheSize() {
        _cacheSize.value = CacheManager.getFormattedCacheSize(getApplication())
    }

    fun deleteServer(server: ServerConfig) {
        viewModelScope.launch {
            repository.deleteServer(server.id)
            _events.emit(UiEvent.ShowToast("已删除"))
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                CacheManager.clearCache(getApplication())
            }
            updateCacheSize()
            _events.emit(UiEvent.ShowToast("已清理 $count 个文件"))
        }
    }

    fun disconnectIfFinishing() {
        smbManager.disconnect()
    }

    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
    }
}
