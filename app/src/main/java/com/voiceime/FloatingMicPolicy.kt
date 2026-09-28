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
