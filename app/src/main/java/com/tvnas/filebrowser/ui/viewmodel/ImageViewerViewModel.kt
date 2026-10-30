package com.tvnas.filebrowser.ui.viewmodel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tvnas.filebrowser.smb.SmbConnectionManager
import com.tvnas.filebrowser.smb.SmbFile
import com.tvnas.filebrowser.util.BitmapSampler
import com.tencent.bugly.crashreport.BuglyLog
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
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import javax.inject.Inject

@HiltViewModel
class ImageViewerViewModel @Inject constructor(
    private val smbManager: SmbConnectionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<ImageUiState>(ImageUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex = _currentIndex.asStateFlow()

    private val _isSlideshowOn = MutableStateFlow(false)
    val isSlideshowOn = _isSlideshowOn.asStateFlow()

    private val _events = MutableSharedFlow<ImageEvent>()
    val events = _events.asSharedFlow()

    private var imageFiles: List<SmbFile> = emptyList()
    private var loadJob: Job? = null
    private var preloadJob: Job? = null
    private var slideshowJob: Job? = null

    // 内存预加载缓存 (index -> Bitmap)，仅维护 [currentIndex - 1, currentIndex, currentIndex + 1] 三张图片
    private val bitmapCache = HashMap<Int, Bitmap>()

    companion object {
        private const val SLIDESHOW_INTERVAL_MS = 5_000L
    }

    private var screenWidth = 0
    private var screenHeight = 0

    fun setScreenSize(w: Int, h: Int) {
        screenWidth = w
        screenHeight = h
    }

    fun init(initialIndex: Int, files: List<SmbFile>) {
        imageFiles = files
        _currentIndex.value = initialIndex
        startLoad(initialIndex)
    }

    fun startLoad(index: Int) {
        if (index < 0 || index >= imageFiles.size) return
        _currentIndex.value = index
        val file = imageFiles[index]

        loadJob?.cancel()

        // 检查预加载缓存
        val cachedBitmap = synchronized(bitmapCache) { bitmapCache[index] }
        if (cachedBitmap != null) {
            _uiState.value = ImageUiState.Success(cachedBitmap, file.name, index, imageFiles.size)
            triggerPreload(index)
            if (_isSlideshowOn.value) scheduleNextSlide()
            return
        }

        _uiState.value = ImageUiState.Loading(file.name, index, imageFiles.size)

        loadJob = viewModelScope.launch {
            try {
                val result = decodeBitmapFromSmb(file)
                if (result.bitmap != null) {
                    synchronized(bitmapCache) {
                        bitmapCache[index] = result.bitmap
                    }
                    _uiState.value = ImageUiState.Success(result.bitmap, file.name, index, imageFiles.size)
                    triggerPreload(index)
                } else {
                    val reason = if (file.size > 0 && result.bytesStreamed in 1 until file.size) {
                        "读取不完整(${result.bytesStreamed}/${file.size}字节)"
                    } else "解码返回 null"
                    BuglyLog.e("ImageViewer", "图片预览失败: $reason, 文件=${file.name}")
                    _uiState.value = ImageUiState.Error("图片加载失败")
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    BuglyLog.e("ImageViewer", "图片加载失败: ${file.name}, error=${e.message}")
                    _uiState.value = ImageUiState.Error(e.message ?: "加载失败")
                }
            } finally {
                if (_isSlideshowOn.value) scheduleNextSlide()
            }
        }
    }

    /**
     * 触发前后一张图片的后台预加载，并淘汰不在 [currentIndex-1, currentIndex, currentIndex+1] 范围内的缓存
     */
    private fun triggerPreload(currentIndex: Int) {
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch(Dispatchers.IO) {
            // 清理过期缓存
            val validIndices = setOf(currentIndex - 1, currentIndex, currentIndex + 1)
            synchronized(bitmapCache) {
                bitmapCache.keys.retainAll(validIndices)
            }

            // 优先预加载下一张（用户正向浏览更普遍），再预加载上一张
            val preloadTargets = listOf(currentIndex + 1, currentIndex - 1)
            for (targetIndex in preloadTargets) {
                if (targetIndex !in 0 until imageFiles.size) continue
                if (synchronized(bitmapCache) { bitmapCache.containsKey(targetIndex) }) continue

                val targetFile = imageFiles[targetIndex]
                try {
                    val bitmap = decodeBitmapFromSmb(targetFile).bitmap
                    if (bitmap != null) {
                        synchronized(bitmapCache) {
                            if (targetIndex in setOf(_currentIndex.value - 1, _currentIndex.value, _currentIndex.value + 1)) {
                                bitmapCache[targetIndex] = bitmap
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    // 预加载失败可静默忽略，用户切到该页时会通过 startLoad 走正常加载与报错流程
                }
            }
        }
    }

    private data class DecodeResult(val bitmap: Bitmap?, val bytesStreamed: Long)

    private suspend fun decodeBitmapFromSmb(file: SmbFile): DecodeResult = withContext(Dispatchers.IO) {
        var bytesStreamed = 0L
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        smbManager.openInputStream(file.path).use { raw ->
            BitmapFactory.decodeStream(
                CountingInputStream(BufferedInputStream(raw)) { bytesStreamed += it },
                null, boundsOptions
            )
        }
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            return@withContext DecodeResult(null, bytesStreamed)
        }

        val sampleSize = BitmapSampler.calculateInSampleSize(boundsOptions, screenWidth, screenHeight)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = smbManager.openInputStream(file.path).use { raw ->
            BitmapFactory.decodeStream(
                CountingInputStream(BufferedInputStream(raw)) { bytesStreamed += it },
                null, decodeOptions
            )
        }
        DecodeResult(bitmap, bytesStreamed)
    }

    fun toggleSlideshow() {
        if (!_isSlideshowOn.value && imageFiles.size < 2) {
            viewModelScope.launch { _events.emit(ImageEvent.ShowToast("至少需要两张图片才能轮播")) }
            return
        }

        _isSlideshowOn.value = !_isSlideshowOn.value
        if (_isSlideshowOn.value) {
            if (_uiState.value !is ImageUiState.Loading) scheduleNextSlide()
        } else {
            slideshowJob?.cancel()
        }
    }

    private fun scheduleNextSlide() {
        slideshowJob?.cancel()
        slideshowJob = viewModelScope.launch {
            delay(SLIDESHOW_INTERVAL_MS)
            showNext(loop = true)
        }
    }

    fun showNext(loop: Boolean = false) {
        val nextIndex = _currentIndex.value + 1
        if (nextIndex < imageFiles.size) {
            startLoad(nextIndex)
        } else if (loop) {
            startLoad(0)
        } else {
            viewModelScope.launch { _events.emit(ImageEvent.ShowToast("已是最后一张图片")) }
        }
    }

    fun showPrevious() {
        val prevIndex = _currentIndex.value - 1
        if (prevIndex >= 0) {
            startLoad(prevIndex)
        } else {
            viewModelScope.launch { _events.emit(ImageEvent.ShowToast("已是第一张图片")) }
        }
    }

    fun cancelPendingJobs() {
        loadJob?.cancel()
        preloadJob?.cancel()
        slideshowJob?.cancel()
        synchronized(bitmapCache) {
            bitmapCache.clear()
        }
    }

    sealed class ImageUiState {
        object Idle : ImageUiState()
        data class Loading(val name: String, val index: Int, val total: Int) : ImageUiState()
        data class Success(val bitmap: Bitmap, val name: String, val index: Int, val total: Int) : ImageUiState()
        data class Error(val message: String) : ImageUiState()
    }

    sealed class ImageEvent {
        data class ShowToast(val message: String) : ImageEvent()
    }

    private class CountingInputStream(input: InputStream, private val onBytesRead: (Int) -> Unit) : FilterInputStream(input) {
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) onBytesRead(1)
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) onBytesRead(n)
            return n
        }
    }
}
