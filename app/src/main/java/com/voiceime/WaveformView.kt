package com.voiceime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Live amplitude waveform: 48 symmetric rounded bars around a center line.
 * Baseline pulse when idle (a slow sine ripple driven by the view itself);
 * amplitude pushed from the recorder via [setAmplitude]. The service owns
 * state — [reset] is the idle signal, pushed amplitudes the activity signal —
 * so this view adds no service coupling. Pulse math lives in
 * [ImeLayoutPolicy.idleRippleFraction] where it is JVM-testable. No unit
 * tests on the View itself (framework-bound); CI gate is compile + existing
 * tests staying green.
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
    /** Bar-width cap; resources.getDimension() must not run per onDraw. */
    private var maxBarPx = 0f
    /** True once reset() (the service's idle signal) clears live levels. */
    private var idle = true
    /** Sine phase of the idle pulse; advanced one step per idle frame. */
    private var idlePhase = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val a = context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.colorPrimary))
        try {
            barPaint.color = a.getColor(0, DEFAULT_BAR_COLOR)
        } finally {
            a.recycle()
        }
        barPaint.style = Paint.Style.FILL
        maxBarPx = context.resources.getDimension(R.dimen.voice_wave_bar_max_width)
    }

    /** Push a 0..1 amplitude; invalidates at most ~30fps and only on change > 0.02. */
    fun setAmplitude(amplitude: Float) {
        val clamped = amplitude.coerceIn(0f, 1f)
        // Any chunk means a live recorder, even a throttled-out one: stop the
        // idle pulse so recording never runs two frame loops at once. reset()
        // (the service's idle signal) restarts it.
        idle = false
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
        idle = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        maxBarPx = resources.getDimension(R.dimen.voice_wave_bar_max_width)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val centerY = h / 2f
        // Cap bar width and center the strip so wide tablets show grouped
        // bars instead of a sparse full-width stretch (ImeLayoutPolicy).
        val geo = ImeLayoutPolicy.barGeometry(w, BAR_COUNT, maxBarPx, BAR_WIDTH_FRACTION)
        val radius = geo.barW / 2f
        // MIN_BAR_PX is raw px by design (hairline floor on all densities).
        for (i in 0 until BAR_COUNT) {
            val level = levels[i]
            // Idle baseline pulse so the hero never looks dead.
            val ripple = if (level <= 0f) ImeLayoutPolicy.idleRippleFraction(idlePhase, i) else 0f
            val frac = (level * BAR_MAX_FRACTION + ripple).coerceAtMost(1f)
            val barH = (frac * h).coerceAtLeast(MIN_BAR_PX)
            val left = geo.startX + i * geo.slotW + (geo.slotW - geo.barW) / 2f
            canvas.drawRoundRect(left, centerY - barH / 2f, left + geo.barW, centerY + barH / 2f, radius, radius, barPaint)
        }
        if (idle) {
            idlePhase += IDLE_PHASE_STEP
            // Self-pulsing hero with no timer, handler, or service coupling:
            // each idle frame schedules the next, but only while actually
            // visible. The loop restarts on its own when visibility returns
            // (showing the view triggers a draw) and dies on hide/detach,
            // so there is nothing to cancel in onDetachedFromWindow.
            if (visibility == VISIBLE && isAttachedToWindow) {
                postInvalidateDelayed(IDLE_FRAME_DELAY_MS)
            }
        }
    }

    companion object {
        private const val BAR_COUNT = 48
        private const val DEFAULT_BAR_COLOR = 0xFF6750A4.toInt()
        private const val BAR_WIDTH_FRACTION = 0.55f
        private const val BAR_MAX_FRACTION = 0.92f
        private const val MIN_BAR_PX = 2f
        private const val INVALIDATE_THRESHOLD = 0.02f
        private const val MIN_INVALIDATE_INTERVAL_MS = 33L
        /** Idle pulse speed: ~2 s per sine cycle at the idle frame rate. */
        private const val IDLE_PHASE_STEP = 0.1f
        /** Idle frame cadence: 20fps is smooth for a slow pulse, cheaper than 30. */
        private const val IDLE_FRAME_DELAY_MS = 50L
    }
}
