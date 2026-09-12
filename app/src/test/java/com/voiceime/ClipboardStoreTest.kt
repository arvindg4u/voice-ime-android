package com.voiceime

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipboardStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store(): ClipboardStore =
        ClipboardStore.forDirectory(temp.newFolder()).apply {
            enabled = true
            ttlMs = 0L
        }

    @Test
    fun `round-trip save load preserves pin and order`() {
        val dir = temp.newFolder()
        val first = ClipboardStore.forDirectory(dir).apply {
            enabled = true
            ttlMs = 0L
        }
        first.add("one", nowMs = 1_000L)
        first.add("two", nowMs = 2_000L)
        val pinnedId = first.snapshot().first { it.text == "one" }.id
        first.togglePin(pinnedId)

        val second = ClipboardStore.forDirectory(dir).apply {
            enabled = true
            ttlMs = 0L
        }
        val loaded = second.load()
        assertEquals(listOf("two", "one"), loaded.map { it.text })
        assertTrue(loaded.first { it.text == "one" }.isPinned)
    }

    @Test
    fun `corrupt primary falls back to bak`() {
        val dir = temp.newFolder()
        val first = ClipboardStore.forDirectory(dir).apply {
            enabled = true
            ttlMs = 0L
        }
        first.add("saved", nowMs = 1_000L)
        first.add("newer", nowMs = 2_000L)
        // A backup was written on the second persist.
        File(dir, "clipboard_history.json").writeText("{not json", Charsets.UTF_8)

        val second = ClipboardStore.forDirectory(dir).apply {
            enabled = true
            ttlMs = 0L
        }
        val loaded = second.load()
        assertEquals(listOf("saved"), loaded.map { it.text })
    }

    @Test
    fun `corrupt primary without backup loads empty`() {
        val dir = temp.newFolder()
        val s = ClipboardStore.forDirectory(dir).apply {
            enabled = true
            ttlMs = 0L
        }
        s.add("hello", nowMs = 1_000L)
        File(dir, "clipboard_history.json").writeText("garbage[[[", Charsets.UTF_8)
        File(dir, "clipboard_history.json.bak").delete()
        assertTrue(s.load().isEmpty())
    }

    @Test
    fun `prune-on-load enforces caps`() {
        val dir = temp.newFolder()
        val array = JSONArray()
        for (i in 1..40) {
            array.put(
                JSONObject()
                    .put("id", "id-$i")
                    .put("text", "item-$i")
                    .put("createdAtMs", i.toLong())
                    .put("isPinned", false)
                    .put("isSensitive", false)
                    .put("mimeType", "text/plain"),
            )
        }
        File(dir, "clipboard_history.json").writeText(array.toString(), Charsets.UTF_8)
        val s = ClipboardStore.forDirectory(dir).apply {
            enabled = true
            ttlMs = 0L
        }
        val loaded = s.load()
        assertEquals(ClipboardPolicy.DEFAULT_MAX_UNPINNED, loaded.size)
    }

    @Test
    fun `clearAll and deleteAt behave`() {
        val s = store()
        s.add("a", nowMs = 1_000L)
        s.add("b", nowMs = 2_000L)
        val idA = s.snapshot().first { it.text == "a" }.id
        s.deleteAt(idA)
        assertEquals(listOf("b"), s.snapshot().map { it.text })
        s.clearAll()
        assertTrue(s.snapshot().isEmpty())
    }

    @Test
    fun `clearUnpinned keeps pinned`() {
        val s = store()
        s.add("keep", nowMs = 1_000L)
        s.add("drop", nowMs = 2_000L)
        s.togglePin(s.snapshot().first { it.text == "keep" }.id)
        s.clearUnpinned()
        assertEquals(listOf("keep"), s.snapshot().map { it.text })
    }

    @Test
    fun `toggle-off deletes files`() {
        val dir = temp.newFolder()
        val s = ClipboardStore.forDirectory(dir).apply {
            enabled = true
            ttlMs = 0L
        }
        s.add("x", nowMs = 1_000L)
        assertTrue(File(dir, "clipboard_history.json").exists())
        s.setEnabled(false)
        assertFalse(File(dir, "clipboard_history.json").exists())
        assertFalse(File(dir, "clipboard_history.json.bak").exists())
        assertTrue(s.snapshot().isEmpty())
    }

    @Test
    fun `disabled store ignores adds`() {
        val s = ClipboardStore.forDirectory(temp.newFolder())
        s.enabled = false
        s.add("nope", nowMs = 1_000L)
        assertTrue(s.snapshot().isEmpty())
    }

    @Test
    fun `togglePin respects pinned cap`() {
        val s = store().apply { maxPinned = 2 }
        s.add("a", nowMs = 1_000L)
        s.add("b", nowMs = 2_000L)
        s.add("c", nowMs = 3_000L)
        for (text in listOf("a", "b", "c")) {
            s.togglePin(s.snapshot().first { it.text == text }.id)
        }
        val pinned = s.snapshot().filter { it.isPinned }
        assertEquals(2, pinned.size)
        // FIFO: oldest pinned ("a") evicted.
        assertEquals(setOf("b", "c"), pinned.map { it.text }.toSet())
    }
}
