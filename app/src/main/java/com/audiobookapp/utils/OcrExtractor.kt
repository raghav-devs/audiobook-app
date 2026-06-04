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
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OcrExtractor {

    private const val RENDER_SCALE = 2.78f

    fun extractPagesAsFlow(context: Context, pdfFile: File): Flow<Triple<String, Int, Int>> =
        callbackFlow {
            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(pfd) } catch (e: Exception) {
                pfd.close(); close(e); return@callbackFlow
            }
            val latin      = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            val total      = renderer.pageCount
            try {
                for (i in 0 until total) {
                    val page   = renderer.openPage(i)
                    val bmp    = Bitmap.createBitmap(
                        (page.width  * RENDER_SCALE).toInt(),
                        (page.height * RENDER_SCALE).toInt(),
                        Bitmap.Config.ARGB_8888
                    )
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    val text = mergeScripts(recognise(latin, bmp), recognise(devanagari, bmp))
                    bmp.recycle()
                    if (text.isNotBlank()) send(Triple(text, i, total))
                }
            } finally {
                renderer.close(); pfd.close(); latin.close(); devanagari.close(); close()
            }
            awaitClose()
        }.flowOn(Dispatchers.IO)

    suspend fun extractFromPdf(
        context: Context, pdfFile: File,
        onPageDone: ((Int, Int) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        val sb = StringBuilder(); var count = 0
        try {
            extractPagesAsFlow(context, pdfFile).collect { (text, _, total) ->
                sb.append(text).append("\n\n"); count++; onPageDone?.invoke(count, total)
            }
        } catch (e: Exception) { return@withContext null }
        sb.toString().trim().takeIf { it.isNotBlank() }
    }

    private suspend fun recognise(r: TextRecognizer, bmp: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            r.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    private fun mergeScripts(l: String, d: String): String {
        val lt = l.trim(); val dt = d.trim()
        return when {
            lt.isBlank() && dt.isBlank() -> ""
            lt.isBlank()  -> dt
            dt.isBlank()  -> lt
            lt.length > dt.length * 2 -> lt
            dt.length > lt.length * 2 -> dt
            else -> "$lt\n$dt"
        }
    }
}
