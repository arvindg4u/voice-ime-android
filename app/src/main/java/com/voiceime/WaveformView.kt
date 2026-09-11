package com.voiceime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Live amplitude waveform: 48 symmetric rounded bars around a center line.
 * Baseline ripple when idle; amplitude pushed from the recorder via
 * [setAmplitude]. No unit tests (framework-bound View); CI gate is compile +
 * existing tests staying green.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Ring of decaying per-bar levels; written by setAmplitude, read by onDraw. */
    private val levels = FloatArray(BAR_COUNT)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastAmplitude = 0f
    private var lastInvalidateMs = 0L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val a = context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorPrimary))
        try {
            barPaint.color = a.getColor(0, DEFAULT_BAR_COLOR)
        } finally {
            a.recycle()
        }
        barPaint.style = Paint.Style.FILL
    }

    /** Push a 0..1 amplitude; invalidates at most ~30fps and only on change > 0.02. */
    fun setAmplitude(amplitude: Float) {
        val clamped = amplitude.coerceIn(0f, 1f)
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(clamped - lastAmplitude) < INVALIDATE_THRESHOLD) return
        if (now - lastInvalidateMs < MIN_INVALIDATE_INTERVAL_MS) return
        lastAmplitude = clamped
        lastInvalidateMs = now
        // Shift ring left and append; newest level drives the rightmost bar.
        System.arraycopy(levels, 1, levels, 0, BAR_COUNT - 1)
        levels[BAR_COUNT - 1] = clamped
        invalidate()
    }

    /** Clear the ring back to baseline. */
    fun reset() {
        levels.fill(0f)
        lastAmplitude = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val centerY = h / 2f
        val slotW = w / BAR_COUNT
        val barW = (slotW * BAR_WIDTH_FRACTION).coerceAtLeast(1f)
        val radius = barW / 2f
        for (i in 0 until BAR_COUNT) {
            val level = levels[i]
            // Idle baseline ripple so the hero never looks dead.
            val ripple = if (level <= 0f) IDLE_RIPPLE_FRACTION else 0f
            val frac = (level * BAR_MAX_FRACTION + ripple).coerceAtMost(1f)
            val barH = (frac * h).coerceAtLeast(MIN_BAR_PX)
            val left = i * slotW + (slotW - barW) / 2f
            canvas.drawRoundRect(left, centerY - barH / 2f, left + barW, centerY + barH / 2f, radius, radius, barPaint)
        }
    }

    companion object {
        private const val BAR_COUNT = 48
        private const val DEFAULT_BAR_COLOR = 0xFF6750A4.toInt()
        private const val BAR_WIDTH_FRACTION = 0.55f
        private const val BAR_MAX_FRACTION = 0.92f
        private const val IDLE_RIPPLE_FRACTION = 0.06f
        private const val MIN_BAR_PX = 2f
        private const val INVALIDATE_THRESHOLD = 0.02f
        private const val MIN_INVALIDATE_INTERVAL_MS = 33L
    }
}
