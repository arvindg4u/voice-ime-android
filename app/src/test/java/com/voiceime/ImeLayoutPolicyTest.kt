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
    fun `actions row follows child visibility`() {
        // Cancel alone keeps the row up mid-record/upload.
        assertTrue(ImeLayoutPolicy.actionsRowVisible(true, false, false))
        // Error children keep the row up.
        assertTrue(ImeLayoutPolicy.actionsRowVisible(false, true, false))
        assertTrue(ImeLayoutPolicy.actionsRowVisible(false, false, true))
        // Idle: all children hidden, row collapses.
        assertFalse(ImeLayoutPolicy.actionsRowVisible(false, false, false))
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

    @Test
    fun `next timer delay lands on second boundaries`() {
        // Start of a second: full interval.
        assertEquals(1000L, ImeLayoutPolicy.nextTimerDelayMs(0L))
        assertEquals(1000L, ImeLayoutPolicy.nextTimerDelayMs(2000L))
        // Mid-second: remainder to the boundary.
        assertEquals(750L, ImeLayoutPolicy.nextTimerDelayMs(250L))
        assertEquals(1L, ImeLayoutPolicy.nextTimerDelayMs(1999L))
        // Negative elapsed (clock moved): clamped to a full interval.
        assertEquals(1000L, ImeLayoutPolicy.nextTimerDelayMs(-50L))
    }

    @Test
    fun `idle ripple stays a subtle hairline pulse`() {
        // Sweeps a full sine cycle across bars and phase: never negative,
        // never above the live-bar floor, so idle never mimics activity.
        for (phaseTenths in 0..62) {
            val phase = phaseTenths * 0.1f
            for (bar in 0 until 48) {
                val ripple = ImeLayoutPolicy.idleRippleFraction(phase, bar)
                assertTrue("ripple $ripple at phase $phase bar $bar", ripple >= 0f)
                assertTrue("ripple $ripple at phase $phase bar $bar", ripple <= 0.1f)
            }
        }
        // Phase 0, bar 0 sits exactly on the base (sin(0) = 0).
        assertEquals(
            ImeLayoutPolicy.IDLE_RIPPLE_BASE,
            ImeLayoutPolicy.idleRippleFraction(0f, 0),
            0.0001f,
        )
        // Neighbouring bars differ: the pulse travels across the strip.
        val travelled = (0 until 48).any { bar ->
            ImeLayoutPolicy.idleRippleFraction(0f, bar) !=
                ImeLayoutPolicy.idleRippleFraction(0f, (bar + 1) % 48)
        }
        assertTrue(travelled)
        // Advancing phase moves every bar: the hero never looks dead.
        val moves = (0 until 48).any { bar ->
            ImeLayoutPolicy.idleRippleFraction(0f, bar) !=
                ImeLayoutPolicy.idleRippleFraction(0.1f, bar)
        }
        assertTrue(moves)
    }
}
