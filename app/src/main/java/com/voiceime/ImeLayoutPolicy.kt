package com.voiceime

/**
 * Pure, framework-free layout policy for large-screen / multi-window IME.
 *
 * Everything here is JVM-testable: window-size inputs in, layout decisions
 * out. The service feeds it the current window width (from the attached input
 * view or WindowManager metrics — never Display.getSize()) and applies the
 * results to views. Audio and key material never flow through this object.
 */
object ImeLayoutPolicy {

    /** Smallest-width breakpoint Matches values-sw600dp/. */
    const val TABLET_SMALLEST_WIDTH_DP = 600

    /** Default inline history previews when resources are unavailable. */
    const val DEFAULT_CLIP_LIMIT = 8

    /** Inline history previews in compact (narrow / multi-window) mode. */
    const val COMPACT_CLIP_LIMIT = 4

    /** Popup height cap as a fraction of the current input-view height. */
    const val POPUP_HEIGHT_FRACTION = 0.6f

    /**
     * Compact when the IME window is narrow. Services cannot call
     * Activity.isInMultiWindowMode, so a narrow window (< 600dp) is the
     * trigger — it covers split-screen, divider-drag shrinks, and small
     * DeX windows. The explicit [inMultiWindow] flag exists for callers
     * (and tests) that do know the windowing state.
     */
    fun isCompactMode(inMultiWindow: Boolean, windowWidthDp: Float): Boolean =
        inMultiWindow || windowWidthDp < TABLET_SMALLEST_WIDTH_DP

    /** Inline clip limit: resource base normally, capped low in compact mode. */
    fun clipLimit(baseLimit: Int, compact: Boolean, compactCap: Int = COMPACT_CLIP_LIMIT): Int {
        val base = baseLimit.coerceAtLeast(1)
        return if (compact) base.coerceAtMost(compactCap.coerceAtLeast(1)) else base
    }

    /**
     * Popup height: resource base, capped at [POPUP_HEIGHT_FRACTION] of the
     * current input-view height so the dialog never covers the whole IME.
     * Non-positive view height (not measured yet) falls back to the base.
     */
    fun popupHeightPx(
        baseHeightPx: Int,
        inputViewHeightPx: Int,
        fraction: Float = POPUP_HEIGHT_FRACTION,
    ): Int {
        val base = baseHeightPx.coerceAtLeast(1)
        if (inputViewHeightPx <= 0) return base
        return minOf(base, (inputViewHeightPx * fraction).toInt().coerceAtLeast(1))
    }

    /** Content-strip width: fill what is available, capped at the max width. */
    fun contentWidthPx(availablePx: Int, maxWidthPx: Int): Int {
        if (maxWidthPx <= 0) return availablePx.coerceAtLeast(0)
        if (availablePx <= 0) return maxWidthPx
        return minOf(availablePx, maxWidthPx)
    }

    /** Fullscreen extract UI: always off so large-screen landscape keeps app context. */
    fun fullscreenMode(): Boolean = false

    /**
     * Delay until the next whole-second boundary of the recording clock:
     * 1000 minus the millisecond part of [elapsedMs]. Keeps the timer
     * runnable posting once per displayed second instead of polling at a
     * fixed rate. Negative elapsed (clock moved) is clamped to zero, so it
     * schedules a full 1000ms interval rather than firing immediately.
     */
    fun nextTimerDelayMs(elapsedMs: Long): Long = 1000L - (elapsedMs.coerceAtLeast(0L) % 1000L)

    /**
     * Actions row: GONE unless at least one child button is visible.
     * The Cancel button shows mid-record/upload while Retry/Settings show on
     * error, so the row must follow child visibility — never state directly —
     * or a visible child ends up trapped inside a GONE parent.
     */
    fun actionsRowVisible(cancelVisible: Boolean, retryVisible: Boolean, settingsVisible: Boolean): Boolean =
        cancelVisible || retryVisible || settingsVisible

    /** Waveform bar geometry: bars capped at [maxBarPx] and centered. */
    data class BarGeometry(val slotW: Float, val barW: Float, val startX: Float)

    fun barGeometry(
        viewWidthPx: Float,
        barCount: Int,
        maxBarPx: Float,
        widthFraction: Float,
    ): BarGeometry {
        if (viewWidthPx <= 0f || barCount <= 0) return BarGeometry(0f, 0f, 0f)
        val naturalSlot = viewWidthPx / barCount
        val slotW = minOf(naturalSlot, maxBarPx.coerceAtLeast(1f))
        val barW = (slotW * widthFraction).coerceAtLeast(1f)
        val startX = ((viewWidthPx - slotW * barCount) / 2f).coerceAtLeast(0f)
        return BarGeometry(slotW, barW, startX)
    }

    /**
     * Idle waveform pulse for bar [barIndex] at sine [phase]: a slow
     * traveling ripple oscillating around [IDLE_RIPPLE_BASE] with amplitude
     * [IDLE_RIPPLE_DEPTH], so the idle hero breathes instead of sitting
     * static. Pure math — the view owns phase/step/cadence, this owns shape.
     */
    fun idleRippleFraction(phase: Float, barIndex: Int): Float =
        IDLE_RIPPLE_BASE + IDLE_RIPPLE_DEPTH *
            kotlin.math.sin(phase + barIndex * IDLE_RIPPLE_WAVE_STEP).toFloat()

    /** Idle pulse midpoint as a fraction of view height. */
    const val IDLE_RIPPLE_BASE = 0.06f

    /** Idle pulse swing around the base; base - depth stays a hairline. */
    const val IDLE_RIPPLE_DEPTH = 0.03f

    /** Per-bar phase offset so the pulse travels across the strip. */
    const val IDLE_RIPPLE_WAVE_STEP = 0.35f
}
