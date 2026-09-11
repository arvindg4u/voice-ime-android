package com.voiceime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRecorderTest {

    @Test
    fun `isSilent returns true for all-zeros capture`() {
        assertTrue(AudioRecorder.isSilent(0f))
    }

    @Test
    fun `isSilent returns false for normal speech`() {
        assertFalse(AudioRecorder.isSilent(0.5f))
    }

    @Test
    fun `isSilent respects default threshold boundary`() {
        assertTrue(AudioRecorder.isSilent(0.019f))
        assertFalse(AudioRecorder.isSilent(0.021f))
    }

    @Test
    fun `magnitude of silence is approximately zero`() {
        assertTrue(AudioRecorder.magnitude(0f) < 0.001f)
    }

    @Test
    fun `magnitude of faint signal is perceptual mid value`() {
        val m = AudioRecorder.magnitude(0.01f)
        assertTrue(m in 0.35f..0.50f)
    }

    @Test
    fun `magnitude increases monotonically with rms`() {
        assertTrue(AudioRecorder.magnitude(0.05f) > AudioRecorder.magnitude(0.01f))
    }

    @Test
    fun `testToneWav is a 1s 16kHz mono 16-bit wav`() {
        val wav = AudioRecorder.testToneWav()
        assertEquals(44 + 16000 * 2, wav.size)
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        val dataSize = (wav[40].toInt() and 0xFF) or
            ((wav[41].toInt() and 0xFF) shl 8) or
            ((wav[42].toInt() and 0xFF) shl 16) or
            ((wav[43].toInt() and 0xFF) shl 24)
        assertEquals(32000, dataSize)
    }
}
