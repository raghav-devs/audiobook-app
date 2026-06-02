package com.audiobookapp.ui.converter

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.audiobookapp.data.db.AppDatabase
import com.audiobookapp.data.model.AudioBook
import com.audiobookapp.service.ConversionWorker
import com.audiobookapp.utils.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

sealed class ConversionState {
    object Idle : ConversionState()
    data class FileSelected(val fileName: String, val uri: Uri) : ConversionState()
    data class Extracting(val fileName: String) : ConversionState()
    data class Converting(val progress: Int, val bookId: Int) : ConversionState()
    data class Done(val bookId: Int, val title: String) : ConversionState()
    data class Error(val message: String) : ConversionState()
}

class ConverterViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val session = SessionManager(application)
    private val workManager = WorkManager.getInstance(application)

    private val _state = MutableLiveData<ConversionState>(ConversionState.Idle)
    val state: LiveData<ConversionState> = _state

    fun onFileSelected(uri: Uri, fileName: String) {
        _state.value = ConversionState.FileSelected(fileName, uri)
    }

    fun startConversion(uri: Uri, fileName: String) {
        _state.value = ConversionState.Extracting(fileName)

        viewModelScope.launch(Dispatchers.IO) {
            // 1. Persist a DB record immediately so it appears in Library right away
            val ext = fileName.substringAfterLast('.').lowercase()
            val safeTitle = fileName.substringBeforeLast('.').take(60)
            val outputDir = File(getApplication<Application>().filesDir, "audiobooks")
            outputDir.mkdirs()
            val outputFile = File(outputDir, "${safeTitle.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")}_${System.currentTimeMillis()}.wav")

            val isGoogle = session.getAuthType() == SessionManager.AUTH_TYPE_GOOGLE
            val book = AudioBook(
                userId       = if (isGoogle) -1 else session.getLocalUserId(),
                googleUserId = if (isGoogle) session.getGoogleUserId() else null,
                title        = safeTitle,
                originalFileName = fileName,
                originalFilePath = uri.toString(),
                mp3FilePath  = outputFile.absolutePath,
                mp3FileName  = outputFile.name,
                fileType     = ext.uppercase(),
                fileSizeBytes = getFileSize(uri),
                isConversionComplete = false,
                conversionProgress   = 0,
                isPlayable   = false
            )
            val bookId = db.audioBookDao().insertAudioBook(book).toInt()

            // 2. Enqueue WorkManager job — survives screen navigation
            val workRequest = ConversionWorker.buildRequest(uri, fileName, bookId)
            workManager.enqueue(workRequest)

            // 3. Observe WorkManager progress on main thread
            viewModelScope.launch(Dispatchers.Main) {
                workManager.getWorkInfoByIdLiveData(workRequest.id)
                    .observeForever { info ->
                        if (info == null) return@observeForever
                        when (info.state) {
                            WorkInfo.State.RUNNING -> {
                                val stage    = info.progress.getString(ConversionWorker.KEY_STAGE)
                                val progress = info.progress.getInt(ConversionWorker.KEY_PROGRESS, 0)
                                when (stage) {
                                    ConversionWorker.STAGE_EXTRACTING -> {
                                        val ocrPage  = info.progress.getInt("ocr_page", 0)
                                        val ocrTotal = info.progress.getInt("ocr_total", 0)
                                        val label = if (ocrTotal > 0)
                                            "OCR scanning page $ocrPage of $ocrTotal…"
                                        else
                                            "Extracting text…"
                                        _state.postValue(ConversionState.Extracting(label))
                                    }
                                    ConversionWorker.STAGE_CONVERTING ->
                                        _state.postValue(ConversionState.Converting(progress, bookId))
                                    else -> {}
                                }
                            }
                            WorkInfo.State.SUCCEEDED -> {
                                _state.postValue(ConversionState.Done(bookId, safeTitle))
                            }
                            WorkInfo.State.FAILED -> {
                                val error = info.outputData.getString(ConversionWorker.KEY_ERROR)
                                    ?: "Conversion failed"
                                _state.postValue(ConversionState.Error(error))
                            }
                            WorkInfo.State.CANCELLED -> {
                                _state.postValue(ConversionState.Error("Conversion was cancelled"))
                            }
                            else -> {}
                        }
                    }
            }
        }
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            getApplication<Application>().contentResolver
                .openFileDescriptor(uri, "r")?.statSize ?: 0L
        } catch (e: Exception) { 0L }
    }

    fun reset() {
        _state.value = ConversionState.Idle
    }
}
