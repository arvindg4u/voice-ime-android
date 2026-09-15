package com.voiceime

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStoreTest {

    @Test
    fun `normalize keeps https url minus trailing slash`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            SettingsStore.normalize("https://generativelanguage.googleapis.com/v1beta/"),
        )
    }

    @Test
    fun `normalize rejects http to default`() {
        assertEquals(SettingsStore.DEFAULT_BASE_URL, SettingsStore.normalize("http://proxy:8080"))
    }

    @Test
    fun `normalize rejects non-url garbage to default`() {
        assertEquals(SettingsStore.DEFAULT_BASE_URL, SettingsStore.normalize("not a url"))
    }

    @Test
    fun `normalize keeps legacy suffix stripping`() {
        assertEquals(
            "https://example.com/v1",
            SettingsStore.normalize("https://example.com/v1/chat/completions"),
        )
    }

    @Test
    fun `normalize blank falls back to default`() {
        assertEquals(SettingsStore.DEFAULT_BASE_URL, SettingsStore.normalize("   "))
    }

    @Test
    fun `default base url is https`() {
        assertEquals(true, SettingsStore.DEFAULT_BASE_URL.startsWith("https://"))
    }
}
