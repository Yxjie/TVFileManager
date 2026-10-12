package com.tvnas.filebrowser.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvnas.filebrowser.data.ServerRepository
import com.tvnas.filebrowser.media.PlayPositionStore
import com.tvnas.filebrowser.smb.SmbFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    application: Application,
    private val repository: ServerRepository
) : AndroidViewModel(application) {

    private val _videoFiles = MutableStateFlow<List<SmbFile>>(emptyList())
    val videoFiles = _videoFiles.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex = _currentIndex.asStateFlow()

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo = _serverInfo.asStateFlow()

    private val _pendingPlayIndex = MutableStateFlow<Int?>(null)
    val pendingPlayIndex = _pendingPlayIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)

    private val _events = MutableSharedFlow<PlayerEvent>()
    val events = _events.asSharedFlow()

    var host: String = ""
    var share: String = ""

    companion object {
        private const val SAVE_INTERVAL_MS = 10_000L
        const val MIN_RESUME_MS = 10_000L
        private const val END_MARGIN_MS = 15_000L
    }

    fun init(serverId: String, initialIndex: Int, files: List<SmbFile>) {
        _videoFiles.value = files
        _currentIndex.value = initialIndex
        
        viewModelScope.launch {
            val config = repository.getServerById(serverId)
            if (config != null) {
                host = config.serverAddress
                share = config.shareName
                _serverInfo.value = ServerInfo(host, share)
                _pendingPlayIndex.value = initialIndex
            } else {
                _events.emit(PlayerEvent.Error("服务器配置丢失"))
            }
        }

        startPeriodicPositionSave()
    }

    private fun startPeriodicPositionSave() {
        viewModelScope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                if (_isPlaying.value) {
                    _events.emit(PlayerEvent.RequestSavePosition)
                }
            }
        }
    }

    fun updatePlayingState(isPlaying: Boolean) {
        _isPlaying.value = isPlaying
    }

    fun savePosition(currentPos: Long, duration: Long) {
        val file = _videoFiles.value.getOrNull(_currentIndex.value) ?: return
        if (duration > 0 && currentPos >= duration - END_MARGIN_MS) {
            PlayPositionStore.clear(getApplication(), host, share, file)
        } else if (currentPos > MIN_RESUME_MS) {
            PlayPositionStore.save(getApplication(), host, share, file, currentPos)
        }
    }

    fun getSavedPosition(file: SmbFile): Long {
        return PlayPositionStore.get(getApplication(), host, share, file)
    }

    fun playNext(autoPlay: Boolean = false) {
        val nextIndex = _currentIndex.value + 1
        viewModelScope.launch {
            if (nextIndex < _videoFiles.value.size) {
                _currentIndex.value = nextIndex
                _events.emit(PlayerEvent.PlayVideo(nextIndex))
            } else if (autoPlay && _videoFiles.value.size > 1) {
                _currentIndex.value = 0
                _events.emit(PlayerEvent.PlayVideo(0))
            } else if (!autoPlay) {
                _events.emit(PlayerEvent.ShowToast("已是最后一个视频"))
            }
        }
    }

    fun playPrevious() {
        val prevIndex = _currentIndex.value - 1
        viewModelScope.launch {
            if (prevIndex >= 0) {
                _currentIndex.value = prevIndex
                _events.emit(PlayerEvent.PlayVideo(prevIndex))
            } else {
                _events.emit(PlayerEvent.ShowToast("已是第一个视频"))
            }
        }
    }

    fun onVideoStarted(index: Int) {
        _currentIndex.value = index
    }

    fun clearPendingPlay() {
        _pendingPlayIndex.value = null
    }

    data class ServerInfo(val host: String, val share: String)

    sealed class PlayerEvent {
        data class PlayVideo(val index: Int) : PlayerEvent()
        data class Error(val message: String) : PlayerEvent()
        data class ShowToast(val message: String) : PlayerEvent()
        object RequestSavePosition : PlayerEvent()
    }
}
