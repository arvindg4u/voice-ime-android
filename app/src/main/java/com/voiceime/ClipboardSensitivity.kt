package com.voiceime

import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle

/**
 * Marks a primary clip as sensitive (API 33+) so the system and downstream
 * consumers do not preview or index it.
 *
 * Shared by the IME's commit path and the history manager on purpose: a
 * sensitive entry re-copied from the manager must not lose the flag the IME set
 * when it first captured it.
 */
internal fun markClipSensitive(desc: ClipDescription) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    try {
        val extras = desc.extras ?: PersistableBundle()
        extras.putBoolean(ClipboardPolicy.EXTRA_IS_SENSITIVE, true)
        desc.extras = extras
    } catch (e: Exception) {
        // Sensitive flag is best-effort on older platforms.
    }
}