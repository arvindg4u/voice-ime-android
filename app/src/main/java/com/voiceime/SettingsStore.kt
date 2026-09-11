package com.voiceime

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Encrypted settings storage for base URL, API key, and model. */
class SettingsStore(context: Context) {

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini-audio-preview"

        private const val PREFS_NAME = "voice_ime_settings"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
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

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value).apply()
        }

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()
            .ifEmpty { DEFAULT_MODEL }
        set(value) {
            prefs.edit().putString(KEY_MODEL, value.ifEmpty { DEFAULT_MODEL }).apply()
        }

    /** Trim trailing slashes and a trailing "/chat/completions"; empty falls back to default. */
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
