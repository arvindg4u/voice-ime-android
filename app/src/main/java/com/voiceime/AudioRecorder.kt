package com.voiceime

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneDirection
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Captures 16 kHz mono 16-bit PCM via VOICE_RECOGNITION source and encodes to WAV.
 * Simple pattern: stop button + 5 min auto-stop. No VAD library, no JNI.
 *
 * For Live streaming, [onPcmChunk] receives a copy of every captured buffer
 * (called on the recording thread — never touch views, just forward).
 */
class AudioRecorder(
    private val context: Context? = null,
    private val onLevel: (Float) -> Unit = {},
    private val onPcmChunk: (ByteArray) -> Unit = {},
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_DURATION_MS = 300_000L
        const val SILENCE_PEAK_THRESHOLD = 0.008f
        /** Grace period of effectively silent capture before the mic may be blocked. */
        const val BLOCKED_GRACE_MS = 2_000L
        /** Capture chunk size in bytes (100 ms at 16 kHz 16-bit mono). */
        const val CHUNK_BYTES = 3_200
        /** RMS level below which a chunk counts as noise-free. */
        const val NOISE_FLOOR = 0.0001f
        /** Bounded join for the capture thread in stop()/discard(). */
        const val STOP_JOIN_MS = 2_000L

        /** Pure helper: true when peak is below the silence threshold. */
        fun isSilent(peak: Float, threshold: Float = SILENCE_PEAK_THRESHOLD): Boolean =
            peak < threshold

        /**
         * Perceptual level 0..1: raw RMS is near-flat for quiet signals, so this
         * expands the low end to drive the visible level meter.
         */
        fun magnitude(rms: Float): Float =
            (1.0f - 0.1f.pow(24.0f * rms)).coerceIn(0f, 1f)

        /** 1 s 440 Hz sine at 0.5 amplitude as WAV — for the settings Test button. */
        internal fun testToneWav(sampleRate: Int = SAMPLE_RATE): ByteArray {
            val frames = sampleRate
            val pcm = ByteArray(frames * 2)
            for (i in 0 until frames) {
                val sample =
                    (sin(2.0 * PI * 440.0 * i / sampleRate) * 0.5 * 32767).toInt()
                pcm[i * 2] = (sample and 0xFF).toByte()
                pcm[i * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
            }
            return pcmToWav(pcm, sampleRate)
        }

        internal fun pcmToWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
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
            writeIntLe(out, 24, sampleRate)
            writeIntLe(out, 28, sampleRate * 2) // byte rate
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

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var recordingThread: Thread? = null

    private val recording = AtomicBoolean(false)

    private var buffer: ByteArrayOutputStream? = null
    private val bufferLock = Any()

    @Volatile
    private var _peakLevel = 0f

    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    private var anyNoiseAtAll = false

    @Volatile
    private var captureStartMs = 0L

    /** True when ~2 s of capture show effectively no signal — mic may be blocked. */
    fun isMicMaybeBlocked(): Boolean =
        !anyNoiseAtAll && System.currentTimeMillis() - captureStartMs > BLOCKED_GRACE_MS

    private fun audioManager(): AudioManager? {
        val svc = context?.getSystemService(Context.AUDIO_SERVICE)
        return svc as? AudioManager
    }

    /** Route capture to the built-in mic (or best non-BT fallback) on S+. Pre-S: no-op. */
    private fun routeToBuiltinMic() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val am = audioManager() ?: return
        val devices = try {
            am.availableCommunicationDevices
        } catch (_: Exception) {
            return
        }
        val dev = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: devices.firstOrNull { it.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: return
        try {
            am.setCommunicationDevice(dev)
        } catch (_: Exception) {
            // Routing is best-effort; capture continues on the default device.
        }
    }

    /** Release routing + audio focus. Finally-safe: never throws. */
    private fun releaseAudioRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager()?.let { am ->
                try {
                    am.clearCommunicationDevice()
                } catch (_: Exception) {
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager()?.let { am ->
                try {
                    focusRequest?.let { am.abandonAudioFocusRequest(it) }
                    focusRequest = null
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun requestExclusiveFocus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val am = audioManager() ?: return
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).build()
        try {
            focusRequest?.let { am.requestAudioFocus(it) }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    @Throws(IllegalStateException::class)
    fun start() {
        if (recording.get()) return
        routeToBuiltinMic()
        val record = createRecord()
        requestExclusiveFocus()
        buffer = ByteArrayOutputStream()
        _peakLevel = 0f
        anyNoiseAtAll = false
        captureStartMs = System.currentTimeMillis()
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            releaseAudioRoute()
            throw IllegalStateException("Mic unavailable")
        }
        recorder = record
        recording.set(true)
        val thread = Thread({ captureLoop(record) }, "VoiceImeRecord")
        recordingThread = thread
        thread.start()
    }

    /**
     * Creates, initializes, and returns a ready AudioRecord.
     * Throws [IllegalStateException] when the mic is unavailable.
     */
    @SuppressLint("MissingPermission")
    private fun createRecord(): AudioRecord {
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
            releaseAudioRoute()
            throw IllegalStateException("Mic unavailable")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                record.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
            } catch (_: Exception) {
                // Unsupported on this device/ROM; directional hint is best-effort.
            }
        }
        return record
    }

    /** Capture-thread body: reads PCM into [buffer] and forwards levels/chunks. */
    private fun captureLoop(record: AudioRecord) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        val startedAt = System.currentTimeMillis()
        val chunk = ByteArray(CHUNK_BYTES)
        while (recording.get()) {
            if (System.currentTimeMillis() - startedAt >= MAX_DURATION_MS) break
            val read = try {
                record.read(chunk, 0, chunk.size)
            } catch (e: Exception) {
                break
            }
            if (read > 0) {
                synchronized(bufferLock) { buffer?.write(chunk, 0, read) }
                val lvl = rmsLevel(chunk, read)
                if (lvl > NOISE_FLOOR) anyNoiseAtAll = true
                if (lvl > _peakLevel) _peakLevel = lvl
                try {
                    onLevel(magnitude(lvl))
                } catch (e: Exception) {
                    // Listener failed; capture continues regardless.
                }
                try {
                    onPcmChunk(chunk.copyOf(read))
                } catch (e: Exception) {
                    // Streaming consumer failed; capture continues regardless.
                }
            } else if (read == 0) {
                // Healthy streams may return 0; only stop if the mic dropped.
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) break
            } else {
                // read < 0 signals an error; stop capture.
                break
            }
        }
        recording.set(false)
    }

    /**
     * Stops capture and returns audio as WAV plus the post-join peak level.
     * The peak is read only after joining the recording thread, so the last
     * buffer write happens-before this read (no early/zero peak race).
     *
     * The join is bounded (2 s) with an unbounded follow-up only when the
     * thread is still alive, but it still blocks the caller, so the service
     * calls this from a background thread (never the UI thread).
     */
    fun stop(): Pair<ByteArray, Float> {
        recording.set(false)
        joinRecordingThread()
        recordingThread = null
        val peak = _peakLevel
        try {
            val pcm = synchronized(bufferLock) { buffer?.toByteArray() } ?: ByteArray(0)
            return pcmToWav(pcm, SAMPLE_RATE) to peak
        } finally {
            synchronized(bufferLock) { buffer = null }
            releaseRecorder()
            releaseAudioRoute()
        }
    }

    /** Discards any captured audio without encoding. Caller must be off the UI thread (joins). */
    fun discard() {
        recording.set(false)
        joinRecordingThread()
        recordingThread = null
        synchronized(bufferLock) { buffer = null }
        releaseRecorder()
        releaseAudioRoute()
    }

    /**
     * Bounded join, then an unbounded second join if the thread is still
     * alive (typically blocked in `record.read`). Callers are already
     * documented off-UI-thread, so waiting out a blocked read cannot wedge
     * the UI — and it guarantees the capture thread can no longer touch
     * [buffer] when stop()/discard() snapshot or clear it.
     */
    private fun joinRecordingThread() {
        val thread = recordingThread ?: return
        try {
            thread.join(STOP_JOIN_MS)
            if (thread.isAlive) thread.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun isRecording(): Boolean = recording.get()

    fun peakLevel(): Float = _peakLevel

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
}
