package com.voiceime

import org.junit.Assert.assertEquals
import org.junit.Test

class MicPermissionPolicyTest {

    @Test
    fun `grant on a pending tap resumes the tap`() {
        assertEquals(
            MicPermissionPolicy.Outcome.RESUME,
            MicPermissionPolicy.outcome(pendingTap = true, granted = true),
        )
    }

    /**
     * Regression: a denial used to re-enter onMicTapped(), which relaunched the
     * permission activity. On Android 11+ the repeat request is auto-denied, so
     * that became an activity-launch loop.
     */
    @Test
    fun `denial on a pending tap is terminal and never re-arms`() {
        assertEquals(
            MicPermissionPolicy.Outcome.DENIED,
            MicPermissionPolicy.outcome(pendingTap = true, granted = false),
        )
    }

    @Test
    fun `a stray result with no pending tap is ignored`() {
        assertEquals(
            MicPermissionPolicy.Outcome.IGNORE,
            MicPermissionPolicy.outcome(pendingTap = false, granted = true),
        )
        assertEquals(
            MicPermissionPolicy.Outcome.IGNORE,
            MicPermissionPolicy.outcome(pendingTap = false, granted = false),
        )
    }

    @Test
    fun `only a grant produces resume`() {
        val outcomes = listOf(true, false).flatMap { pending ->
            listOf(true, false).map { granted ->
                MicPermissionPolicy.outcome(pending, granted)
            }
        }
        assertEquals(1, outcomes.count { it == MicPermissionPolicy.Outcome.RESUME })
    }
}
