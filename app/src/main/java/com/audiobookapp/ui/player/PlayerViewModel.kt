package com.audiobookapp.ui.player

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.audiobookapp.data.db.AppDatabase
import com.audiobookapp.data.model.AudioBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class ExportState {
    object Idle : ExportState()
    object Exporting : ExportState()
    data class Done(val savedPath: String) : ExportState()
    data class ReadyToShare(val uri: Uri, val mimeType: String) : ExportState()
    data class Error(val message: String) : ExportState()
}


    object Idle : PlayerStatus()
    object Loading : PlayerStatus()
    object Ready : PlayerStatus()
    // File is being converted — user can still play the portion written so far
    data class StreamingConversion(val conversionProgress: Int) : PlayerStatus()
    data class Error(val message: String) : PlayerStatus()
}

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)

    val player: ExoPlayer = ExoPlayer.Builder(application).build()

    private val _book = MutableLiveData<AudioBook?>()
    val book: LiveData<AudioBook?> = _book

    private val _isPlaying = MutableLiveData(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _currentPositionMs = MutableLiveData(0L)
    val currentPositionMs: LiveData<Long> = _currentPositionMs

    private val _durationMs = MutableLiveData(0L)
    val durationMs: LiveData<Long> = _durationMs

    private val _status = MutableLiveData<PlayerStatus>(PlayerStatus.Idle)
    val status: LiveData<PlayerStatus> = _status

    private var positionJob: Job? = null
    private var bookId: Int = -1
    private var conversionPollingJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.postValue(isPlaying)
                if (isPlaying) startPositionUpdates() else stopPositionUpdates()
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        _durationMs.postValue(player.duration.coerceAtLeast(0L))
                        _status.postValue(
                            if (_book.value?.isConversionComplete == false)
                                PlayerStatus.StreamingConversion(_book.value?.conversionProgress ?: 0)
                            else
                                PlayerStatus.Ready
                        )
                    }
                    Player.STATE_BUFFERING -> _status.postValue(PlayerStatus.Loading)
                    Player.STATE_ENDED -> {
                        _isPlaying.postValue(false)
                        stopPositionUpdates()
                    }
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _status.postValue(PlayerStatus.Error("Playback error: ${error.message}"))
            }
        })
    }

    fun loadBook(id: Int) {
        bookId = id
        _status.postValue(PlayerStatus.Loading)

        viewModelScope.launch {
            val audioBook = db.audioBookDao().getBookById(id) ?: run {
                _status.postValue(PlayerStatus.Error("Book not found"))
                return@launch
            }
            _book.postValue(audioBook)

            val file = File(audioBook.mp3FilePath)

            when {
                // Conversion not started yet or file doesn't exist
                !file.exists() || file.length() == 0L -> {
                    if (audioBook.conversionError != null) {
                        _status.postValue(PlayerStatus.Error(audioBook.conversionError))
                    } else {
                        _status.postValue(PlayerStatus.Error("Audio file not ready yet. Please wait for conversion to start."))
                    }
                }

                // File exists and is playable (>= 10% written) — start player even if still converting
                audioBook.isPlayable || audioBook.isConversionComplete -> {
                    loadIntoPlayer(file, audioBook)
                    if (!audioBook.isConversionComplete) {
                        startConversionPolling()  // poll DB for progress updates
                    }
                }

                // File exists but not enough written yet — wait
                else -> {
                    _status.postValue(PlayerStatus.StreamingConversion(audioBook.conversionProgress))
                    startConversionPolling()
                }
            }
        }
    }

    private fun loadIntoPlayer(file: File, audioBook: AudioBook) {
        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        player.setMediaItem(mediaItem)
        player.prepare()
        // Resume from saved position
        if (audioBook.lastPositionMs > 0) {
            player.seekTo(audioBook.lastPositionMs)
        }
        _currentPositionMs.postValue(audioBook.lastPositionMs)
    }

    /**
     * Poll the DB every 2 seconds while conversion is in progress.
     * When isPlayable flips true, load the player automatically.
     * Updates the streaming progress banner.
     */
    private fun startConversionPolling() {
        conversionPollingJob?.cancel()
        conversionPollingJob = viewModelScope.launch {
            while (isActive) {
                delay(2000L)
                val latest = db.audioBookDao().getBookById(bookId) ?: break
                _book.postValue(latest)

                when {
                    latest.conversionError != null -> {
                        _status.postValue(PlayerStatus.Error(latest.conversionError))
                        break
                    }
                    latest.isConversionComplete -> {
                        // Reload player with final file for accurate duration
                        val file = File(latest.mp3FilePath)
                        if (file.exists()) {
                            val wasPlaying = player.isPlaying
                            val savedPos = player.currentPosition
                            loadIntoPlayer(file, latest.copy(lastPositionMs = savedPos))
                            if (wasPlaying) player.play()
                        }
                        _status.postValue(PlayerStatus.Ready)
                        _durationMs.postValue(latest.durationMs)
                        break
                    }
                    latest.isPlayable && player.playbackState == Player.STATE_IDLE -> {
                        // Now enough audio is written — start playback
                        loadIntoPlayer(File(latest.mp3FilePath), latest)
                        _status.postValue(PlayerStatus.StreamingConversion(latest.conversionProgress))
                    }
                    latest.isPlayable -> {
                        // Already playing, just update the streaming progress banner
                        _status.postValue(PlayerStatus.StreamingConversion(latest.conversionProgress))
                    }
                    else -> {
                        _status.postValue(PlayerStatus.StreamingConversion(latest.conversionProgress))
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _currentPositionMs.postValue(positionMs)
        savePosition(positionMs)
    }

    fun skipForward(ms: Long = 30_000L) {
        val newPos = (player.currentPosition + ms).coerceAtMost(
            player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        player.seekTo(newPos)
    }

    fun skipBack(ms: Long = 15_000L) {
        val newPos = (player.currentPosition - ms).coerceAtLeast(0L)
        player.seekTo(newPos)
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (isActive) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                _currentPositionMs.postValue(pos)
                savePosition(pos)
                delay(1000L)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        savePosition(player.currentPosition.coerceAtLeast(0L))
    }

    private fun savePosition(posMs: Long) {
        if (bookId == -1) return
        viewModelScope.launch {
            db.audioBookDao().updateLastPosition(bookId, posMs)
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    private val _exportState = MutableLiveData<ExportState>(ExportState.Idle)
    val exportState: LiveData<ExportState> = _exportState

    /**
     * Copy the audio file to Music/Readio/ on the public external storage.
     * Uses MediaStore on API 29+ (no WRITE_EXTERNAL_STORAGE permission needed).
     * Falls back to direct file copy on API 26–28.
     *
     * The exported file is immediately visible in the phone's file manager and
     * any audio player that scans MediaStore (VLC, Spotify, system Music app, etc.).
     */
    fun exportToDevice() {
        val book = _book.value ?: run {
            _exportState.value = ExportState.Error("No book loaded")
            return
        }
        val sourceFile = File(book.mp3FilePath)
        if (!sourceFile.exists()) {
            _exportState.value = ExportState.Error("Audio file not found on device")
            return
        }

        _exportState.value = ExportState.Exporting

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val safeTitle = book.title
                    .replace(Regex("[^a-zA-Z0-9\\s\\-_]"), "")
                    .trim()
                    .take(80)
                    .ifBlank { "audiobook" }
                val outFileName = "$safeTitle.wav"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // API 29+ — use MediaStore (no storage permission needed)
                    val resolver = getApplication<Application>().contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, outFileName)
                        put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                        put(MediaStore.Audio.Media.RELATIVE_PATH,
                            "${Environment.DIRECTORY_MUSIC}/Readio")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw Exception("MediaStore insert failed")

                    resolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { it.copyTo(out) }
                    }

                    // Mark as complete so other apps can see it
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)

                    _exportState.postValue(
                        ExportState.Done("Music/Readio/$outFileName"))

                } else {
                    // API 26–28 — direct file copy to public Music folder
                    val musicDir = File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_MUSIC), "Readio")
                    musicDir.mkdirs()
                    val destFile = File(musicDir, outFileName)
                    sourceFile.copyTo(destFile, overwrite = true)

                    // Tell the media scanner so it appears in music apps
                    MediaScannerConnection.scanFile(
                        getApplication(),
                        arrayOf(destFile.absolutePath),
                        arrayOf("audio/wav"),
                        null
                    )
                    _exportState.postValue(ExportState.Done(destFile.absolutePath))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _exportState.postValue(ExportState.Error("Export failed: ${e.message}"))
            }
        }
    }

    /**
     * Build a shareable URI for the audio file using FileProvider and
     * post it as ReadyToShare — the Fragment opens the system share sheet.
     */
    fun prepareShare() {
        val book = _book.value ?: run {
            _exportState.value = ExportState.Error("No book loaded")
            return
        }
        val sourceFile = File(book.mp3FilePath)
        if (!sourceFile.exists()) {
            _exportState.value = ExportState.Error("Audio file not found")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    getApplication(),
                    "${getApplication<Application>().packageName}.fileprovider",
                    sourceFile
                )
                _exportState.postValue(ExportState.ReadyToShare(uri, "audio/wav"))
            } catch (e: Exception) {
                _exportState.postValue(ExportState.Error("Share failed: ${e.message}"))
            }
        }
    }

    fun resetExportState() {
        _exportState.value = ExportState.Idle
    }

    override fun onCleared() {
        conversionPollingJob?.cancel()
        player.release()
        super.onCleared()
    }
}
