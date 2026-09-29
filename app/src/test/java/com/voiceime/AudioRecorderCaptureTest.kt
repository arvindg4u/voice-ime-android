package com.voiceime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import kotlin.math.PI
import kotlin.math.sin

/**
 * Robolectric start()/stop() smoke tests for [AudioRecorder], driven by
 * [ShadowAudioRecord]. Plain JVM tests cannot drive the capture loop
 * (AudioRecord is a hardware stub there); the shadow cooperates — default
 * source returns full-size reads with proper INIT/RECORDING states, and a
 * custom [ShadowAudioRecord.AudioRecordSource] feeds synthetic PCM — so the
 * real capture thread, the listener guards, and the stop() join path are
 * exercised here. (Probe evidence in task-3-report.md.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("DEPRECATION") // ShadowAudioRecord.setSource is deprecated but is the supported injection hook
class AudioRecorderCaptureTest {

    @After
    fun clearShadowSource() {
        ShadowAudioRecord.clearSource()
    }

    /** Sine source: 640 bytes (~20 ms at 16 kHz 16-bit mono) per read. */
    private fun sineSource(): ShadowAudioRecord.AudioRecordSource =
        object : ShadowAudioRecord.AudioRecordSource {
            var phase = 0
            override fun readInByteArray(
                audioData: ByteArray,
                offsetInBytes: Int,
                sizeInBytes: Int,
                isBlocking: Boolean,
            ): Int {
                Thread.sleep(5)
                val n = minOf(sizeInBytes, 640)
                var i = 0
                while (i + 1 < n) {
                    val s = (sin(2.0 * PI * 440.0 * phase / 16000) * 10000).toInt()
                    phase++
                    audioData[offsetInBytes + i] = (s and 0xFF).toByte()
                    audioData[offsetInBytes + i + 1] = ((s ushr 8) and 0xFF).toByte()
                    i += 2
                }
                return n
            }
        }

    @Test
    fun `start stop round trip yields valid wav repeatedly`() {
        // The default Robolectric source returns immediately and can grow the
        // in-memory capture buffer without bound. Use the bounded synthetic
        // source so this lifecycle test remains deterministic on CI runners.
        ShadowAudioRecord.setSource(sineSource())
        repeat(3) {
            // Arrange
            val rec = AudioRecorder()

            // Act
            rec.start()
            assertTrue(rec.isRecording())
            Thread.sleep(100)
            val (wav, peak) = rec.stop()

            // Assert
            assertFalse(rec.isRecording())
            assertTrue("wav=${wav.size}", wav.size > 44)
            assertEquals('R'.code.toByte(), wav[0])
            assertTrue(AudioRecorder.isSilent(peak))
        }
    }

    @Test
    fun `synthetic source captures audio and notifies listeners`() {
        // Arrange
        ShadowAudioRecord.setSource(sineSource())
        var levels = 0
        var chunks = 0
        val rec = AudioRecorder(onLevel = { levels++ }, onPcmChunk = { chunks++ })

        // Act
        rec.start()
        Thread.sleep(200)
        val (wav, peak) = rec.stop()

        // Assert
        assertTrue("expected captured pcm, wav=${wav.size}", wav.size - 44 > 640)
        assertFalse(AudioRecorder.isSilent(peak))
        assertTrue("levels=$levels", levels > 0)
        assertTrue("chunks=$chunks", chunks > 0)
    }

    @Test
    fun `throwing onLevel does not kill capture`() {
        // Arrange
        ShadowAudioRecord.setSource(sineSource())
        var chunks = 0
        val rec = AudioRecorder(
            onLevel = { throw RuntimeException("boom") },
            onPcmChunk = { chunks++ },
        )

        // Act
        rec.start()
        Thread.sleep(200)
        val (wav, _) = rec.stop()

        // Assert: pre-fix the first throwing onLevel kills the capture thread
        // after a single 640-byte read; guarded, capture spans many reads.
        assertTrue("expected many chunks, wav=${wav.size}", wav.size - 44 > 640)
        assertTrue("chunks=$chunks", chunks > 1)
        assertFalse(rec.isRecording())
    }
}
