package com.tvnas.filebrowser.ui

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.tvnas.filebrowser.R
import com.tvnas.filebrowser.databinding.ActivityVideoPlayerBinding
import com.tvnas.filebrowser.media.MimeTypeUtil
import com.tvnas.filebrowser.media.SmbDataSourceFactory
import com.tvnas.filebrowser.smb.ServerStorage
import com.tvnas.filebrowser.smb.SmbFile
import com.tencent.bugly.crashreport.BuglyLog
import com.tencent.bugly.crashreport.CrashReport
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

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null

    private var videoFiles: List<SmbFile> = emptyList()
    private var currentIndex: Int = 0
    private var server: String = ""
    private var share: String = ""

    // 用于判断 D-pad 左右键是切换视频还是 seek
    private var isControllerVisible: Boolean = false

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

        val videoListJson = intent.getStringArrayListExtra("videoFiles")
        if (videoListJson != null) {
            videoFiles = videoListJson.map { json ->
                val parts = json.split("|")
                SmbFile(
                    name = parts.getOrElse(0) { "" },
                    path = parts.getOrElse(1) { "" },
                    isDirectory = false,
                    size = parts.getOrElse(2) { "0" }.toLongOrNull() ?: 0,
                    lastModified = parts.getOrElse(3) { "0" }.toLongOrNull() ?: 0
                )
            }
        }

        if (videoFiles.isEmpty()) {
            showToast(getString(R.string.video_not_found))
            finish()
            return
        }

        initializePlayer()
        playVideo(currentIndex)
    }

    /**
     * 使用现代 API 设置全屏沉浸模式
     */
    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
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
                            // 自动播放下一个视频
                            playNext(autoPlay = true)
                        }
                        Player.STATE_IDLE -> {
                            // 不做处理
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    binding.progressBar.visibility = View.GONE

                    // 上报视频播放错误到 Bugly
                    val currentFile = videoFiles.getOrNull(currentIndex)
                    BuglyLog.e("VideoPlayer", "播放失败: ${currentFile?.name ?: "未知"}, 格式: ${currentFile?.getExtension() ?: "未知"}, 路径: smb://$server/$share/${currentFile?.path ?: "未知"}")
                    CrashReport.postCatchedException(error)

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
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    /**
     * 显示错误信息
     */
    private fun showError(message: String) {
        binding.layoutError.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    /**
     * 播放下一个视频
     */
    private fun playNext(autoPlay: Boolean = false) {
        if (currentIndex < videoFiles.size - 1) {
            playVideo(currentIndex + 1)
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
        lifecycleScope.launch {
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
            // OK 键：切换播放/暂停
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
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
        player?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
