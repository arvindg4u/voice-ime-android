package com.voiceime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSessionGuardTest {

    @Test
    fun `ids are monotonic starting at 1`() {
        val guard = LiveSessionGuard()
        assertEquals(1L, guard.next())
        assertEquals(2L, guard.next())
        assertEquals(3L, guard.next())
    }

    @Test
    fun `previous id is stale after next`() {
        val guard = LiveSessionGuard()
        val first = guard.next()
        assertTrue(guard.isCurrent(first))
        guard.next()
        assertFalse(guard.isCurrent(first))
    }

    @Test
    fun `zero id is never current`() {
        val guard = LiveSessionGuard()
        guard.next()
        assertFalse(guard.isCurrent(0L))
    }
}
