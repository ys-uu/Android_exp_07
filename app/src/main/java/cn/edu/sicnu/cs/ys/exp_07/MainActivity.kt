package cn.edu.sicnu.cs.ys.exp_07

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.ContentUris
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: View
    private lateinit var tvPermissionStatus: TextView
    private lateinit var listView: ListView
    private lateinit var tvSongTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var btnPrevious: Button
    private lateinit var btnPlayPause: Button
    private lateinit var btnStop: Button
    private lateinit var btnNext: Button

    private val songs = mutableListOf<AudioItem>()
    private lateinit var songAdapter: SongAdapter

    @Volatile
    private var musicService: MusicService? = null
    private var isBound = false

    @Volatile
    private var isRefreshing = false
    private var progressThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false

    private var pendingPlayIndex = -1
    private var lastSyncedIndex = -1
    private var lastSyncedTitle: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val audioGranted = result[audioPermission()] == true || hasAudioPermission()
        if (audioGranted) {
            loadMusicAndBindService()
        } else {
            showPermissionRequired()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            musicService?.setPlaylist(songs)
            listView.isEnabled = true

            val pendingIndex = pendingPlayIndex
            if (pendingIndex in songs.indices) {
                musicService?.playIndex(pendingIndex)
                listView.setItemChecked(pendingIndex, true)
                pendingPlayIndex = -1
            }
            updateProgressFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        initViews()
        initListViewAndListeners()
        applyWindowInsets()

        if (hasAudioPermission()) {
            loadMusicAndBindService()
        } else {
            requestAudioPermissions()
        }
    }

    private fun initViews() {
        rootLayout = findViewById(R.id.main)
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        listView = findViewById(R.id.lvSongs)
        tvSongTitle = findViewById(R.id.tvSongTitle)
        tvArtist = findViewById(R.id.tvArtist)
        seekBar = findViewById(R.id.sbProgress)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnStop = findViewById(R.id.btnStop)
        btnNext = findViewById(R.id.btnNext)
    }

    private fun initListViewAndListeners() {
        songAdapter = SongAdapter(this, songs)
        listView.adapter = songAdapter
        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            playSongAt(position)
        }

        btnPlayPause.setOnClickListener {
            ensureServiceStartedAndBound()
            val service = musicService
            if (service == null) {
                if (songs.isNotEmpty()) pendingPlayIndex = 0
                return@setOnClickListener
            }

            if (service.getCurrentIndex() < 0 && songs.isNotEmpty()) {
                playSongAt(0)
            } else {
                service.playOrPause()
                updateProgressFromService()
            }
        }

        btnPrevious.setOnClickListener {
            ensureServiceStartedAndBound()
            musicService?.previous()
            mainHandler.postDelayed({ updateProgressFromService() }, 150)
        }

        btnNext.setOnClickListener {
            ensureServiceStartedAndBound()
            musicService?.next()
            mainHandler.postDelayed({ updateProgressFromService() }, 150)
        }

        btnStop.setOnClickListener {
            musicService?.stopMusic()
            seekBar.progress = 0
            tvCurrentTime.text = getString(R.string.time_zero)
            tvTotalTime.text = getString(R.string.time_zero)
            tvSongTitle.text = getString(R.string.not_selected_song)
            tvArtist.text = getString(R.string.select_from_list_tip)
            listView.clearChoices()
            songAdapter.notifyDataSetChanged()
            lastSyncedIndex = -1
            lastSyncedTitle = null
            btnPlayPause.text = getString(R.string.play)
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCurrentTime.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                val targetPosition = seekBar?.progress ?: 0
                musicService?.seekTo(targetPosition)
                mainHandler.postDelayed({ updateProgressFromService() }, 100)
            }
        })

        tvPermissionStatus.setOnClickListener {
            if (hasAudioPermission()) {
                loadMusicAndBindService()
            } else {
                requestAudioPermissions()
            }
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val basePadding = dpToPx(16)
            view.setPadding(
                systemBars.left + basePadding,
                systemBars.top + basePadding,
                systemBars.right + basePadding,
                systemBars.bottom + basePadding
            )
            insets
        }
    }

    private fun loadMusicAndBindService() {
        ensureServiceStartedAndBound()

        if (songs.isEmpty()) {
            tvPermissionStatus.visibility = View.VISIBLE
            tvPermissionStatus.text = getString(R.string.loading_music)
        }

        // 媒体库查询属于 IO 操作，放在子线程中执行。
        Thread({
            val loadedSongs = queryAudioFiles()
            mainHandler.post {
                songs.clear()
                songs.addAll(loadedSongs)
                songAdapter.notifyDataSetChanged()
                musicService?.setPlaylist(songs)
                updateProgressFromService()

                if (songs.isEmpty()) {
                    tvPermissionStatus.visibility = View.VISIBLE
                    tvPermissionStatus.text = getString(R.string.no_music_found)
                } else {
                    tvPermissionStatus.visibility = View.GONE
                }
            }
        }, "LoadMusicThread").start()
    }

    private fun ensureServiceStartedAndBound() {
        startProgressRefreshThread()
        if (isBound) return

        val intent = Intent(this, MusicService::class.java)
        try {
            // 同时 start 与 bind：start 保证退出 Activity 后音乐可继续播放，bind 用于获取 Binder。
            ContextCompat.startForegroundService(this, intent)
            isBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            tvPermissionStatus.visibility = View.VISIBLE
            tvPermissionStatus.text = getString(
                R.string.service_start_failed,
                e.message ?: getString(R.string.unknown_error)
            )
        }
    }

    private fun startProgressRefreshThread() {
        if (progressThread?.isAlive == true) return

        isRefreshing = true
        progressThread = Thread({
            while (isRefreshing) {
                // 子线程定时通过 Binder 读取播放状态，再切回主线程刷新 SeekBar 和文字。
                mainHandler.post {
                    if (!isDestroyed) updateProgressFromService()
                }
                try {
                    Thread.sleep(PROGRESS_REFRESH_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "SeekBar-Refresh-Thread").apply {
            start()
        }
    }

    private fun updateProgressFromService() {
        val service = musicService ?: return
        val duration = service.getDuration()
        val position = service.getCurrentPosition()

        if (!isUserSeeking) {
            val maxProgress = duration.coerceAtLeast(1)
            seekBar.max = maxProgress
            seekBar.progress = position.coerceIn(0, maxProgress)
            tvCurrentTime.text = formatTime(position)
        }
        tvTotalTime.text = formatTime(duration)
        btnPlayPause.text = if (service.isPlaying()) getString(R.string.pause) else getString(R.string.play)

        val index = service.getCurrentIndex()
        val title = service.getCurrentTitle()
        if (index != lastSyncedIndex || title != lastSyncedTitle) {
            lastSyncedIndex = index
            lastSyncedTitle = title

            tvSongTitle.text = title ?: getString(R.string.not_selected_song)
            tvArtist.text = service.getCurrentArtist()
                ?.takeIf { it.isNotBlank() }
                ?: getString(R.string.select_from_list_tip)

            if (index in songs.indices) {
                listView.setItemChecked(index, true)
            } else {
                listView.clearChoices()
            }
        }
    }

    private fun playSongAt(index: Int) {
        if (index !in songs.indices) return

        ensureServiceStartedAndBound()
        val service = musicService
        if (service == null) {
            // 服务绑定是异步的，连接成功后补播用户刚才点击的歌曲。
            pendingPlayIndex = index
            return
        }

        service.setPlaylist(songs)
        service.playIndex(index)
        listView.setItemChecked(index, true)
        mainHandler.postDelayed({ updateProgressFromService() }, 150)
    }

    @SuppressLint("InlinedApi")
    private fun queryAudioFiles(): List<AudioItem> {
        val result = ArrayList<AudioItem>()
        var cursor: Cursor? = null

        try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1"
            val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

            cursor = contentResolver.query(
                collection,
                projection,
                selection,
                null,
                sortOrder
            ) ?: return emptyList()

            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn)
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.unknown_song)
                val durationMs = if (durationColumn >= 0 && !cursor.isNull(durationColumn)) {
                    cursor.getInt(durationColumn)
                } else {
                    0
                }
                val contentUri = ContentUris.withAppendedId(collection, id)

                result += AudioItem(
                    id = id,
                    title = title,
                    artist = "",
                    uri = contentUri,
                    durationMs = durationMs
                )
            }
        } catch (_: Exception) {
            return emptyList()
        } finally {
            cursor?.close()
        }

        return result
    }

    private fun requestAudioPermissions() {
        val permissions = mutableListOf(audioPermission())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            audioPermission()
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun audioPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun showPermissionRequired() {
        tvPermissionStatus.visibility = View.VISIBLE
        tvPermissionStatus.text = getString(R.string.permission_required)
    }

    private fun formatTime(timeMs: Int): String {
        if (timeMs <= 0) return getString(R.string.time_zero)
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        isRefreshing = false
        progressThread?.interrupt()
        progressThread = null
        mainHandler.removeCallbacksAndMessages(null)

        val shouldKeepStartedService = musicService?.hasActiveSession() == true
        if (!shouldKeepStartedService) {
            try {
                stopService(Intent(this, MusicService::class.java))
            } catch (_: Exception) {
                // 页面销毁时服务可能尚未完全启动，忽略停止失败。
            }
        }

        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: IllegalArgumentException) {
                // 服务可能已经断开，忽略重复解绑。
            }
            isBound = false
        }
        super.onDestroy()
    }

    private inner class SongAdapter(
        context: Context,
        items: List<AudioItem>
    ) : ArrayAdapter<AudioItem>(
        context,
        android.R.layout.simple_list_item_activated_2,
        android.R.id.text1,
        items
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = getItem(position)
            val itemView = super.getView(position, convertView, parent)
            itemView.findViewById<TextView>(android.R.id.text1).text = item?.title
            itemView.findViewById<TextView>(android.R.id.text2).text = item?.artist
            return itemView
        }
    }

    companion object {
        private const val PROGRESS_REFRESH_INTERVAL_MS = 400L
    }
}
