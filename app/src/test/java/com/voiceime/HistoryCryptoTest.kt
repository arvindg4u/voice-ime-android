package com.voiceime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryCryptoTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `persisted history is not plaintext on disk`() {
        // Arrange
        val store = ClipboardStore.forContext(context)
        // ttlMs = 0 disables expiry: the synthetic nowMs below predates the
        // real clock by decades, so the default 24h TTL would prune it
        // instantly. Same pattern as all ClipboardStoreTest cases.
        store.ttlMs = 0L
        store.setEnabled(true)

        // Act
        store.add("cipher-me-please", nowMs = 1_000L)
        val raw = File(context.noBackupFilesDir, ClipboardStore.FILE_NAME)
            .readText(Charsets.UTF_8)

        // Assert
        assertFalse(raw.contains("cipher-me-please"))
        assertEquals(listOf("cipher-me-please"), store.snapshot().map { it.text })
    }

    @Test
    fun `round-trip survives reload`() {
        val store = ClipboardStore.forContext(context)
        store.ttlMs = 0L
        store.setEnabled(true)
        store.add("persist-me", nowMs = 1_000L)
        val loaded = ClipboardStore.forContext(context).apply { ttlMs = 0L }.load()
        assertEquals(listOf("persist-me"), loaded.map { it.text })
    }

    @Test
    fun `legacy plaintext file migrates on load`() {
        // Arrange: hand-write the OLD plaintext format
        val array = org.json.JSONArray()
        array.put(
            org.json.JSONObject()
                .put("id", "legacy-1")
                .put("text", "legacy-entry")
                .put("createdAtMs", 1_000L)
                .put("isPinned", false)
                .put("isSensitive", false)
                .put("mimeType", "text/plain"),
        )
        File(context.noBackupFilesDir, ClipboardStore.FILE_NAME)
            .writeText(array.toString(), Charsets.UTF_8)

        // Act
        val loaded = ClipboardStore.forContext(context).apply { ttlMs = 0L }.load()

        // Assert
        assertEquals(listOf("legacy-entry"), loaded.map { it.text })
    }
}
