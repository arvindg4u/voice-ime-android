package com.voiceime

/**
 * Pure policy and geometry for the system floating dictation button.
 *
 * Keeping these decisions out of [FloatingMicAccessibilityService] makes the
 * security-sensitive visibility rules and drag/peek math straightforward to
 * unit test without a device or an accessibility framework.
 */
object FloatingMicPolicy {
    const val PEEK_VISIBLE_DP = 20
    const val PEEK_DELAY_MS = 4_000L
    /** Keep the edge peek brief, then restore the full button for discoverability. */
    const val PEEK_RESTORE_DELAY_MS = 700L
    const val PEEK_ANIMATION_MS = 220L
    const val TAP_THRESHOLD_DP = 10
    const val LONG_PRESS_MS = 500L

    /**
     * The overlay is only offered for a focused, editable, non-password
     * control. Accessibility services can see password fields, but a voice
     * button must never invite audio capture over one accidentally.
     */
    fun shouldShowForEditor(
        isEditable: Boolean,
        isFocused: Boolean,
        isPassword: Boolean,
        packageName: String?,
        ownPackageName: String,
    ): Boolean =
        isEditable && isFocused && !isPassword && packageName != ownPackageName

    /**
     * AccessibilityNodeInfo implementations sometimes expose an editor hint
     * through text. Android 8+ also marks that case with isShowingHintText,
     * but some apps omit hintText while still setting the flag. Never copy
     * either representation of a placeholder into the user's field.
     */
    fun textForInsertion(
        text: String?,
        hintText: String?,
        showingHintText: Boolean = false,
    ): String? {
        if (text == null) return null
        return if (showingHintText || (!hintText.isNullOrEmpty() && text == hintText)) "" else text
    }

    /**
     * Merges the authoritative final prefix and the currently changing interim
     * hypothesis for a live preview. Interims can be either the whole running
     * hypothesis or only the next segment, so remove containment and the
     * longest suffix/prefix overlap before joining them.
     */
    fun mergeLivePreview(finalText: String, interimText: String): String {
        val final = finalText.trim()
        val interim = interimText.trim()
        if (final.isEmpty()) return interim
        if (interim.isEmpty()) return final
        if (final.contains(interim)) return final
        if (interim.contains(final)) return interim
        val max = minOf(final.length, interim.length)
        for (overlap in max downTo 1) {
            if (final.endsWith(interim.substring(0, overlap))) {
                return final + interim.substring(overlap)
            }
        }
        return "$final $interim"
    }

    /**
     * Keeps the newest part of a long live preview visible in the compact
     * bubble. The leading side rolls away as new words arrive.
     */
    fun livePreviewTail(text: String, maxChars: Int): String {
        val limit = maxChars.coerceAtLeast(0)
        val tail = text.takeLast(limit)
        if (text.length <= limit) return tail.trimStart()
        val boundary = tail.indexOfFirst { it.isWhitespace() }
        return if (boundary >= 0) tail.substring(boundary).trimStart() else tail.trimStart()
    }

    /** Keep the stop/cancel affordance reachable if focus disappears mid-session. */
    fun shouldKeepVisibleWhileBusy(isBusy: Boolean): Boolean = isBusy

    fun isRightDocked(x: Int, screenWidth: Int, windowSize: Int): Boolean =
        x + windowSize / 2 > screenWidth / 2

    /**
     * Returns the x coordinate that leaves [visiblePx] of the actual button on
     * screen. The window is allowed outside display bounds by
     * FLAG_LAYOUT_NO_LIMITS, which is what makes the peek animation real
     * rather than a no-op caused by WindowManager clamping.
     */
    fun peekedX(
        x: Int,
        screenWidth: Int,
        windowSize: Int,
        visiblePx: Int,
        insetPx: Int = 0,
    ): Int = if (isRightDocked(x, screenWidth, windowSize)) {
        screenWidth - visiblePx - insetPx
    } else {
        visiblePx - windowSize + insetPx
    }

    fun dockedX(x: Int, screenWidth: Int, windowSize: Int, marginPx: Int): Int =
        if (isRightDocked(x, screenWidth, windowSize)) {
            (screenWidth - windowSize - marginPx).coerceAtLeast(marginPx)
        } else {
            marginPx
        }

    fun clampY(y: Int, screenHeight: Int, windowSize: Int, marginPx: Int): Int =
        y.coerceIn(marginPx, (screenHeight - windowSize - marginPx).coerceAtLeast(marginPx))
}
