package com.jarvis.mobile

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The JARVIS HUD background — an animated arc-reactor that sits behind the UI so the app
 * feels alive (Iron-Man grade). Pure Canvas, no bitmaps, no blur masks, so it stays cheap on
 * low-RAM devices. Rotating rings + orbiting particles + scanlines, cyan on near-black.
 */
class HudView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val cyan = Color.parseColor("#00E5FF")
    private val cyanDim = Color.parseColor("#2A00E5FF")
    private val teal = Color.parseColor("#7CFFE9")

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var rot = 0f
    private var rot2 = 360f

    private val animA = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 13000; interpolator = LinearInterpolator(); repeatCount = ValueAnimator.INFINITE
        addUpdateListener { rot = it.animatedValue as Float; invalidate() }
    }
    private val animB = ValueAnimator.ofFloat(360f, 0f).apply {
        duration = 21000; interpolator = LinearInterpolator(); repeatCount = ValueAnimator.INFINITE
        addUpdateListener { rot2 = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animA.isStarted) { animA.start(); animB.start() }
    }

    override fun onDetachedFromWindow() {
        animA.cancel(); animB.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h * 0.30f            // keep the reactor up top so the controls below stay readable
        val base = minOf(w, h)

        fill.color = Color.parseColor("#00090E")
        canvas.drawRect(0f, 0f, w, h, fill)

        // concentric reactor rings: soft glow ring + rotating arc segments
        val radii = floatArrayOf(base * 0.17f, base * 0.235f, base * 0.31f)
        for (i in radii.indices) {
            val r = radii[i]
            stroke.color = cyanDim
            stroke.strokeWidth = base * 0.022f
            canvas.drawCircle(cx, cy, r, stroke)
            stroke.color = cyan
            stroke.strokeWidth = base * 0.009f
            val a = if (i % 2 == 0) rot else rot2
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, a, 68f, false, stroke)
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, a + 180f, 40f, false, stroke)
        }

        // core
        stroke.color = teal; stroke.strokeWidth = base * 0.012f
        canvas.drawCircle(cx, cy, base * 0.105f, stroke)
        fill.color = Color.parseColor("#3300E5FF")
        canvas.drawCircle(cx, cy, base * 0.095f, fill)
        fill.color = Color.parseColor("#D8FAFE")
        canvas.drawCircle(cx, cy, base * 0.038f, fill)

        // orbiting particles
        val orbit = base * 0.27f
        for (k in 0 until 6) {
            val ang = Math.toRadians((rot * 2f + k * 60f).toDouble())
            fill.color = cyan
            canvas.drawCircle(cx + orbit * Math.cos(ang).toFloat(),
                cy + orbit * Math.sin(ang).toFloat(), base * 0.0065f, fill)
        }

        // scanlines
        stroke.color = Color.parseColor("#0D00E5FF"); stroke.strokeWidth = 1f
        var y = 0f
        val step = base * 0.021f
        while (y < h) { canvas.drawLine(0f, y, w, y, stroke); y += step }

        // HUD corner brackets — the framing that makes it read as a heads-up display
        stroke.color = Color.parseColor("#8000E5FF"); stroke.strokeWidth = base * 0.007f
        val m = base * 0.045f
        val arm = base * 0.10f
        canvas.drawLine(m, m, m + arm, m, stroke);             canvas.drawLine(m, m, m, m + arm, stroke)
        canvas.drawLine(w - m, m, w - m - arm, m, stroke);     canvas.drawLine(w - m, m, w - m, m + arm, stroke)
        canvas.drawLine(m, h - m, m + arm, h - m, stroke);     canvas.drawLine(m, h - m, m, h - m - arm, stroke)
        canvas.drawLine(w - m, h - m, w - m - arm, h - m, stroke); canvas.drawLine(w - m, h - m, w - m, h - m - arm, stroke)
    }
}
