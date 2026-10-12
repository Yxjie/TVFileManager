package com.tvnas.filebrowser.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvnas.filebrowser.R
import com.tvnas.filebrowser.databinding.ActivityImageViewerBinding
import com.tvnas.filebrowser.smb.SmbFile
import com.tvnas.filebrowser.ui.viewmodel.ImageViewerViewModel
import com.tvnas.filebrowser.util.FullscreenHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 图片查看器 - 全屏大图，底部文件名+序号，左右键切换，支持轮播
 */
@AndroidEntryPoint
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageViewerBinding
    private val viewModel: ImageViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        FullscreenHelper.setup(this)

        val serverId = intent.getStringExtra("serverId")
        val currentIndex = intent.getIntExtra("currentIndex", 0)
        @Suppress("DEPRECATION")
        val imageFiles = intent.getParcelableArrayListExtra<SmbFile>("imageFiles") ?: emptyList()

        if (imageFiles.isEmpty()) {
            showToast(getString(R.string.image_not_found))
            finish()
            return
        }

        val displayMetrics = resources.displayMetrics
        viewModel.setScreenSize(displayMetrics.widthPixels, displayMetrics.heightPixels)
        
        setupListeners()
        observeViewModel()

        viewModel.init(currentIndex, imageFiles)
    }

    private fun setupListeners() {
        binding.btnSlideshow.setOnClickListener { viewModel.toggleSlideshow() }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        handleUiState(state)
                    }
                }
                launch {
                    viewModel.isSlideshowOn.collect { isOn ->
                        updateSlideshowButton(isOn)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is ImageViewerViewModel.ImageEvent.ShowToast -> showToast(event.message)
                        }
                    }
                }
            }
        }
    }

    private fun handleUiState(state: ImageViewerViewModel.ImageUiState) {
        when (state) {
            is ImageViewerViewModel.ImageUiState.Loading -> {
                binding.tvFileName.text = state.name
                binding.tvIndex.text = getString(R.string.image_index, state.index + 1, state.total)
                binding.progressBar.visibility = View.VISIBLE
            }
            is ImageViewerViewModel.ImageUiState.Success -> {
                binding.progressBar.visibility = View.GONE
                binding.imageView.setImageBitmap(state.bitmap)
            }
            is ImageViewerViewModel.ImageUiState.Error -> {
                binding.progressBar.visibility = View.GONE
                if (!viewModel.isSlideshowOn.value) {
                    showToast(state.message)
                }
            }
            else -> {}
        }
    }

    private fun updateSlideshowButton(isOn: Boolean) {
        binding.btnSlideshow.text = getString(
            if (isOn) R.string.slideshow_stop else R.string.slideshow_start
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> {
                viewModel.showNext()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> {
                viewModel.showPrevious()
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                viewModel.toggleSlideshow()
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
        if (isFinishing) {
            viewModel.cancelPendingJobs()
        }
    }
}
