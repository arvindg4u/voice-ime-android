package com.voiceime

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Encrypted settings storage for base URL, API keys (one per line), model, and custom prompt. */
class SettingsStore(context: Context) {

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        /** Max chars for the custom prompt; longer input is silently truncated on save. */
        const val MAX_CUSTOM_PROMPT = 2000

        private const val PREFS_NAME = "voice_ime_settings"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_CUSTOM_PROMPT = "custom_prompt"
    }

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var baseUrl: String
        get() = normalizeBaseUrl(prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty())
        set(value) {
            prefs.edit().putString(KEY_BASE_URL, normalizeBaseUrl(value)).apply()
        }

    /**
     * API keys, one per line in the stored string. The SAME pref key holds the
     * raw text, so migration from the old single-key value is automatic: it is
     * just a one-line list.
     */
    var apiKeys: List<String>
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value.joinToString("\n")).apply()
        }

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()
            .ifEmpty { DEFAULT_MODEL }
        set(value) {
            prefs.edit().putString(KEY_MODEL, value.ifEmpty { DEFAULT_MODEL }).apply()
        }

    /** Free-form user preferences appended to the system instruction; "" disables. Trims; caps at 2000 chars. */
    var customPrompt: String
        get() = prefs.getString(KEY_CUSTOM_PROMPT, "").orEmpty().trim()
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_PROMPT, value.trim().take(MAX_CUSTOM_PROMPT)).apply()
        }

    /** Trim trailing slashes and legacy "/chat/completions"; empty falls back to default. */
    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return DEFAULT_BASE_URL
        url = url.trimEnd('/')
        if (url.endsWith("/chat/completions")) {
            url = url.removeSuffix("/chat/completions").trimEnd('/')
        }
        if (url.isEmpty()) return DEFAULT_BASE_URL
        return url
    }
}
