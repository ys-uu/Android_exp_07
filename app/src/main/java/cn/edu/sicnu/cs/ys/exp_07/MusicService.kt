package cn.edu.sicnu.cs.ys.exp_07

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 一首本机音频的简要信息。
 */
data class AudioItem(
    val id: Long,
    val title: String,
    val artist: String,
    val uri: Uri,
    val durationMs: Int
)

/**
 * 音乐播放服务。
 *
 * 所有真正与 MediaPlayer 和播放状态有关的操作都放在这里：
 * Activity 通过 [MusicBinder.getService] 拿到服务实例后调用播放控制方法和状态读取方法。
 */
class MusicService : Service() {

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    private val binder = MusicBinder()
    private var player: MediaPlayer? = null
    private var playlist: List<AudioItem> = emptyList()
    private var currentIndex: Int = -1
    private var currentAudio: AudioItem? = null
    private var prepared: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // startForegroundService 启动后应尽快进入前台，避免系统抛出 ForegroundServiceDidNotStartInTimeException。
        showNotification(getString(R.string.app_name), getString(R.string.service_started), false)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 预留通知动作/外部 Intent 控制入口；当前界面主要通过 Binder 控制。
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> playOrPause()
            ACTION_PREVIOUS -> previous()
            ACTION_NEXT -> next()
            ACTION_STOP -> stopMusic()
            else -> showNotification(
                currentAudio?.title ?: getString(R.string.app_name),
                currentAudio?.artist ?: getString(R.string.service_running),
                isPlaying()
            )
        }
        return START_NOT_STICKY
    }

    fun setPlaylist(items: List<AudioItem>) {
        playlist = items
        if (currentIndex !in playlist.indices) {
            currentIndex = -1
        }
    }

    fun playIndex(index: Int) {
        if (index !in playlist.indices) return

        val item = playlist[index]
        currentIndex = index
        currentAudio = item
        prepared = false
        showNotification(item.title, getString(R.string.loading_song, item.artist), true)

        val mediaPlayer = player ?: createPlayer().also { player = it }

        try {
            mediaPlayer.reset()
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mediaPlayer.setDataSource(applicationContext, item.uri)
            mediaPlayer.setOnPreparedListener { mp ->
                prepared = true
                mp.start()
                showNotification(item.title, item.artist, true)
            }
            mediaPlayer.setOnCompletionListener {
                // 一首播放完成后自动切换下一首。
                next()
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                prepared = false
                showNotification(item.title, getString(R.string.playback_failed, what, extra), false)
                try {
                    player?.reset()
                } catch (_: Exception) {
                    // 错误状态下 reset 失败时直接释放，下次播放会重新创建。
                    releasePlayer()
                }
                true
            }
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            prepared = false
            val errorMessage = e.message ?: getString(R.string.unknown_error)
            showNotification(item.title, getString(R.string.cannot_play_song, errorMessage), false)
            releasePlayer()
        }
    }

    fun playOrPause() {
        if (isPlaying()) {
            pause()
        } else {
            resume()
        }
    }

    fun pause() {
        val mediaPlayer = player ?: return
        try {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
                currentAudio?.let {
                    showNotification(it.title, getString(R.string.paused_status, it.artist), false)
                }
            }
        } catch (_: IllegalStateException) {
            // MediaPlayer 尚未 prepared 时忽略暂停操作。
        }
    }

    fun resume() {
        val mediaPlayer = player ?: return
        try {
            if (!mediaPlayer.isPlaying && prepared) {
                mediaPlayer.start()
                currentAudio?.let { showNotification(it.title, it.artist, true) }
            }
        } catch (_: IllegalStateException) {
            // 状态尚未准备完成，忽略本次恢复操作。
        }
    }

    fun stopMusic() {
        try {
            player?.let {
                if (it.isPlaying) it.stop()
            }
        } catch (_: Exception) {
            // 停止时若播放器状态异常，继续执行资源释放。
        }
        releasePlayer()
        currentIndex = -1
        currentAudio = null
        prepared = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun next() {
        if (playlist.isEmpty()) return
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % playlist.size
        playIndex(nextIndex)
    }

    fun previous() {
        if (playlist.isEmpty()) return
        val previousIndex = if (currentIndex <= 0) playlist.lastIndex else currentIndex - 1
        playIndex(previousIndex)
    }

    fun seekTo(positionMs: Int) {
        val mediaPlayer = player ?: return
        if (!prepared) return
        try {
            mediaPlayer.seekTo(positionMs.coerceAtLeast(0))
        } catch (_: IllegalStateException) {
            // 播放器尚未准备完成时不能拖动进度。
        }
    }

    fun isPlaying(): Boolean {
        return try {
            player?.isPlaying == true
        } catch (_: IllegalStateException) {
            false
        }
    }

    fun getCurrentPosition(): Int {
        return try {
            player?.currentPosition ?: 0
        } catch (_: IllegalStateException) {
            0
        }
    }

    fun getDuration(): Int {
        return try {
            player?.duration?.takeIf { it > 0 }
                ?: currentAudio?.durationMs?.takeIf { it > 0 }
                ?: 0
        } catch (_: IllegalStateException) {
            currentAudio?.durationMs ?: 0
        }
    }

    fun getCurrentIndex(): Int = currentIndex

    fun hasActiveSession(): Boolean = player != null

    fun getCurrentTitle(): String? = currentAudio?.title

    fun getCurrentArtist(): String? = currentAudio?.artist

    override fun onDestroy() {
        releasePlayer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createPlayer(): MediaPlayer {
        return MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
        }
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (_: Exception) {
            // 释放失败不影响服务销毁。
        }
        player = null
        prepared = false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun showNotification(title: String, text: String, playing: Boolean) {
        val notification = buildNotification(title, text, playing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String, text: String, playing: Boolean): Notification {
        val openPlayerIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_ACTIVITY,
            openPlayerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentPendingIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "music_playback_channel"
        private const val NOTIFICATION_ID = 12
        private const val REQUEST_OPEN_ACTIVITY = 100

        const val ACTION_PLAY_PAUSE = "cn.edu.sicnu.cs.ys.exp_07.PLAY_PAUSE"
        const val ACTION_PREVIOUS = "cn.edu.sicnu.cs.ys.exp_07.PREVIOUS"
        const val ACTION_NEXT = "cn.edu.sicnu.cs.ys.exp_07.NEXT"
        const val ACTION_STOP = "cn.edu.sicnu.cs.ys.exp_07.STOP"
    }
}
