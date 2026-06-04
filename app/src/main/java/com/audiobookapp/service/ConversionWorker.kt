package com.audiobookapp.service

import android.content.Context
import android.net.Uri
import androidx.work.*
import com.audiobookapp.data.db.AppDatabase
import com.audiobookapp.data.model.AudioBook
import com.audiobookapp.utils.SessionManager
import com.audiobookapp.utils.TextExtractor
import com.audiobookapp.utils.TtsConverter
import com.audiobookapp.utils.getAudioDurationMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * WorkManager worker that runs TTS conversion in the background.
 * Survives the user navigating away from the Converter screen.
 * Reports progress via WorkManager's setProgress() so the UI can observe it.
 */
class ConversionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    // Fire-and-forget scope for suspend DB calls inside non-suspend TTS callback
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val KEY_URI         = "uri"
        const val KEY_FILE_NAME   = "file_name"
        const val KEY_BOOK_ID     = "book_id"
        const val KEY_PROGRESS    = "progress"
        const val KEY_ERROR       = "error"
        const val KEY_STAGE       = "stage"   // "extracting" | "converting" | "done"

        const val STAGE_EXTRACTING = "extracting"
        const val STAGE_CONVERTING = "converting"
        const val STAGE_DONE       = "done"

        fun buildRequest(uri: Uri, fileName: String, bookId: Int): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ConversionWorker>()
                .setInputData(workDataOf(
                    KEY_URI       to uri.toString(),
                    KEY_FILE_NAME to fileName,
                    KEY_BOOK_ID   to bookId
                ))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
    }

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI) ?: return Result.failure()
        val fileName  = inputData.getString(KEY_FILE_NAME) ?: return Result.failure()
        val bookId    = inputData.getInt(KEY_BOOK_ID, -1)
        if (bookId == -1) return Result.failure()

        val db = AppDatabase.getInstance(applicationContext)
        TextExtractor.init(applicationContext)

        return try {
            // Step 1: Extract text (with OCR fallback for scanned PDFs)
            setProgress(workDataOf(KEY_STAGE to STAGE_EXTRACTING, KEY_PROGRESS to 0))

            val uri = Uri.parse(uriString)
            val extracted = TextExtractor.extract(
                context     = applicationContext,
                uri         = uri,
                fileName    = fileName,
                onOcrProgress = { done, total ->
                    // Surface OCR page progress in the converting banner
                    val pct = (done * 100) / total
                    setProgressAsync(workDataOf(
                        KEY_STAGE    to STAGE_EXTRACTING,
                        KEY_PROGRESS to pct,
                        KEY_BOOK_ID  to bookId,
                        "ocr_page"   to done,
                        "ocr_total"  to total
                    ))
                }
            )

            if (extracted == null) {
                val msg = if (fileName.endsWith(".pdf", ignoreCase = true))
                    "Could not extract text. If this is a scanned PDF, OCR was attempted but found no recognisable text. " +
                    "Ensure the scan is clear and right-side up."
                else
                    "Could not extract text from this file."
                db.audioBookDao().markConversionError(bookId, msg)
                return Result.failure(workDataOf(KEY_ERROR to msg))
            }
            val (_, text) = extracted

            if (text.isBlank()) {
                val msg = "No text content found in this file."
                db.audioBookDao().markConversionError(bookId, msg)
                return Result.failure(workDataOf(KEY_ERROR to msg))
            }

            // Step 2: Convert — write chunks progressively so player can start early
            setProgress(workDataOf(KEY_STAGE to STAGE_CONVERTING, KEY_PROGRESS to 0))

            val outputDir = File(applicationContext.filesDir, "audiobooks")
            outputDir.mkdirs()
            val book = db.audioBookDao().getBookById(bookId) ?: return Result.failure()
            val outputFile = File(book.mp3FilePath)

            var markedPlayable = book.isPlayable

            val converter = TtsConverter(applicationContext) { progress ->
                // Non-suspend lambda — use setProgressAsync and ioScope for DB ops
                setProgressAsync(workDataOf(
                    KEY_STAGE    to STAGE_CONVERTING,
                    KEY_PROGRESS to progress,
                    KEY_BOOK_ID  to bookId
                ))
                ioScope.launch {
                    db.audioBookDao().updateConversionProgress(bookId, progress)
                    if (progress >= 10 && !markedPlayable) {
                        db.audioBookDao().markPlayable(bookId)
                        markedPlayable = true
                    }
                }
            }

            val success = converter.convert(text, outputFile)
            if (!success) {
                db.audioBookDao().markConversionError(bookId,
                    "TTS synthesis failed. Ensure a TTS engine is installed on your device.")
                return Result.failure(workDataOf(KEY_ERROR to "TTS failed"))
            }

            // Step 3: Finalise
            val durationMs = getAudioDurationMs(outputFile)
            db.audioBookDao().markConversionComplete(bookId, durationMs)
            setProgress(workDataOf(KEY_STAGE to STAGE_DONE, KEY_PROGRESS to 100, KEY_BOOK_ID to bookId))

            Result.success(workDataOf(KEY_BOOK_ID to bookId))

        } catch (e: Exception) {
            e.printStackTrace()
            db.audioBookDao().markConversionError(bookId, "Unexpected error: ${e.message}")
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        }
    }
}
