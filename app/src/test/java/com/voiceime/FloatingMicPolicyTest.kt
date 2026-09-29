package com.voiceime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingMicPolicyTest {

    @Test
    fun `only focused non-password editable fields qualify`() {
        assertTrue(FloatingMicPolicy.shouldShowForEditor(true, true, false, "other.app", "com.voiceime"))
        assertFalse(FloatingMicPolicy.shouldShowForEditor(false, true, false, "other.app", "com.voiceime"))
        assertFalse(FloatingMicPolicy.shouldShowForEditor(true, false, false, "other.app", "com.voiceime"))
        assertFalse(FloatingMicPolicy.shouldShowForEditor(true, true, true, "other.app", "com.voiceime"))
        assertFalse(FloatingMicPolicy.shouldShowForEditor(true, true, false, "com.voiceime", "com.voiceime"))
    }

    @Test
    fun `peek restores before one second and keeps the slide animation short`() {
        assertTrue(FloatingMicPolicy.PEEK_RESTORE_DELAY_MS + FloatingMicPolicy.PEEK_ANIMATION_MS < 1_000L)
    }

    @Test
    fun `peek leaves the requested visible sliver on either edge`() {
        assertEquals(980, FloatingMicPolicy.peekedX(944, 1000, 56, 20))
        assertEquals(-36, FloatingMicPolicy.peekedX(8, 1000, 56, 20))
    }

    @Test
    fun `placeholder text is not copied into the editor`() {
        assertEquals("", FloatingMicPolicy.textForInsertion("What would you like to do?", "What would you like to do?"))
        assertEquals("", FloatingMicPolicy.textForInsertion("What would you like to do?", null, showingHintText = true))
        assertEquals("typed", FloatingMicPolicy.textForInsertion("typed", "What would you like to do?"))
        assertEquals(null, FloatingMicPolicy.textForInsertion(null, "hint"))
    }

    @Test
    fun `live preview replaces an interim hypothesis without duplicating final text`() {
        assertEquals("hello world", FloatingMicPolicy.mergeLivePreview("hello", "hello world"))
        assertEquals("hello world again", FloatingMicPolicy.mergeLivePreview("hello world", "world again"))
        assertEquals("hello world", FloatingMicPolicy.mergeLivePreview("hello", "world"))
        assertEquals("hello", FloatingMicPolicy.mergeLivePreview("hello", ""))
    }

    @Test
    fun `live preview keeps the newest tail inside the bubble limit`() {
        assertEquals("five six", FloatingMicPolicy.livePreviewTail("one two three four five six", 10))
        assertEquals("latest", FloatingMicPolicy.livePreviewTail("latest", 140))
    }

    @Test
    fun `drag snaps to an edge and clamps vertical position`() {
        assertEquals(8, FloatingMicPolicy.dockedX(100, 1000, 56, 8))
        assertEquals(936, FloatingMicPolicy.dockedX(850, 1000, 56, 8))
        assertEquals(8, FloatingMicPolicy.clampY(-30, 600, 56, 8))
        assertEquals(536, FloatingMicPolicy.clampY(900, 600, 56, 8))
    }

    @Test
    fun `busy overlay remains reachable when focus disappears`() {
        assertTrue(FloatingMicPolicy.shouldKeepVisibleWhileBusy(true))
        assertFalse(FloatingMicPolicy.shouldKeepVisibleWhileBusy(false))
    }
}
