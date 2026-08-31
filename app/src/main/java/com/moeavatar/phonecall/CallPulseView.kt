package com.moeavatar.phonecall

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A center particle with a restrained expanding particle halo; never an audio bar graph. */
class CallPulseView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class Mode { WAITING, HEARING, SPEAKING, HIDDEN }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var level = 0f
    private var mode = Mode.WAITING
    private var phase = 0f

    fun update(level: Float, mode: Mode) {
        this.level = level.coerceIn(0f, 1f)
        this.mode = mode
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (mode == Mode.HIDDEN) return
        val speed = when (mode) {
            Mode.WAITING -> 0.045f
            Mode.HEARING -> 0.11f
            Mode.SPEAKING -> 0.16f
            Mode.HIDDEN -> 0f
        }
        phase += speed
        val density = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val breath = (sin(phase) + 1f) / 2f
        val response = when (mode) {
            Mode.HEARING -> level
            Mode.SPEAKING -> 0.70f
            else -> 0.18f
        }
        val spread = density * (7f + response * 13f + breath * 4f)
        val coreRadius = density * (3.3f + response * 2.2f + breath * 0.7f)
        paint.color = 0xFFFFD0E1.toInt()
        paint.alpha = 230
        canvas.drawCircle(cx, cy, coreRadius, paint)

        for (i in 0 until 8) {
            val angle = i * (2.0 * PI / 8.0) + phase * 0.22
            val wobble = 0.72f + 0.28f * ((sin(phase * 1.7f + i * 0.9f) + 1f) / 2f)
            val radius = spread * wobble
            val particleRadius = density * (1.15f + response * 0.75f)
            paint.color = if (i % 2 == 0) 0xFFE7C5FA.toInt() else 0xFFFFB6CF.toInt()
            paint.alpha = (55 + 130 * wobble).toInt()
            canvas.drawCircle(
                cx + cos(angle).toFloat() * radius,
                cy + sin(angle).toFloat() * radius,
                particleRadius,
                paint,
            )
        }
        postInvalidateOnAnimation()
    }
}
