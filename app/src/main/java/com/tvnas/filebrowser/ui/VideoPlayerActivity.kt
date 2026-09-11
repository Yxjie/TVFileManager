package com.tvnas.filebrowser.ui

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.tvnas.filebrowser.R
import com.tvnas.filebrowser.databinding.ActivityVideoPlayerBinding
import com.tvnas.filebrowser.media.MimeTypeUtil
import com.tvnas.filebrowser.media.PlayPositionStore
import com.tvnas.filebrowser.media.SmbDataSourceFactory
import com.tvnas.filebrowser.smb.ServerStorage
import com.tvnas.filebrowser.smb.SmbFile
import com.tvnas.filebrowser.util.FullscreenHelper
import com.tencent.bugly.crashreport.BuglyLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 视频播放器 - 全屏播放，从 SMB 共享流式读取
 *
 * 功能：
 * - ExoPlayer 流式播放（通过自定义 SmbDataSource）
 * - D-pad 左右键切换视频（控制器隐藏时）/ seek（控制器可见时）
 * - OK 键切换播放/暂停 & 显示/隐藏控制器
 * - 自动播放下一个视频
 * - 中文错误提示
 */
class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        private const val MIN_RESUME_MS = 10_000L    // 播放不足 10 秒不记录续播位置
        private const val END_MARGIN_MS = 15_000L    // 距结尾 15 秒内视为已看完，清除记录
        private const val SAVE_INTERVAL_MS = 10_000L // 播放中定时保存间隔
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null

    private var videoFiles: List<SmbFile> = emptyList()
    private var currentIndex: Int = 0
    private var server: String = ""
    private var share: String = ""

    // 用于判断 D-pad 左右键是切换视频还是 seek
    private var isControllerVisible: Boolean = false

    // 自动隐藏信息栏的延时任务；重新计时前取消上一个，避免多个延时叠加
    private var hideInfoJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFullscreen()

        // 从 serverId 加载服务器配置
        val serverId = intent.getStringExtra("serverId")
        if (serverId != null) {
            val serverConfig = ServerStorage(this).getServerById(serverId)
            if (serverConfig == null) {
                showToast(getString(R.string.server_config_missing))
                finish()
                return
            }
            server = serverConfig.serverAddress
            share = serverConfig.shareName
        } else {
            showToast(getString(R.string.server_config_missing))
            finish()
            return
        }

        currentIndex = intent.getIntExtra("currentIndex", 0)

        // 视频列表由 BrowserActivity 以 ParcelableArrayList 传入
        @Suppress("DEPRECATION")
        val parcelableList = intent.getParcelableArrayListExtra<SmbFile>("videoFiles")
        videoFiles = parcelableList ?: emptyList()

        if (videoFiles.isEmpty()) {
            showToast(getString(R.string.video_not_found))
            finish()
            return
        }

        initializePlayer()
        playVideo(currentIndex)
        startPeriodicPositionSave()
    }

    /**
     * 播放中定时保存进度，防止电视断电/崩溃导致进度丢失
     */
    private fun startPeriodicPositionSave() {
        lifecycleScope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                if (player?.isPlaying == true) {
                    saveCurrentPosition()
                }
            }
        }
    }

    /**
     * 使用现代 API 设置全屏沉浸模式
     */
    private fun setupFullscreen() {
        FullscreenHelper.setup(this)
    }

    /**
     * 初始化 ExoPlayer
     */
    private fun initializePlayer() {
        // 加大缓冲以适应电视 WiFi 带宽波动
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,   // minBufferMs
                120_000,  // maxBufferMs
                5_000,    // bufferForPlaybackMs
                10_000    // bufferForPlaybackAfterRebufferMs
            ).build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build().also { exoPlayer ->
            binding.playerView.player = exoPlayer

            // 监听控制器显示/隐藏状态
            binding.playerView.setControllerVisibilityListener(
                androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
                    isControllerVisible = visibility == View.VISIBLE
                }
            )

            // 监听播放状态
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.layoutError.visibility = View.GONE
                        }
                        Player.STATE_READY -> {
                            binding.progressBar.visibility = View.GONE
                            binding.layoutError.visibility = View.GONE
                        }
                        Player.STATE_ENDED -> {
                            // 自动播放下一个；播到最后一个时循环回第一个
                            playNext(autoPlay = true)
                        }
                        Player.STATE_IDLE -> {
                            // 不做处理
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    binding.progressBar.visibility = View.GONE

                    // 记录视频播放错误到 Bugly。
                    // 播放失败多是网络/码流/格式问题（业务失败），不是代码崩溃，
                    // 用 BuglyLog.e 记录而非 postCatchedException，避免污染崩溃统计。
                    val currentFile = videoFiles.getOrNull(currentIndex)
                    BuglyLog.e("VideoPlayer", "播放失败: ${currentFile?.name ?: "未知"}, 格式: ${currentFile?.getExtension() ?: "未知"}, 路径: smb://$server/$share/${currentFile?.path ?: "未知"}, error=${error.message}")

                    showError(getString(R.string.video_playback_error, error.message ?: "未知错误"))
                }
            })
        }
    }

    /**
     * 播放指定索引的视频
     */
    private fun playVideo(index: Int) {
        if (index < 0 || index >= videoFiles.size) return

        val exoPlayer = player ?: return
        // 先保存上一个视频的播放进度（此时 currentIndex 仍指向它）
        saveCurrentPosition()
        currentIndex = index
        val file = videoFiles[index]

        // 更新底部信息栏
        binding.tvFileName.text = file.name
        binding.tvIndex.text = getString(R.string.video_index, index + 1, videoFiles.size)

        // 隐藏错误提示
        binding.layoutError.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE

        // 构建 SMB URI 用于 SmbDataSource 解析路径
        // SmbDataSource 会从 URI path 中提取远程文件路径
        val smbPath = file.path.replace("\\", "/")
        val uri = Uri.parse("smb://$server/$share/$smbPath")

        val mimeType = MimeTypeUtil.getVideoMimeType(file.getExtension())

        // 通过 MediaItem 设置 MIME 类型，让 ExoPlayer 正确解析视频格式
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mimeType)
            .build()

        val mediaSource = ProgressiveMediaSource.Factory(SmbDataSourceFactory())
            .createMediaSource(mediaItem)

        exoPlayer.setMediaSource(mediaSource)

        // 断点续播：恢复之前记录的播放位置
        val savedPosition = PlayPositionStore.get(this, server, share, file)
        if (savedPosition > MIN_RESUME_MS) {
            exoPlayer.seekTo(savedPosition)
            showToast(getString(R.string.resume_playback, formatTime(savedPosition)))
        }

        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    /**
     * 保存当前视频的播放位置；已看完（接近结尾）则清除记录
     */
    private fun saveCurrentPosition() {
        val exoPlayer = player ?: return
        val file = videoFiles.getOrNull(currentIndex) ?: return
        val position = exoPlayer.currentPosition
        val duration = exoPlayer.duration
        if (duration > 0 && position >= duration - END_MARGIN_MS) {
            PlayPositionStore.clear(this, server, share, file)
        } else if (position > MIN_RESUME_MS) {
            PlayPositionStore.save(this, server, share, file, position)
        }
    }

    /**
     * 毫秒转 mm:ss / h:mm:ss
     */
    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    /**
     * 显示错误信息
     */
    private fun showError(message: String) {
        binding.layoutError.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    /** 重试当前视频：错误布局可见时按 OK 触发，避免只能退出重进 */
    private fun retryPlayback() {
        playVideo(currentIndex)
    }

    /**
     * 播放下一个视频
     *
     * @param autoPlay 是否由播放结束自动触发。为 true 且已到最后一个视频时，
     *                 循环回第一个视频（连续播放列表不中断）；仅手动切到最后一个
     *                 时提示已是最后一个。
     */
    private fun playNext(autoPlay: Boolean = false) {
        if (currentIndex < videoFiles.size - 1) {
            playVideo(currentIndex + 1)
        } else if (autoPlay && videoFiles.size > 1) {
            // 自动播放到末尾：循环回第一个，避免停在 ENDED 状态黑屏
            playVideo(0)
        } else if (!autoPlay) {
            showToast(getString(R.string.last_video))
        }
    }

    /**
     * 播放上一个视频
     */
    private fun playPrevious() {
        if (currentIndex > 0) {
            playVideo(currentIndex - 1)
        } else {
            showToast(getString(R.string.first_video))
        }
    }

    /**
     * 隐藏信息栏（播放时自动隐藏，提升沉浸感）
     */
    private fun scheduleHideInfoBars() {
        // 取消上一个延时任务再启新的：否则每次按 OK/上下键都叠加一个 5s 协程，
        // 最早那个仍会按原计划隐藏信息栏
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
            // D-pad 左右键：控制器可见时 seek，隐藏时切换视频
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isControllerVisible) {
                    // 让 ExoPlayer 控制器处理 seek
                    false
                } else {
                    playPrevious()
                    true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isControllerVisible) {
                    // 让 ExoPlayer 控制器处理 seek
                    false
                } else {
                    playNext()
                    true
                }
            }
            // OK 键：错误布局可见时重试当前视频；否则切换播放/暂停
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (binding.layoutError.visibility == View.VISIBLE) {
                    retryPlayback()
                    return true
                }
                player?.let {
                    if (it.isPlaying) {
                        it.pause()
                    } else {
                        it.play()
                    }
                    showInfoBars()
                }
                // 也传递给 ExoPlayer 控制器以显示/隐藏控制条
                false
            }
            // 菜单键：显示信息栏
            KeyEvent.KEYCODE_MENU -> {
                showInfoBars()
                true
            }
            // 返回键：退出播放器
            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            // D-pad 上下键：显示信息栏
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                showInfoBars()
                false
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentPosition()
        player?.release()
        player = null
    }
}
