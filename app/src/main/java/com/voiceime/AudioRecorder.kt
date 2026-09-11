package com.voiceime

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Captures 16 kHz mono 16-bit PCM via VOICE_RECOGNITION source and encodes to WAV.
 * Simple pattern: stop button + 60 s auto-stop. No VAD library, no JNI.
 */
class AudioRecorder(
    private val onLevel: (Float) -> Unit = {},
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_DURATION_MS = 60_000L
        const val SILENCE_PEAK_THRESHOLD = 0.02f

        /** Pure helper: true when peak is below the silence threshold. */
        fun isSilent(peak: Float, threshold: Float = SILENCE_PEAK_THRESHOLD): Boolean =
            peak < threshold
    }

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var recordingThread: Thread? = null

    private val recording = AtomicBoolean(false)

    private var buffer: ByteArrayOutputStream? = null

    @Volatile
    private var peakLevel = 0f

    @SuppressLint("MissingPermission")
    @Throws(IllegalStateException::class)
    fun start() {
        if (recording.get()) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) throw IllegalStateException("Mic unavailable")
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Mic unavailable")
        }
        buffer = ByteArrayOutputStream()
        peakLevel = 0f
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw IllegalStateException("Mic unavailable")
        }
        recorder = record
        recording.set(true)
        val startedAt = System.currentTimeMillis()
        val thread = Thread({
            val chunk = ByteArray(3200)
            while (recording.get()) {
                if (System.currentTimeMillis() - startedAt >= MAX_DURATION_MS) break
                val read = try {
                    record.read(chunk, 0, chunk.size)
                } catch (e: Exception) {
                    break
                }
                if (read > 0) {
                    buffer?.write(chunk, 0, read)
                    val lvl = rmsLevel(chunk, read)
                    if (lvl > peakLevel) peakLevel = lvl
                    onLevel(lvl)
                } else if (read == 0) {
                    // Healthy streams may return 0; only stop if the mic dropped.
                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) break
                } else {
                    // read < 0 signals an error; stop capture.
                    break
                }
            }
            recording.set(false)
        }, "VoiceImeRecord")
        recordingThread = thread
        thread.start()
    }

    /** Stops capture and returns the audio as a WAV byte array (44-byte RIFF header). */
    fun stop(): ByteArray {
        recording.set(false)
        try {
            recordingThread?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        recordingThread = null
        val pcm = buffer?.toByteArray() ?: ByteArray(0)
        buffer = null
        releaseRecorder()
        return pcmToWav(pcm)
    }

    /** Discards any captured audio without encoding. */
    fun discard() {
        recording.set(false)
        try {
            recordingThread?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        recordingThread = null
        buffer = null
        releaseRecorder()
    }

    fun isRecording(): Boolean = recording.get()

    fun peakLevel(): Float = peakLevel

    private fun releaseRecorder() {
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // Already stopped; ignore.
        }
        recorder?.release()
        recorder = null
    }

    /** RMS level normalized to 0..1 for the level meter. */
    private fun rmsLevel(bytes: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += (sample * sample).toDouble()
            count++
            i += 2
        }
        if (count == 0) return 0f
        return (sqrt(sum / count) / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val totalLen = 44 + pcm.size
        val out = ByteArray(totalLen)
        // RIFF header
        out[0] = 'R'.code.toByte()
        out[1] = 'I'.code.toByte()
        out[2] = 'F'.code.toByte()
        out[3] = 'F'.code.toByte()
        writeIntLe(out, 4, totalLen - 8)
        out[8] = 'W'.code.toByte()
        out[9] = 'A'.code.toByte()
        out[10] = 'V'.code.toByte()
        out[11] = 'E'.code.toByte()
        // fmt chunk
        out[12] = 'f'.code.toByte()
        out[13] = 'm'.code.toByte()
        out[14] = 't'.code.toByte()
        out[15] = ' '.code.toByte()
        writeIntLe(out, 16, 16)
        writeShortLe(out, 20, 1) // PCM
        writeShortLe(out, 22, 1) // mono
        writeIntLe(out, 24, SAMPLE_RATE)
        writeIntLe(out, 28, SAMPLE_RATE * 2) // byte rate
        writeShortLe(out, 32, 2) // block align
        writeShortLe(out, 34, 16) // bits per sample
        // data chunk
        out[36] = 'd'.code.toByte()
        out[37] = 'a'.code.toByte()
        out[38] = 't'.code.toByte()
        out[39] = 'a'.code.toByte()
        writeIntLe(out, 40, pcm.size)
        System.arraycopy(pcm, 0, out, 44, pcm.size)
        return out
    }

    private fun writeIntLe(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeShortLe(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
