package com.voiceime

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
}
