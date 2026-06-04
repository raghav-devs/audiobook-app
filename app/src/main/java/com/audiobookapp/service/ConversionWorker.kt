package com.audiobookapp.service

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.*
import com.audiobookapp.data.db.AppDatabase
import com.audiobookapp.utils.NotificationHelper
import com.audiobookapp.utils.OcrExtractor
import com.audiobookapp.utils.TextExtractor
import com.audiobookapp.utils.TtsConverter
import com.audiobookapp.utils.getAudioDurationMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class ConversionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_URI           = "uri"
        const val KEY_FILE_NAME     = "file_name"
        const val KEY_BOOK_ID       = "book_id"
        const val KEY_PROGRESS      = "progress"
        const val KEY_ERROR         = "error"
        const val KEY_STAGE         = "stage"

        const val STAGE_EXTRACTING  = "extracting"
        const val STAGE_CONVERTING  = "converting"
        const val STAGE_DONE        = "done"

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

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI)       ?: return Result.failure()
        val fileName  = inputData.getString(KEY_FILE_NAME) ?: return Result.failure()
        val bookId    = inputData.getInt(KEY_BOOK_ID, -1)
        if (bookId == -1) return Result.failure()

        // ── Promote to foreground immediately ─────────────────────────────────
        // This is what keeps the worker alive when the app is backgrounded.
        // Without this, Android kills the process after ~10 minutes (or sooner
        // on aggressive OEMs like Pixel with battery optimisation).
        NotificationHelper.createChannel(applicationContext)
        val notification = NotificationHelper.buildProgressNotification(
            context   = applicationContext,
            title     = "Readio — Converting",
            message   = "Preparing \"$fileName\"…",
            progress  = -1   // indeterminate until we know page count
        )
        val foregroundInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NotificationHelper.NOTIFICATION_ID, notification)
        }
        setForeground(foregroundInfo)

        val db  = AppDatabase.getInstance(applicationContext)
        val uri = Uri.parse(uriString)
        val ext = fileName.substringAfterLast('.').lowercase()
        TextExtractor.init(applicationContext)

        val book = db.audioBookDao().getBookById(bookId) ?: return Result.failure()
        val outputFile = File(book.mp3FilePath)
        File(applicationContext.filesDir, "audiobooks").mkdirs()

        return try {
            if (ext == "pdf" && isScannedPdf(uri)) {
                convertScannedPdf(uri, fileName, bookId, outputFile, db)
            } else {
                convertDigitalFile(uri, fileName, bookId, outputFile, db)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            db.audioBookDao().markConversionError(bookId, "Unexpected error: ${e.message}")
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        }
    }

    // ── Scanned PDF ───────────────────────────────────────────────────────────

    private suspend fun isScannedPdf(uri: Uri): Boolean {
        return try {
            val stream = applicationContext.contentResolver.openInputStream(uri) ?: return false
            val doc    = com.tom_roush.pdfbox.pdmodel.PDDocument.load(stream)
            val text   = com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc).trim()
            doc.close()
            text.length < 50
        } catch (e: Exception) { false }
    }

    private suspend fun convertScannedPdf(
        uri: Uri, fileName: String, bookId: Int,
        outputFile: File, db: AppDatabase
    ): Result {
        updateNotification("Readio — Scanning PDF", "Starting OCR…", -1)
        setProgress(workDataOf(KEY_STAGE to STAGE_EXTRACTING, KEY_PROGRESS to 0))

        val tempPdf = File(applicationContext.cacheDir, "ocr_input_$bookId.pdf")
        try {
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                tempPdf.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            db.audioBookDao().markConversionError(bookId, "Could not read PDF file.")
            return Result.failure(workDataOf(KEY_ERROR to "PDF read failed"))
        }

        val pages          = mutableListOf<String>()
        var markedPlayable = false

        // Collect OCR pages with live notification updates
        OcrExtractor.extractPagesAsFlow(applicationContext, tempPdf)
            .collect { (pageText, pageIndex, total) ->
                pages.add(pageText)
                val pct = ((pageIndex + 1) * 100) / total
                updateNotification(
                    title    = "Readio — Scanning PDF",
                    message  = "OCR page ${pageIndex + 1} of $total",
                    progress = pct
                )
                setProgressAsync(workDataOf(
                    KEY_STAGE    to STAGE_EXTRACTING,
                    KEY_PROGRESS to pct,
                    KEY_BOOK_ID  to bookId,
                    "ocr_page"   to (pageIndex + 1),
                    "ocr_total"  to total
                ))
            }

        tempPdf.delete()

        if (pages.isEmpty()) {
            val msg = "OCR found no text. Ensure the scan is clear and right-side up."
            db.audioBookDao().markConversionError(bookId, msg)
            return Result.failure(workDataOf(KEY_ERROR to msg))
        }

        updateNotification("Readio — Converting", "Generating audio…", 0)
        setProgress(workDataOf(KEY_STAGE to STAGE_CONVERTING, KEY_PROGRESS to 0))

        val converter = buildConverter(bookId, db, fileName) { bytesWritten ->
            if (!markedPlayable && bytesWritten >= TtsConverter.PLAYABLE_THRESHOLD_BYTES) {
                markedPlayable = true
                ioScope.launch { db.audioBookDao().markPlayable(bookId) }
            }
        }

        val success = converter.convertPages(pages, outputFile)
        return finalise(success, bookId, outputFile, db)
    }

    // ── Digital file ──────────────────────────────────────────────────────────

    private suspend fun convertDigitalFile(
        uri: Uri, fileName: String, bookId: Int,
        outputFile: File, db: AppDatabase
    ): Result {
        updateNotification("Readio — Converting", "Extracting text…", -1)
        setProgress(workDataOf(KEY_STAGE to STAGE_EXTRACTING, KEY_PROGRESS to 0))

        val extracted = TextExtractor.extract(
            context       = applicationContext,
            uri           = uri,
            fileName      = fileName,
            onOcrProgress = { done, total ->
                val pct = (done * 100) / total
                updateNotification("Readio — Scanning", "OCR page $done of $total", pct)
                setProgressAsync(workDataOf(
                    KEY_STAGE    to STAGE_EXTRACTING,
                    KEY_PROGRESS to pct,
                    KEY_BOOK_ID  to bookId,
                    "ocr_page"   to done,
                    "ocr_total"  to total
                ))
            }
        )

        if (extracted == null || extracted.second.isBlank()) {
            val msg = "No text content found in this file."
            db.audioBookDao().markConversionError(bookId, msg)
            return Result.failure(workDataOf(KEY_ERROR to msg))
        }

        updateNotification("Readio — Converting", "Generating audio…", 0)
        setProgress(workDataOf(KEY_STAGE to STAGE_CONVERTING, KEY_PROGRESS to 0))

        var markedPlayable = false
        val converter = buildConverter(bookId, db, fileName) { bytesWritten ->
            if (!markedPlayable && bytesWritten >= TtsConverter.PLAYABLE_THRESHOLD_BYTES) {
                markedPlayable = true
                ioScope.launch { db.audioBookDao().markPlayable(bookId) }
            }
        }

        val success = converter.convert(extracted.second, outputFile)
        return finalise(success, bookId, outputFile, db)
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private fun buildConverter(
        bookId: Int,
        db: AppDatabase,
        fileName: String,
        onChunkReady: (Long) -> Unit
    ) = TtsConverter(
        context      = applicationContext,
        onProgress   = { progress ->
            updateNotification(
                title    = "Readio — Converting",
                message  = "Audio synthesis $progress%  •  $fileName",
                progress = progress
            )
            setProgressAsync(workDataOf(
                KEY_STAGE    to STAGE_CONVERTING,
                KEY_PROGRESS to progress,
                KEY_BOOK_ID  to bookId
            ))
            ioScope.launch {
                db.audioBookDao().updateConversionProgress(bookId, progress)
            }
        },
        onChunkReady = onChunkReady
    )

    private suspend fun finalise(
        success: Boolean, bookId: Int, outputFile: File, db: AppDatabase
    ): Result {
        if (!success) {
            db.audioBookDao().markConversionError(bookId,
                "TTS synthesis failed. Ensure a TTS engine is installed.")
            return Result.failure(workDataOf(KEY_ERROR to "TTS failed"))
        }
        val durationMs = getAudioDurationMs(outputFile)
        db.audioBookDao().markConversionComplete(bookId, durationMs)
        updateNotification("Readio — Done", "Conversion complete", 100)
        setProgress(workDataOf(KEY_STAGE to STAGE_DONE, KEY_PROGRESS to 100, KEY_BOOK_ID to bookId))
        return Result.success(workDataOf(KEY_BOOK_ID to bookId))
    }

    private fun updateNotification(title: String, message: String, progress: Int) {
        val notification = NotificationHelper.buildProgressNotification(
            applicationContext, title, message, progress)
        val manager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NotificationHelper.NOTIFICATION_ID, notification)
    }
}
