package com.voiceime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPolicyTest {

    private fun entry(
        text: String,
        pinned: Boolean = false,
        createdAtMs: Long = 1_000L,
    ) = ClipboardEntry(text = text, createdAtMs = createdAtMs, isPinned = pinned)

    @Test
    fun `shouldCapture accepts plain text`() {
        assertTrue(ClipboardPolicy.shouldCapture("hello"))
    }

    @Test
    fun `shouldCapture rejects blank`() {
        assertFalse(ClipboardPolicy.shouldCapture(""))
        assertFalse(ClipboardPolicy.shouldCapture("   "))
        assertFalse(ClipboardPolicy.shouldCapture(null))
    }

    @Test
    fun `shouldCapture rejects password fields`() {
        assertFalse(ClipboardPolicy.shouldCapture("secret", passwordField = true))
    }

    @Test
    fun `shouldCapture rejects no-personalized-learning`() {
        assertFalse(ClipboardPolicy.shouldCapture("hello", noPersonalizedLearning = true))
    }

    @Test
    fun `shouldCapture rejects sensitive extra`() {
        assertFalse(ClipboardPolicy.shouldCapture("hello", hasSensitiveExtra = true))
    }

    @Test
    fun `shouldCapture rejects oversized item`() {
        val big = "x".repeat(ClipboardPolicy.MAX_ITEM_CHARS + 1)
        assertFalse(ClipboardPolicy.shouldCapture(big))
        assertTrue(ClipboardPolicy.shouldCapture("x".repeat(ClipboardPolicy.MAX_ITEM_CHARS)))
    }

    @Test
    fun `isPasswordInputType covers all four variations`() {
        val textPw = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val textVisible = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val textWeb = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val numPw = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        assertTrue(ClipboardPolicy.isPasswordInputType(textPw))
        assertTrue(ClipboardPolicy.isPasswordInputType(textVisible))
        assertTrue(ClipboardPolicy.isPasswordInputType(textWeb))
        assertTrue(ClipboardPolicy.isPasswordInputType(numPw))
        assertFalse(ClipboardPolicy.isPasswordInputType(InputType.TYPE_CLASS_TEXT))
        assertFalse(ClipboardPolicy.isPasswordInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(ClipboardPolicy.isPasswordInputType(InputType.TYPE_CLASS_NUMBER))
        assertFalse(ClipboardPolicy.isPasswordInputType(InputType.TYPE_CLASS_PHONE))
    }

    @Test
    fun `hasNoPersonalizedLearning reads ime flag`() {
        assertTrue(ClipboardPolicy.hasNoPersonalizedLearning(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertFalse(ClipboardPolicy.hasNoPersonalizedLearning(0))
    }

    @Test
    fun `dedupeExactText moves newest to top`() {
        val now = 10_000L
        val existing = listOf(entry("b", createdAtMs = 2_000L), entry("a", createdAtMs = 1_000L))
        val updated = ClipboardPolicy.addClip(existing, "a", now, ttlMs = 0L)
        assertEquals(listOf("a", "b"), updated.map { it.text })
        assertEquals(now, updated.first { it.text == "a" }.createdAtMs)
        assertEquals(2, updated.size)
    }

    @Test
    fun `dedupe preserves pin state`() {
        val pinned = entry("a", pinned = true, createdAtMs = 1_000L)
        val updated = ClipboardPolicy.addClip(listOf(pinned), "a", 9_000L, ttlMs = 0L)
        assertEquals(1, updated.size)
        assertTrue(updated.single().isPinned)
    }

    @Test
    fun `pruneExpired respects pinned exemption`() {
        val now = ClipboardPolicy.TTL_24H + 10_000L
        val entries = listOf(
            entry("old-unpinned", createdAtMs = 1_000L),
            entry("old-pinned", pinned = true, createdAtMs = 1_000L),
            entry("fresh", createdAtMs = now),
        )
        val pruned = ClipboardPolicy.prune(entries, now)
        assertEquals(listOf("old-pinned", "fresh"), pruned.map { it.text })
    }

    @Test
    fun `ttl off keeps everything`() {
        val ancient = entry("ancient", createdAtMs = 1L)
        assertEquals(1, ClipboardPolicy.prune(listOf(ancient), Long.MAX_VALUE / 2, ttlMs = 0L).size)
    }

    @Test
    fun `unpin refreshes timestamp`() {
        val now = 50_000L
        val entries = listOf(entry("p", pinned = true, createdAtMs = 1_000L))
        val updated = ClipboardPolicy.unpin(entries, entries.single().id, now)
        assertFalse(updated.single().isPinned)
        assertEquals(now, updated.single().createdAtMs)
    }

    @Test
    fun `trimBeyondCap keeps pinned and drops oldest unpinned`() {
        val now = 100_000L
        // 12 unpinned (oldest first by time) + 1 pinned; cap 10 (MIN clamp
        // forbids smaller caps) must drop the 2 oldest unpinned, keep pinned.
        val unpinned = (1..12).map { i -> entry("u$i", createdAtMs = i * 1_000L) }
        val entries = listOf(entry("pinned", pinned = true, createdAtMs = 1_000L)) + unpinned
        val capped = ClipboardPolicy.prune(entries, now, maxUnpinned = 10, ttlMs = 0L)
        assertEquals(11, capped.size)
        assertTrue(capped.any { it.text == "pinned" })
        assertFalse(capped.any { it.text == "u1" })
        assertFalse(capped.any { it.text == "u2" })
        assertTrue(capped.any { it.text == "u12" })
    }

    @Test
    fun `pinned cap enforced FIFO`() {
        val entries = (1..12).map { i -> entry("p$i", pinned = true, createdAtMs = i.toLong()) }
        val pruned = ClipboardPolicy.prune(entries, 99_999L, maxPinned = 10, ttlMs = 0L)
        assertEquals(10, pruned.size)
        assertTrue(pruned.all { it.isPinned })
    }

    @Test
    fun `clampMaxUnpinned bounds range`() {
        assertEquals(10, ClipboardPolicy.clampMaxUnpinned(1))
        assertEquals(50, ClipboardPolicy.clampMaxUnpinned(500))
        assertEquals(30, ClipboardPolicy.clampMaxUnpinned(30))
    }

    @Test
    fun `sanitizePreview single-lines and truncates at 64`() {
        val long = "word ".repeat(30).trim()
        val preview = ClipboardPolicy.sanitizePreview(entry("line1\nline2  $long"))
        assertFalse(preview.contains("\n"))
        assertTrue(preview.length <= ClipboardPolicy.PREVIEW_CHARS)
        assertEquals(ClipboardPolicy.PREVIEW_CHARS, preview.length)
    }

    @Test
    fun `sanitizePreview masks sensitive entries`() {
        val preview = ClipboardPolicy.sanitizePreview(
            ClipboardEntry(text = "s3cret", createdAtMs = 1L, isSensitive = true),
        )
        assertFalse(preview.contains("s3cret"))
        assertTrue(preview.isNotEmpty())
    }
}
