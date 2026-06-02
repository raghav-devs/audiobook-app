package com.audiobookapp.utils

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.InputStream

object TextExtractor {

    // Minimum character count from PDFBox to consider a PDF "text-based".
    // Scanned PDFs yield 0–few chars (whitespace, artifacts); genuine text PDFs
    // typically yield hundreds per page.
    private const val MIN_TEXT_CHARS = 50

    fun init(context: Context) {
        PDFBoxResourceLoader.init(context)
    }

    /**
     * Extract plain text from a file URI.
     *
     * For PDFs: first attempts PDFBox (fast, exact). If extracted text is below
     * [MIN_TEXT_CHARS] (indicating a scanned/image-only PDF), falls back to
     * ML Kit OCR via [OcrExtractor].
     *
     * [onOcrProgress] is called during OCR fallback with (pagesDone, totalPages).
     *
     * Returns Pair(title, text) or null on unrecoverable failure.
     */
    suspend fun extract(
        context: Context,
        uri: Uri,
        fileName: String,
        onOcrProgress: ((done: Int, total: Int) -> Unit)? = null
    ): Pair<String, String>? {
        return try {
            val ext = fileName.substringAfterLast('.').lowercase()
            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: return null

            when (ext) {
                "pdf"  -> extractPdf(context, uri, inputStream, fileName, onOcrProgress)
                "docx" -> extractDocx(inputStream, fileName)
                "txt"  -> extractTxt(inputStream, fileName)
                "epub" -> extractEpubManually(inputStream, fileName)
                "doc"  -> extractTxt(inputStream, fileName)
                "rtf"  -> extractTxt(inputStream, fileName)
                else   -> extractTxt(inputStream, fileName)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    private suspend fun extractPdf(
        context: Context,
        uri: Uri,
        stream: InputStream,
        fileName: String,
        onOcrProgress: ((Int, Int) -> Unit)?
    ): Pair<String, String>? {
        val title = fileName.substringBeforeLast('.')

        // Step 1: Try PDFBox text extraction (fast path for digital PDFs)
        val pdfBoxText = runCatching {
            val document = PDDocument.load(stream)
            val stripper = PDFTextStripper()
            val text = stripper.getText(document)
            document.close()
            text.trim()
        }.getOrNull() ?: ""

        if (pdfBoxText.length >= MIN_TEXT_CHARS) {
            // Digital PDF — text extracted cleanly
            return Pair(title, pdfBoxText)
        }

        // Step 2: PDFBox yielded little/no text — scanned PDF detected.
        // Copy the URI content to a temp file (PdfRenderer requires a seekable fd).
        val tempFile = copyUriToTempFile(context, uri) ?: return null

        return try {
            val ocrText = OcrExtractor.extractFromPdf(
                context  = context,
                pdfFile  = tempFile,
                onPageDone = onOcrProgress
            )
            if (ocrText.isNullOrBlank()) {
                // OCR also found nothing — truly empty or unsupported format
                null
            } else {
                Pair(title, ocrText)
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Copy a content URI to a temporary File so PdfRenderer can open it
     * via a seekable ParcelFileDescriptor.
     */
    private fun copyUriToTempFile(context: Context, uri: Uri): File? {
        return try {
            val tempFile = File.createTempFile("ocr_pdf_", ".pdf", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── DOCX ─────────────────────────────────────────────────────────────────

    private fun extractDocx(stream: InputStream, fileName: String): Pair<String, String> {
        val doc = XWPFDocument(stream)
        val sb = StringBuilder()
        val title = doc.properties?.coreProperties?.title
            ?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.')
        doc.paragraphs.forEach { para ->
            val text = para.text.trim()
            if (text.isNotEmpty()) sb.append(text).append("\n")
        }
        doc.close()
        return Pair(title, sb.toString().trim())
    }

    // ── TXT ──────────────────────────────────────────────────────────────────

    private fun extractTxt(stream: InputStream, fileName: String): Pair<String, String> {
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val title = fileName.substringBeforeLast('.')
        return Pair(title, text.trim())
    }

    // ── EPUB ─────────────────────────────────────────────────────────────────

    /**
     * EPUB is a ZIP archive containing HTML/XHTML files.
     * Parsed with Java's built-in ZipInputStream — no external library.
     */
    private fun extractEpubManually(stream: InputStream, fileName: String): Pair<String, String> {
        val sb = StringBuilder()
        var title = fileName.substringBeforeLast('.')

        val zipStream = java.util.zip.ZipInputStream(stream)
        var entry = zipStream.nextEntry
        while (entry != null) {
            val name = entry.name.lowercase()
            when {
                name.endsWith(".opf") -> {
                    val content = zipStream.readBytes().toString(Charsets.UTF_8)
                    Regex("<dc:title[^>]*>([^<]+)</dc:title>", RegexOption.IGNORE_CASE)
                        .find(content)?.groupValues?.get(1)?.trim()
                        ?.takeIf { it.isNotBlank() }?.let { title = it }
                }
                (name.endsWith(".html") || name.endsWith(".xhtml") || name.endsWith(".htm"))
                        && !name.contains("toc") -> {
                    val content = zipStream.readBytes().toString(Charsets.UTF_8)
                    val stripped = content
                        .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                        .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                        .replace(Regex("<[^>]+>"), " ")
                        .replace("&nbsp;", " ").replace("&amp;", "&")
                        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                        .replace(Regex("\\s+"), " ").trim()
                    if (stripped.isNotBlank()) sb.append(stripped).append("\n")
                }
                else -> zipStream.readBytes()
            }
            zipStream.closeEntry()
            entry = zipStream.nextEntry
        }
        zipStream.close()
        return Pair(title, sb.toString().trim())
    }
}
