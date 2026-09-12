package com.voiceime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeLayoutPolicyTest {

    @Test
    fun `compact when narrow window or multi-window`() {
        assertTrue(ImeLayoutPolicy.isCompactMode(false, 599f))
        assertFalse(ImeLayoutPolicy.isCompactMode(false, 600f))
        assertTrue(ImeLayoutPolicy.isCompactMode(true, 800f))
    }

    @Test
    fun `clip limit capped in compact mode`() {
        assertEquals(8, ImeLayoutPolicy.clipLimit(8, false))
        assertEquals(4, ImeLayoutPolicy.clipLimit(8, true))
        assertEquals(2, ImeLayoutPolicy.clipLimit(2, true))
        assertEquals(1, ImeLayoutPolicy.clipLimit(0, true))
    }

    @Test
    fun `popup height capped by input view fraction`() {
        // 400dp base, 500px view → 60% = 300px cap wins.
        assertEquals(300, ImeLayoutPolicy.popupHeightPx(400, 500))
        // Tall view: base wins.
        assertEquals(400, ImeLayoutPolicy.popupHeightPx(400, 2000))
        // Unmeasured view: base fallback.
        assertEquals(400, ImeLayoutPolicy.popupHeightPx(400, 0))
    }

    @Test
    fun `content width capped at max`() {
        assertEquals(560, ImeLayoutPolicy.contentWidthPx(1280, 560))
        assertEquals(400, ImeLayoutPolicy.contentWidthPx(400, 560))
        assertEquals(560, ImeLayoutPolicy.contentWidthPx(0, 560))
        assertEquals(300, ImeLayoutPolicy.contentWidthPx(300, 0))
    }

    @Test
    fun `fullscreen extract always off`() {
        assertFalse(ImeLayoutPolicy.fullscreenMode())
    }

    @Test
    fun `bar geometry caps and centers`() {
        // Narrow phone: natural slot wins, no offset.
        val phone = ImeLayoutPolicy.barGeometry(360f, 48, 100f, 0.55f)
        assertEquals(360f / 48, phone.slotW, 0.001f)
        assertEquals(0f, phone.startX, 0.001f)
        // Wide tablet: slot capped, strip centered.
        val tablet = ImeLayoutPolicy.barGeometry(1280f, 48, 10f, 0.55f)
        assertEquals(10f, tablet.slotW, 0.001f)
        assertEquals(5.5f, tablet.barW, 0.001f)
        assertEquals((1280f - 10f * 48) / 2f, tablet.startX, 0.001f)
        // Degenerate input: zeros.
        val empty = ImeLayoutPolicy.barGeometry(0f, 48, 10f, 0.55f)
        assertEquals(0f, empty.slotW, 0.001f)
    }
}
