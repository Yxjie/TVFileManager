package com.tvnas.filebrowser.ui

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.tvnas.filebrowser.R
import com.tvnas.filebrowser.databinding.ActivityVideoPlayerBinding
import com.tvnas.filebrowser.media.MimeTypeUtil
import com.tvnas.filebrowser.media.SmbDataSourceFactory
import com.tvnas.filebrowser.smb.SmbFile
import com.tvnas.filebrowser.ui.viewmodel.VideoPlayerViewModel
import com.tvnas.filebrowser.util.FullscreenHelper
import com.tencent.bugly.crashreport.BuglyLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 视频播放器 - 全屏播放，从 SMB 共享流式读取
 */
@AndroidEntryPoint
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private val viewModel: VideoPlayerViewModel by viewModels()
    private var player: ExoPlayer? = null

    // 用于判断 D-pad 左右键是切换视频还是 seek
    private var isControllerVisible: Boolean = false

    // 自动隐藏信息栏的延时任务
    private var hideInfoJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        FullscreenHelper.setup(this)

        val serverId = intent.getStringExtra("serverId")
        val currentIndex = intent.getIntExtra("currentIndex", 0)
        @Suppress("DEPRECATION")
        val videoFiles = intent.getParcelableArrayListExtra<SmbFile>("videoFiles") ?: emptyList()

        if (serverId == null || videoFiles.isEmpty()) {
            showToast(getString(R.string.server_config_missing))
            finish()
            return
        }

        initializePlayer()
        observeViewModel()

        viewModel.init(serverId, currentIndex, videoFiles)
    }

    private fun initializePlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 5_000, 10_000)
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build().also { exoPlayer ->
            binding.playerView.player = exoPlayer

            binding.playerView.setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    isControllerVisible = visibility == View.VISIBLE
                }
            )

            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> binding.progressBar.visibility = View.VISIBLE
                        Player.STATE_READY -> {
                            binding.progressBar.visibility = View.GONE
                            binding.layoutError.visibility = View.GONE
                        }
                        Player.STATE_ENDED -> viewModel.playNext(autoPlay = true)
                        else -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    binding.progressBar.visibility = View.GONE
                    val currentFile = viewModel.videoFiles.value.getOrNull(viewModel.currentIndex.value)
                    BuglyLog.e("VideoPlayer", "播放失败: ${currentFile?.name}, error=${error.message}")
                    showError(getString(R.string.video_playback_error, error.message ?: "未知错误"))
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    viewModel.updatePlayingState(isPlaying)
                }
            })
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.pendingPlayIndex.collect { index ->
                        if (index != null) {
                            playVideo(index)
                            viewModel.clearPendingPlay()
                        }
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is VideoPlayerViewModel.PlayerEvent.Error -> {
                                showToast(event.message)
                                finish()
                            }
                            is VideoPlayerViewModel.PlayerEvent.ShowToast -> showToast(event.message)
                            VideoPlayerViewModel.PlayerEvent.RequestSavePosition -> saveCurrentPosition()
                            is VideoPlayerViewModel.PlayerEvent.PlayVideo -> playVideo(event.index)
                        }
                    }
                }
            }
        }
    }

    private fun playVideo(index: Int) {
        val file = viewModel.videoFiles.value.getOrNull(index) ?: return
        val exoPlayer = player ?: return

        saveCurrentPosition()
        viewModel.onVideoStarted(index)

        binding.tvFileName.text = file.name
        binding.tvIndex.text = getString(R.string.video_index, index + 1, viewModel.videoFiles.value.size)
        binding.layoutError.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE

        val smbPath = file.path.replace("\\", "/")
        val uri = Uri.parse("smb://${viewModel.host}/${viewModel.share}/$smbPath")
        val mimeType = MimeTypeUtil.getVideoMimeType(file.getExtension())

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType)
            .build()

        val mediaSource = ProgressiveMediaSource.Factory(SmbDataSourceFactory())
            .createMediaSource(mediaItem)

        exoPlayer.setMediaSource(mediaSource)

        val savedPosition = viewModel.getSavedPosition(file)
        if (savedPosition > VideoPlayerViewModel.MIN_RESUME_MS) {
            exoPlayer.seekTo(savedPosition)
            showToast(getString(R.string.resume_playback, formatTime(savedPosition)))
        }

        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun saveCurrentPosition() {
        player?.let {
            viewModel.savePosition(it.currentPosition, it.duration)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    private fun showError(message: String) {
        binding.layoutError.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun retryPlayback() {
        playVideo(viewModel.currentIndex.value)
    }

    private fun scheduleHideInfoBars() {
        hideInfoJob?.cancel()
        hideInfoJob = lifecycleScope.launch {
            delay(5000)
            if (player?.isPlaying == true) {
                binding.bottomBar.visibility = View.GONE
                binding.tvHint.visibility = View.GONE
            }
        }
    }

    private fun showInfoBars() {
        binding.bottomBar.visibility = View.VISIBLE
        binding.tvHint.visibility = View.VISIBLE
        scheduleHideInfoBars()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isControllerVisible) false else { viewModel.playPrevious(); true }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isControllerVisible) false else { viewModel.playNext(); true }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (binding.layoutError.visibility == View.VISIBLE) {
                    retryPlayback()
                    return true
                }
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                    showInfoBars()
                }
                false
            }
            KeyEvent.KEYCODE_MENU -> { showInfoBars(); true }
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showInfoBars(); false }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
        viewModel.updatePlayingState(player?.isPlaying == true)
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
        player?.playWhenReady = false
        viewModel.updatePlayingState(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentPosition()
        player?.release()
        player = null
    }
}
