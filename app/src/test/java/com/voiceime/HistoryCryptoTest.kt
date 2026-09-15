package com.voiceime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `fallback engages under robolectric where keystore provider is absent`() {
        // The AndroidKeyStore provider does not exist on this JVM
        // (upstream robolectric/robolectric#1518 still open); this documents
        // why the software fallback engages here and ONLY here.
        assertNull(java.security.Security.getProvider("AndroidKeyStore"))
        assertTrue(
            EncryptedFileHistoryCrypto.isFallbackAllowed(
                java.security.KeyStoreException("AndroidKeyStore not found"),
            ),
        )
    }

    @Test
    fun `fallback refuses non-absence keystore failures`() {
        // A locked/corrupted on-device keystore also surfaces as
        // KeyStoreException while the provider IS present — the gate must
        // NOT treat those as fallback-eligible. (Directly covered by
        // isFallbackAllowed's first line; this pins a non-qualifying cause.)
        assertFalse(
            EncryptedFileHistoryCrypto.isFallbackAllowed(
                IllegalStateException("unrelated failure"),
            ),
        )
    }

    @Test
    fun `first migration leaves no plaintext bak behind`() {
        // Arrange: hand-write the OLD plaintext format as primary
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

        // Act: load (migrates in memory) then add (triggers sealed persist,
        // which rotates the legacy primary to .bak before the sealed write)
        val store = ClipboardStore.forContext(context).apply { ttlMs = 0L }
        store.setEnabled(true)
        store.add("post-migration", nowMs = 1_000L)

        // Assert: primary is sealed AND .bak is absent-or-sealed
        val primary = File(context.noBackupFilesDir, ClipboardStore.FILE_NAME)
        val backup = File(context.noBackupFilesDir, ClipboardStore.FILE_NAME + ".bak")
        val primaryRaw = primary.readText(Charsets.UTF_8)
        assertFalse(primaryRaw.contains("legacy-entry"))
        assertFalse(primaryRaw.contains("post-migration"))
        if (backup.exists()) {
            val bakRaw = backup.readText(Charsets.UTF_8)
            assertFalse(bakRaw.contains("legacy-entry"))
        }
        assertEquals(
            listOf("post-migration", "legacy-entry"),
            store.snapshot().map { it.text },
        )
    }
}
