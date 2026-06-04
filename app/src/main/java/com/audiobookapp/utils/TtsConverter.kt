package com.audiobookapp.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class TtsConverter(
    private val context: Context,
    private val onProgress: (Int) -> Unit,
    private val onChunkReady: ((bytesWritten: Long) -> Unit)? = null
) {
    companion object {
        private const val CHUNK_SIZE = 3800
        private const val UTTERANCE_PRE = "chunk_"
        const val PLAYABLE_THRESHOLD_BYTES = 88_200L  // ~2 seconds of audio
    }

    suspend fun convert(text: String, outputFile: File): Boolean =
        convertChunks(splitIntoChunks(text), outputFile)

    suspend fun convertPages(pages: List<String>, outputFile: File): Boolean =
        convertChunks(pages.flatMap { splitIntoChunks(it) }, outputFile)

    private suspend fun convertChunks(chunks: List<String>, outputFile: File): Boolean =
        suspendCancellableCoroutine { cont ->
            if (chunks.isEmpty()) { cont.resume(false); return@suspendCancellableCoroutine }

            var tts: TextToSpeech? = null
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.ERROR) { cont.resume(false); return@TextToSpeech }
                val engine = tts ?: run { cont.resume(false); return@TextToSpeech }

                engine.language = Locale.getDefault().let { loc ->
                    if (engine.isLanguageAvailable(loc) == TextToSpeech.LANG_AVAILABLE) loc
                    else Locale.US
                }
                engine.setSpeechRate(0.95f)

                // Write placeholder WAV header — ExoPlayer plays WAV with size=0 fine
                writePlaceholderWavHeader(outputFile)

                val chunkFiles   = Array(chunks.size) { i -> File(context.cacheDir, "tts_chunk_$i.wav") }
                val completed    = AtomicInteger(0)
                val totalPcmSize = AtomicInteger(0)
                var hasFailed    = false

                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}

                    override fun onDone(id: String?) {
                        if (hasFailed) return
                        val index = id?.removePrefix(UTTERANCE_PRE)?.toIntOrNull() ?: return
                        val chunkFile = chunkFiles[index]

                        if (chunkFile.exists() && chunkFile.length() > 44) {
                            val pcm = chunkFile.readBytes().drop(44).toByteArray()
                            appendPcmToFile(outputFile, pcm)
                            totalPcmSize.addAndGet(pcm.size)
                            chunkFile.delete()
                            onChunkReady?.invoke(outputFile.length())
                        }

                        val done = completed.incrementAndGet()
                        onProgress((done * 100) / chunks.size)

                        if (done == chunks.size) {
                            patchWavHeader(outputFile, totalPcmSize.get().toLong())
                            engine.shutdown()
                            cont.resume(true)
                        }
                    }

                    override fun onError(id: String?) {
                        hasFailed = true
                        chunkFiles.forEach { it.delete() }
                        engine.shutdown()
                        cont.resume(false)
                    }
                })

                chunks.forEachIndexed { i, chunk ->
                    val params = Bundle()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "$UTTERANCE_PRE$i")
                    engine.synthesizeToFile(chunk, params, chunkFiles[i], "$UTTERANCE_PRE$i")
                }

                cont.invokeOnCancellation {
                    engine.stop(); engine.shutdown()
                    chunkFiles.forEach { it.delete() }
                }
            }
        }

    private fun writePlaceholderWavHeader(file: File) {
        file.outputStream().use { out ->
            val header = ByteArray(44)
            "RIFF".toByteArray().copyInto(header, 0)
            "WAVE".toByteArray().copyInto(header, 8)
            "fmt ".toByteArray().copyInto(header, 12)
            intToBytes(16).copyInto(header, 16)
            shortToBytes(1).copyInto(header, 20)   // PCM
            shortToBytes(1).copyInto(header, 22)   // mono
            intToBytes(22050).copyInto(header, 24) // sample rate
            intToBytes(44100).copyInto(header, 28) // byte rate
            shortToBytes(2).copyInto(header, 32)   // block align
            shortToBytes(16).copyInto(header, 34)  // bits per sample
            "data".toByteArray().copyInto(header, 36)
            out.write(header)
        }
    }

    private fun appendPcmToFile(file: File, pcm: ByteArray) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(raf.length()); raf.write(pcm)
        }
    }

    private fun patchWavHeader(file: File, pcmSize: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4);  raf.write(intToBytes((pcmSize + 36).toInt()))
            raf.seek(40); raf.write(intToBytes(pcmSize.toInt()))
        }
    }

    private fun splitIntoChunks(text: String): List<String> {
        if (text.length <= CHUNK_SIZE) return listOf(text).filter { it.isNotBlank() }
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + CHUNK_SIZE, text.length)
            if (end < text.length) {
                val boundary = maxOf(text.lastIndexOf('.', end), text.lastIndexOf('\n', end))
                if (boundary > start + CHUNK_SIZE / 2) end = boundary + 1
            }
            val chunk = text.substring(start, end).trim()
            if (chunk.isNotBlank()) chunks.add(chunk)
            start = end
        }
        return chunks
    }

    private fun intToBytes(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    private fun shortToBytes(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()
    )
}

fun getAudioDurationMs(file: File): Long {
    return try {
        val r = MediaMetadataRetriever()
        r.setDataSource(file.absolutePath)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release(); d
    } catch (e: Exception) { 0L }
}
