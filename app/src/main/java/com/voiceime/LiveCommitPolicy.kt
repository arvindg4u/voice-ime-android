package com.voiceime

/**
 * Commit-text decision for a finished Live turn.
 *
 * The commit flow may only ever source text produced by the live transcript
 * callbacks (`onFinal` / `onInterim`). It must never fall back to a UI label:
 * by the time finalization settles, the status view can already hold a failure
 * message (e.g. "Network error — check connection"), and committing that would
 * type it into the focused editor, the clipboard, and history as if it were
 * dictated speech.
 *
 * Keeping the rule here rather than inside [VoiceImeService] makes it
 * JVM-testable and gives the service one explicit seam: there is no parameter
 * through which a status or error string can enter.
 */
internal object LiveCommitPolicy {

    /**
     * Returns the text to commit, or `""` when nothing may be committed.
     *
     * @param finalText accumulated final transcript parts (authoritative).
     * @param interimText last interim hypothesis. Used as a fallback when no
     *   final arrived — a quiet take can settle without a final turn event.
     * @param stillUploading true only while the stop flow still owns the
     *   commit. An error or cancel that took over during finalization must win
     *   over a late transcript instead of being overwritten by one.
     */
    fun resolveCommitText(
        finalText: String,
        interimText: String,
        stillUploading: Boolean,
    ): String {
        if (!stillUploading) return ""
        return finalText.trim().ifEmpty { interimText.trim() }
    }
}
