package com.tvnas.filebrowser.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvnas.filebrowser.data.ServerRepository
import com.tvnas.filebrowser.smb.ServerConfig
import com.tvnas.filebrowser.smb.SmbConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val repository: ServerRepository,
    private val smbManager: SmbConnectionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<ConnectUiState>(ConnectUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ConnectEvent>()
    val events = _events.asSharedFlow()

    private var editingServer: ServerConfig? = null

    fun setEditingServer(serverId: String?) {
        if (serverId == null) return
        viewModelScope.launch {
            editingServer = repository.getServerById(serverId)
            editingServer?.let {
                _events.emit(ConnectEvent.FillForm(it))
            }
        }
    }

    fun connectAndSave(
        address: String,
        port: Int,
        share: String,
        user: String,
        pass: String,
        domain: String
    ) {
        viewModelScope.launch {
            _uiState.value = ConnectUiState.Loading("正在连接到 $address...")
            
            val testManager = SmbConnectionManager()
            try {
                withContext(Dispatchers.IO) {
                    testManager.connect(address, port, share, user, pass, domain)
                }

                val config = ServerConfig(
                    id = editingServer?.id ?: System.currentTimeMillis().toString(),
                    name = editingServer?.name ?: "",
                    serverAddress = address,
                    port = port,
                    shareName = share,
                    username = user,
                    password = pass,
                    domain = domain
                )
                repository.saveServer(config)
                _events.emit(ConnectEvent.NavigateToBrowser(config.id))

            } catch (e: Exception) {
                _uiState.value = ConnectUiState.Error(e.message ?: "连接失败")
            } finally {
                testManager.disconnect()
            }
        }
    }

    fun deleteServer() {
        val id = editingServer?.id ?: return
        viewModelScope.launch {
            repository.deleteServer(id)
            _events.emit(ConnectEvent.ServerDeleted)
        }
    }

    sealed class ConnectUiState {
        object Idle : ConnectUiState()
        data class Loading(val message: String) : ConnectUiState()
        data class Error(val message: String) : ConnectUiState()
    }

    sealed class ConnectEvent {
        data class FillForm(val server: ServerConfig) : ConnectEvent()
        data class NavigateToBrowser(val serverId: String) : ConnectEvent()
        object ServerDeleted : ConnectEvent()
    }
}
