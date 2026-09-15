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
class ClipboardToggleTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun historyFile(): File = File(context.noBackupFilesDir, ClipboardStore.FILE_NAME)

    @Test
    fun `disabling via helper deletes history files`() {
        // Arrange
        ClipboardHistoryActivity.setClipboardEnabled(context, true)
        val store = ClipboardStore.forContext(context)
        store.setEnabled(true)
        store.add("secret", nowMs = 1_000L)
        assertTrue(historyFile().exists())

        // Act
        ClipboardHistoryActivity.setClipboardEnabled(context, false)

        // Assert
        assertFalse(historyFile().exists())
        assertFalse(File(context.noBackupFilesDir, "${ClipboardStore.FILE_NAME}.bak").exists())
        assertEquals(false, ClipboardHistoryActivity.isEnabled(context))
    }

    @Test
    fun `re-enable after disable resurfaces nothing`() {
        ClipboardHistoryActivity.setClipboardEnabled(context, true)
        val store = ClipboardStore.forContext(context)
        store.setEnabled(true)
        store.add("gone", nowMs = 1_000L)
        ClipboardHistoryActivity.setClipboardEnabled(context, false)
        ClipboardHistoryActivity.setClipboardEnabled(context, true)
        assertTrue(ClipboardStore.forContext(context).load().isEmpty())
    }
}
