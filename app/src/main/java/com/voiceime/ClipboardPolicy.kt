package com.voiceime

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Pure clipboard-history policy: gating, dedupe, prune, trim, previews.
 *
 * Kept free of Android storage APIs so plain JVM unit tests cover it.
 * Pattern origin: FUTO android-keyboard clipboard history (AtomicFile +
 * backup/prune/pin-exemption approach) — reimplemented here from scratch.
 */
object ClipboardPolicy {

    /** Compat raw string for android.content.extra.IS_SENSITIVE (API 33+). */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    const val DEFAULT_MAX_UNPINNED = 30
    const val MIN_MAX_UNPINNED = 10
    const val MAX_MAX_UNPINNED = 50
    const val DEFAULT_MAX_PINNED = 10

    /** Unpinned TTL options, in ms. 0 = no expiry. */
    const val TTL_OFF = 0L
    const val TTL_24H = 24L * 60 * 60 * 1000
    const val TTL_72H = 72L * 60 * 60 * 1000

    const val DEFAULT_TTL_MS = TTL_24H

    /** Per-item text cap in the history store; oversized clips are dropped. */
    const val MAX_ITEM_CHARS = 8 * 1024

    /** Preview length shown in the IME strip / manager list. */
    const val PREVIEW_CHARS = 64

    fun clampMaxUnpinned(raw: Int): Int = raw.coerceIn(MIN_MAX_UNPINNED, MAX_MAX_UNPINNED)

    fun isPasswordInputType(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT && cls != InputType.TYPE_CLASS_NUMBER) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    fun hasNoPersonalizedLearning(imeOptions: Int): Boolean =
        (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

    /**
     * Capture gate: never persist while the target is a password field,
     * opted out of personalized learning, flagged sensitive, or blank.
     */
    fun shouldCapture(
        text: CharSequence?,
        passwordField: Boolean = false,
        noPersonalizedLearning: Boolean = false,
        hasSensitiveExtra: Boolean = false,
    ): Boolean {
        if (text == null || text.isBlank()) return false
        if (text.length > MAX_ITEM_CHARS) return false
        if (passwordField) return false
        if (noPersonalizedLearning) return false
        if (hasSensitiveExtra) return false
        return true
    }

    /**
     * Adds [text] as the newest entry, deduping exact-text matches (the
     * surviving entry keeps its pin state and moves to the top), then
     * prunes expired unpinned entries and trims to caps.
     */
    fun addClip(
        current: List<ClipboardEntry>,
        text: String,
        nowMs: Long,
        maxUnpinned: Int = DEFAULT_MAX_UNPINNED,
        maxPinned: Int = DEFAULT_MAX_PINNED,
        ttlMs: Long = DEFAULT_TTL_MS,
        isSensitive: Boolean = false,
    ): List<ClipboardEntry> {
        if (!shouldCapture(text)) return current
        val cap = clampMaxUnpinned(maxUnpinned)
        val existing = current.find { it.text == text }
        val entry = if (existing != null) {
            existing.copy(createdAtMs = nowMs, isSensitive = existing.isSensitive || isSensitive)
        } else {
            ClipboardEntry(text = text, createdAtMs = nowMs, isSensitive = isSensitive)
        }
        val rest = current.filter { it.text != text }
        return prune(listOf(entry) + rest, nowMs, cap, maxPinned, ttlMs)
    }

    /**
     * Prune pass: drop expired unpinned entries, then FIFO-trim oldest
     * unpinned beyond [maxUnpinned] and oldest pinned beyond [maxPinned].
     * Result order is newest first.
     */
    fun prune(
        entries: List<ClipboardEntry>,
        nowMs: Long,
        maxUnpinned: Int = DEFAULT_MAX_UNPINNED,
        maxPinned: Int = DEFAULT_MAX_PINNED,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): List<ClipboardEntry> {
        val cap = clampMaxUnpinned(maxUnpinned)
        var kept = if (ttlMs > 0) {
            entries.filter { it.isPinned || nowMs - it.createdAtMs <= ttlMs }
        } else {
            entries.toList()
        }
        // FIFO by age: newest first, then cap each group.
        kept = kept.sortedByDescending { it.createdAtMs }
        val pinned = kept.filter { it.isPinned }.take(maxPinned.coerceAtLeast(0))
        val unpinned = kept.filter { !it.isPinned }.take(cap)
        return pinned + unpinned
    }

    /**
     * Unpins [entryId], refreshing createdAtMs so a just-unpinned item is
     * not instantly deleted by the TTL pass.
     */
    fun unpin(
        entries: List<ClipboardEntry>,
        entryId: String,
        nowMs: Long,
    ): List<ClipboardEntry> = entries.map { entry ->
        if (entry.id == entryId && entry.isPinned) {
            entry.copy(isPinned = false, createdAtMs = nowMs)
        } else {
            entry
        }
    }

    /** Single-line 64-char preview; sensitive entries show a bullet mask. */
    fun sanitizePreview(entry: ClipboardEntry): String {
        if (entry.isSensitive) return "••••••••"
        val singleLine = entry.text.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length > PREVIEW_CHARS) {
            singleLine.take(PREVIEW_CHARS)
        } else {
            singleLine
        }
    }

    /** Stable dedupe key for the primary-clip listener: label + text + mime. */
    fun clipFingerprint(label: CharSequence?, text: CharSequence?, mimeType: String?): Int {
        var h = 17
        h = 31 * h + (label?.toString()?.hashCode() ?: 0)
        h = 31 * h + (text?.toString()?.hashCode() ?: 0)
        h = 31 * h + (mimeType?.hashCode() ?: 0)
        return h
    }
}
