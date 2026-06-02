package com.audiobookapp.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR extractor for scanned PDFs using Android's built-in PdfRenderer
 * and ML Kit Text Recognition v2.
 *
 * Supports:
 *   - Latin script  (English, and other Latin-alphabet languages)
 *   - Devanagari    (Hindi, Marathi, Sanskrit, Nepali)
 *
 * Both models are bundled in the APK (no network call needed at runtime).
 * PdfRenderer is part of android.graphics (API 21+) — no extra dependency.
 *
 * Render resolution: 300 DPI equivalent (scaling = 300/72 = 4.17×).
 * Higher DPI → better OCR accuracy but more memory per page.
 * Pages are rendered and released one at a time to avoid OOM.
 */
object OcrExtractor {

    // Render at ~200 DPI for a good accuracy/memory balance.
    // 72 pt = 1 inch on PDF canvas; ×2.78 ≈ 200 DPI on most phone screens.
    private const val RENDER_SCALE = 2.78f

    /**
     * Run OCR on every page of a PDF file.
     *
     * @param context      Application context
     * @param pdfFile      The PDF file on disk (must be seekable — copy from Uri first if needed)
     * @param onPageDone   Called after each page with (pagesDone, totalPages)
     * @return Full extracted text, or null if the file cannot be opened
     */
    suspend fun extractFromPdf(
        context: Context,
        pdfFile: File,
        onPageDone: ((done: Int, total: Int) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {

        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            ?: return@withContext null

        val renderer = try {
            PdfRenderer(pfd)
        } catch (e: Exception) {
            pfd.close()
            return@withContext null
        }

        // ML Kit recognisers — one per script
        val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val devanagariRecognizer = TextRecognition.getClient(
            DevanagariTextRecognizerOptions.Builder().build()
        )

        val pageCount = renderer.pageCount
        val sb = StringBuilder()

        try {
            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)

                // Allocate bitmap at scaled resolution
                val width  = (page.width  * RENDER_SCALE).toInt()
                val height = (page.height * RENDER_SCALE).toInt()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                // Fill white background (PDF pages are transparent by default)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                // Run both recognisers concurrently and merge results
                val latinText     = recognise(latinRecognizer, bitmap)
                val devanagariText = recognise(devanagariRecognizer, bitmap)

                // Pick the script with more content for this page
                val pageText = mergeScripts(latinText, devanagariText)
                if (pageText.isNotBlank()) {
                    sb.append(pageText).append("\n\n")
                }

                bitmap.recycle()
                onPageDone?.invoke(i + 1, pageCount)
            }
        } finally {
            renderer.close()
            pfd.close()
            latinRecognizer.close()
            devanagariRecognizer.close()
        }

        sb.toString().trim().takeIf { it.isNotBlank() }
    }

    /**
     * Suspend wrapper around ML Kit's callback-based recognise() call.
     */
    private suspend fun recognise(
        recognizer: TextRecognizer,
        bitmap: Bitmap
    ): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result -> cont.resume(result.text) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    /**
     * Merge Latin and Devanagari results from the same page.
     *
     * Strategy:
     * - If one result is substantially longer, use it.
     * - If both have content (mixed-script page), interleave by line position
     *   is not possible without bounding boxes here, so we append both
     *   and let TTS handle the mix (Android TTS switches voice for Hindi chars).
     * - Blank results are discarded.
     */
    private fun mergeScripts(latin: String, devanagari: String): String {
        val latinClean     = latin.trim()
        val devanagariClean = devanagari.trim()

        return when {
            latinClean.isBlank() && devanagariClean.isBlank() -> ""
            latinClean.isBlank()  -> devanagariClean
            devanagariClean.isBlank() -> latinClean
            // Both have content — mixed-script page
            // Devanagari recogniser often also picks up Latin on mixed pages;
            // use the longer result if ratio > 2:1, otherwise append both
            latinClean.length > devanagariClean.length * 2 -> latinClean
            devanagariClean.length > latinClean.length * 2 -> devanagariClean
            else -> "$latinClean\n$devanagariClean"
        }
    }
}
