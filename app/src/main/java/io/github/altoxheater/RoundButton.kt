package io.github.altoxheater

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Round button with a grey ring. A progress arc runs over the ring: a share from 0 to 1 in
 * [progress], or a spinning arc while [spinning]. Inside: [label] and below it the smaller
 * [sub], which may have several lines.
 */
class RoundButton(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var fill = Color.GRAY
        set(v) { field = v; invalidate() }
    var label = ""
        set(v) { field = v; contentDescription = v; invalidate() }
    var sub = ""
        set(v) { field = v; invalidate() }
    /** -1: no arc */
    var progress = -1f
        set(v) { field = v; invalidate() }
    var spinning = false
        set(v) { field = v; invalidate() }

    var labelSize = sp(28f)
    var subSize = sp(15f)
    var ringWidth = dp(7f)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ctx.getColor(R.color.btn_ring)
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ctx.getColor(R.color.btn_progress)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val oval = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - ringWidth / 2f

        fillPaint.color = if (isPressed) darker(fill) else fill
        canvas.drawCircle(cx, cy, r, fillPaint)
        ringPaint.strokeWidth = ringWidth
        canvas.drawCircle(cx, cy, r, ringPaint)

        oval.set(cx - r, cy - r, cx + r, cy + r)
        arcPaint.strokeWidth = ringWidth
        if (spinning) {
            val start = (System.currentTimeMillis() % 1200) * 360f / 1200 - 90
            canvas.drawArc(oval, start, 90f, false, arcPaint)
            postInvalidateOnAnimation()
        } else if (progress >= 0) {
            canvas.drawArc(oval, -90f, 360f * progress.coerceIn(0f, 1f), false, arcPaint)
        }

        labelPaint.textSize = labelSize
        subPaint.textSize = subSize
        val lines = if (sub.isEmpty()) emptyList() else sub.split('\n')
        val subLine = subSize * 1.25f
        val total = labelSize + lines.size * subLine
        var y = cy - total / 2 + labelSize * 0.8f
        canvas.drawText(label, cx, y, labelPaint)
        y += labelSize * 0.2f
        for (line in lines) {
            y += subLine
            canvas.drawText(line, cx, y - subSize * 0.2f, subPaint)
        }
    }

    private fun darker(c: Int) =
        Color.rgb(Color.red(c) * 85 / 100, Color.green(c) * 85 / 100, Color.blue(c) * 85 / 100)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
