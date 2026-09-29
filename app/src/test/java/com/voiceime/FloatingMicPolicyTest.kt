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
    fun `peek leaves the requested visible sliver on either edge`() {
        assertEquals(980, FloatingMicPolicy.peekedX(944, 1000, 56, 20))
        assertEquals(-36, FloatingMicPolicy.peekedX(8, 1000, 56, 20))
    }

    @Test
    fun `placeholder text is not copied into the editor`() {
        assertEquals("", FloatingMicPolicy.textForInsertion("What would you like to do?", "What would you like to do?"))
        assertEquals("typed", FloatingMicPolicy.textForInsertion("typed", "What would you like to do?"))
        assertEquals(null, FloatingMicPolicy.textForInsertion(null, "hint"))
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
