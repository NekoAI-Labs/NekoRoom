package com.moeavatar.voiceinteraction

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.moeavatar.R
import kotlin.math.abs
import kotlin.math.sin

/** Compact microphone level visual kept entirely inside the existing glass capsule. */
class VoiceWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private var targetLevel = 0f
    private var displayLevel = 0f
    private var listening = false
    private var cancelArmed = false
    private var phase = 0f
    private var normalGradient: LinearGradient? = null
    private var cancelGradient: LinearGradient? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun update(level: Float, isListening: Boolean, isCancelArmed: Boolean) {
        targetLevel = level.coerceIn(0f, 1f)
        listening = isListening
        cancelArmed = isCancelArmed
        if (isListening) postInvalidateOnAnimation() else invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        normalGradient = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            ContextCompat.getColor(context, R.color.voice_blue),
            ContextCompat.getColor(context, R.color.voice_pink),
            Shader.TileMode.CLAMP,
        )
        cancelGradient = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            ContextCompat.getColor(context, R.color.voice_cancel),
            ContextCompat.getColor(context, R.color.voice_cancel_soft),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        displayLevel += (targetLevel - displayLevel) * 0.22f
        phase += 0.12f
        val colorStart = ContextCompat.getColor(context, if (cancelArmed) R.color.voice_cancel else R.color.voice_blue)
        paint.shader = if (cancelArmed) cancelGradient else normalGradient
        paint.strokeWidth = resources.displayMetrics.density * 2f
        paint.setShadowLayer(
            resources.displayMetrics.density * 3f,
            0f,
            0f,
            colorStart,
        )
        val count = 24
        val step = width / count.toFloat()
        val centerY = height / 2f
        val maxHalf = height * 0.38f
        for (i in 0 until count) {
            val position = i / (count - 1f)
            val envelope = 0.45f + 0.55f * sin(position * Math.PI).toFloat()
            val motion = abs(sin(phase + i * 0.67f))
            val breath = if (listening) 0.10f + 0.08f * motion else 0.08f
            val half = maxHalf * envelope * (breath + displayLevel * (0.45f + motion * 0.55f))
            val x = step * (i + 0.5f)
            canvas.drawLine(x, centerY - half, x, centerY + half, paint)
        }
        paint.clearShadowLayer()
        paint.shader = null
        if (listening) postInvalidateOnAnimation()
    }
}
