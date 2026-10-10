package com.voiceime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * A dependency-free circular mic control for the accessibility overlay.
 *
 * It intentionally draws the glyph instead of inflating a themed button: an
 * accessibility overlay has no Activity theme, and framework/themed widgets
 * vary noticeably between OEMs. The view therefore has one deterministic
 * look in idle, recording, transcribing, and error states.
 */
class FloatingMicView(context: Context) : View(context) {

    enum class State { IDLE, RECORDING, TRANSCRIBING, ERROR }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(1.9f)
    }
    private val glyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val circle = RectF()
    private var state = State.IDLE

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Start recording"
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    fun setState(next: State) {
        if (state == next) return
        state = next
        contentDescription = when (next) {
            State.IDLE -> "Start recording"
            State.RECORDING -> "Stop recording"
            State.TRANSCRIBING -> "Cancel transcription"
            State.ERROR -> "Retry dictation"
        }
        invalidate()
    }

    fun state(): State = state

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val diameter = min(width, height).toFloat()
        val radius = diameter / 2f - dp(2f)
        val cx = width / 2f
        val cy = height / 2f
        circle.set(cx - radius, cy - radius, cx + radius, cy + radius)

        fillPaint.color = when (state) {
            State.IDLE -> Color.rgb(28, 28, 30)
            State.RECORDING -> Color.rgb(220, 68, 68)
            State.TRANSCRIBING -> Color.rgb(107, 107, 107)
            State.ERROR -> Color.rgb(183, 68, 50)
        }
        borderPaint.color = when (state) {
            State.IDLE -> Color.rgb(255, 0, 255)
            State.RECORDING -> Color.rgb(255, 220, 220)
            State.TRANSCRIBING -> Color.rgb(232, 234, 237)
            State.ERROR -> Color.rgb(255, 210, 180)
        }
        canvas.drawOval(circle, fillPaint)
        canvas.drawOval(circle, borderPaint)
        // Keep the touch target at 48 dp, but use a restrained glyph so the
        // icon reads as a small mic inside the compact floating control.
        drawMicGlyph(canvas, cx, cy, diameter * 0.17f)
    }

    private fun drawMicGlyph(canvas: Canvas, cx: Float, cy: Float, halfWidth: Float) {
        val top = cy - halfWidth * 1.45f
        val bottom = cy + halfWidth * 0.35f
        val capsule = RectF(cx - halfWidth * 0.55f, top, cx + halfWidth * 0.55f, bottom)
        canvas.drawRoundRect(capsule, halfWidth * 0.55f, halfWidth * 0.55f, glyphFillPaint)

        val cradle = RectF(
            cx - halfWidth,
            top + halfWidth * 0.55f,
            cx + halfWidth,
            bottom + halfWidth * 0.55f,
        )
        canvas.drawArc(cradle, 0f, 180f, false, glyphPaint)
        canvas.drawLine(cx, cradle.bottom, cx, cradle.bottom + halfWidth * 0.72f, glyphPaint)
        canvas.drawLine(cx - halfWidth * 0.7f, cradle.bottom + halfWidth * 0.72f, cx + halfWidth * 0.7f, cradle.bottom + halfWidth * 0.72f, glyphPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
