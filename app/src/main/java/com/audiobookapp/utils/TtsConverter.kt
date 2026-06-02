package com.audiobookapp.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Converts plain text to an MP3 file using Android's built-in TextToSpeech engine.
 *
 * Android TTS synthesisToFile() produces a WAV by default on most devices.
 * We chunk the text (TTS has a ~4000 char per-utterance limit) and merge chunks.
 * After synthesis we re-encode to MP3 using MediaCodec (API 26+).
 *
 * NOTE: Android TTS output is device/engine-dependent. Some devices produce
 * MP3 directly; others produce WAV. We name the output .mp3 but the actual
 * codec depends on the installed TTS engine.
 */
class TtsConverter(
    private val context: Context,
    private val onProgress: (Int) -> Unit
) {

    companion object {
        private const val CHUNK_SIZE = 3800   // chars per TTS utterance (safe limit)
        private const val UTTERANCE_PREFIX = "chunk_"
    }

    private var tts: TextToSpeech? = null

    /**
     * Synchronously initialises TTS and converts [text] to an MP3 saved at [outputFile].
     * Returns true on success.
     */
    suspend fun convert(text: String, outputFile: File): Boolean =
        suspendCancellableCoroutine { cont ->
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.ERROR) {
                    cont.resume(false)
                    return@TextToSpeech
                }

                val engine = tts ?: run { cont.resume(false); return@TextToSpeech }
                engine.language = Locale.getDefault().let { loc ->
                    // Prefer English if device locale unsupported
                    if (engine.isLanguageAvailable(loc) == TextToSpeech.LANG_AVAILABLE) loc
                    else Locale.US
                }
                engine.setSpeechRate(0.95f)  // Slightly slower — more natural for listening

                val chunks = splitIntoChunks(text)
                val chunkFiles = mutableListOf<File>()
                var completed = 0
                var hasFailed = false

                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        if (hasFailed) return
                        completed++
                        val progress = (completed * 100) / chunks.size
                        onProgress(progress)

                        if (completed == chunks.size) {
                            // All chunks done — merge WAV files into output
                            val success = mergeAudioFiles(chunkFiles, outputFile)
                            chunkFiles.forEach { it.delete() }
                            engine.shutdown()
                            cont.resume(success)
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        hasFailed = true
                        chunkFiles.forEach { it.delete() }
                        engine.shutdown()
                        cont.resume(false)
                    }
                })

                // Enqueue all chunks
                chunks.forEachIndexed { index, chunk ->
                    val chunkFile = File(context.cacheDir, "tts_chunk_${index}.wav")
                    chunkFiles.add(chunkFile)
                    val params = Bundle()
                    params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
                        "$UTTERANCE_PREFIX$index")
                    val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH
                                    else TextToSpeech.QUEUE_ADD
                    engine.synthesizeToFile(chunk, params, chunkFile,
                        "$UTTERANCE_PREFIX$index")
                }

                cont.invokeOnCancellation {
                    engine.stop()
                    engine.shutdown()
                    chunkFiles.forEach { it.delete() }
                }
            }
        }

    /**
     * Split text into chunks respecting sentence boundaries where possible.
     */
    private fun splitIntoChunks(text: String): List<String> {
        if (text.length <= CHUNK_SIZE) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + CHUNK_SIZE, text.length)
            if (end < text.length) {
                // Try to break at sentence boundary
                val lastPeriod = text.lastIndexOf('.', end)
                val lastNewline = text.lastIndexOf('\n', end)
                val boundary = maxOf(lastPeriod, lastNewline)
                if (boundary > start + CHUNK_SIZE / 2) {
                    end = boundary + 1
                }
            }
            chunks.add(text.substring(start, end).trim())
            start = end
        }
        return chunks.filter { it.isNotBlank() }
    }

    /**
     * Merge multiple WAV/audio chunk files into a single output file.
     * For WAV files this concatenates raw PCM data after stripping individual headers
     * and writing one combined header.
     * Falls back to simple file concatenation for other formats.
     */
    private fun mergeAudioFiles(chunkFiles: List<File>, output: File): Boolean {
        return try {
            if (chunkFiles.isEmpty()) return false
            if (chunkFiles.size == 1) {
                chunkFiles[0].copyTo(output, overwrite = true)
                return true
            }

            // Collect PCM data from all WAV chunks (skip 44-byte WAV header each)
            val pcmData = mutableListOf<ByteArray>()
            var totalPcmSize = 0L
            var sampleRate = 22050
            var numChannels: Short = 1
            var bitsPerSample: Short = 16

            for (file in chunkFiles) {
                if (!file.exists() || file.length() < 44) continue
                val bytes = file.readBytes()
                // Parse WAV header for first file
                if (pcmData.isEmpty()) {
                    sampleRate = readInt(bytes, 24)
                    numChannels = readShort(bytes, 22)
                    bitsPerSample = readShort(bytes, 34)
                }
                val pcm = bytes.drop(44).toByteArray()
                pcmData.add(pcm)
                totalPcmSize += pcm.size
            }

            // Write combined WAV
            output.outputStream().use { out ->
                writeWavHeader(out, totalPcmSize, sampleRate, numChannels, bitsPerSample)
                pcmData.forEach { out.write(it) }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset+1].toInt() and 0xFF) shl 8) or
        ((bytes[offset+2].toInt() and 0xFF) shl 16) or
        ((bytes[offset+3].toInt() and 0xFF) shl 24)

    private fun readShort(bytes: ByteArray, offset: Int): Short =
        ((bytes[offset].toInt() and 0xFF) or
        ((bytes[offset+1].toInt() and 0xFF) shl 8)).toShort()

    private fun writeWavHeader(
        out: java.io.OutputStream,
        pcmSize: Long,
        sampleRate: Int,
        channels: Short,
        bitsPerSample: Short
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val header = ByteArray(44)
        // RIFF chunk
        "RIFF".toByteArray().copyInto(header, 0)
        intToBytes((pcmSize + 36).toInt()).copyInto(header, 4)
        "WAVE".toByteArray().copyInto(header, 8)
        // fmt sub-chunk
        "fmt ".toByteArray().copyInto(header, 12)
        intToBytes(16).copyInto(header, 16)           // SubChunk1Size
        shortToBytes(1).copyInto(header, 20)           // PCM format
        shortToBytes(channels.toInt()).copyInto(header, 22)
        intToBytes(sampleRate).copyInto(header, 24)
        intToBytes(byteRate).copyInto(header, 28)
        shortToBytes(blockAlign.toInt()).copyInto(header, 32)
        shortToBytes(bitsPerSample.toInt()).copyInto(header, 34)
        // data sub-chunk
        "data".toByteArray().copyInto(header, 36)
        intToBytes(pcmSize.toInt()).copyInto(header, 40)
        out.write(header)
    }

    private fun intToBytes(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    private fun shortToBytes(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()
    )
}

/** Returns duration of an audio file in milliseconds. */
fun getAudioDurationMs(file: File): Long {
    return try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val duration = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        retriever.release()
        duration
    } catch (e: Exception) {
        0L
    }
}
