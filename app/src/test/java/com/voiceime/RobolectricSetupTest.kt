package com.voiceime

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RobolectricSetupTest {

    @Test
    fun `real SharedPreferences round-trips through companion helpers`() {
        // Arrange
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(
            ClipboardHistoryActivity.PREFS,
            android.content.Context.MODE_PRIVATE,
        ).edit().putBoolean(ClipboardHistoryActivity.KEY_ENABLED, true).apply()

        // Act
        val enabled = ClipboardHistoryActivity.isEnabled(context)

        // Assert
        assertEquals(true, enabled)
    }
}
