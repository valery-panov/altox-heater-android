package io.github.altoxheater

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * Round button with a grey ring; with [corner] >= 0 a rectangle with corners that round, the
 * ring along its edge. A progress arc runs over the ring: a share from 0 to 1 in
 * [progress], or a spinning arc while [spinning]. Inside: [label] and below it the smaller
 * [sub], which may have several lines.
 *
 * With [holdMs] > 0 a tap does nothing but show [hint] instead of [sub] for a while: the click
 * comes after holding the button that long, while the ring fills. A finger that slides off the
 * button or a second finger cancels it, so a phone taken out of a pocket does not send a command.
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
    var holdMs = 0L
    var hint = ""
    /** Corner radius, px: a rounded rectangle; -1: a circle */
    var corner = -1f
        set(v) { field = v; buildEdge(width, height); invalidate() }

    private var holdFrom = 0L // 0: not held
    private var hintUntil = 0L
    private val held = Runnable {
        holdFrom = 0
        isPressed = false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        performClick()
    }

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
    /** The rectangle's ring, from the middle of the top edge clockwise, as the circle's arcs go */
    private val edge = Path()
    private val edgePart = Path()
    private val measure = PathMeasure()
    private var edgeLength = 0f

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (holdMs <= 0) return super.onTouchEvent(e)
        if (!isEnabled) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holdFrom = SystemClock.uptimeMillis()
                hintUntil = 0
                isPressed = true
                postDelayed(held, holdMs)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (holdFrom > 0 && !inside(e)) cancelHold()
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelHold()
            MotionEvent.ACTION_UP -> if (holdFrom > 0) {
                cancelHold()
                hintUntil = SystemClock.uptimeMillis() + HINT_MS
                postInvalidateDelayed(HINT_MS)
            }
        }
        return true
    }

    private fun inside(e: MotionEvent): Boolean {
        if (corner >= 0) return e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
        val r = minOf(width, height) / 2f
        val dx = e.x - width / 2f
        val dy = e.y - height / 2f
        return dx * dx + dy * dy <= r * r
    }

    private fun cancelHold() {
        removeCallbacks(held)
        holdFrom = 0
        isPressed = false
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        if (!enabled && holdFrom > 0) cancelHold()
        super.setEnabled(enabled)
    }

    override fun onDetachedFromWindow() {
        cancelHold()
        super.onDetachedFromWindow()
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = buildEdge(w, h)

    private fun buildEdge(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val half = ringWidth / 2f
        val l = half
        val t = half
        val r = w - half
        val b = h - half
        val c = corner.coerceIn(0f, minOf(r - l, b - t) / 2)
        edge.reset()
        edge.moveTo(w / 2f, t)
        edge.lineTo(r - c, t)
        edge.arcTo(RectF(r - 2 * c, t, r, t + 2 * c), -90f, 90f)
        edge.lineTo(r, b - c)
        edge.arcTo(RectF(r - 2 * c, b - 2 * c, r, b), 0f, 90f)
        edge.lineTo(l + c, b)
        edge.arcTo(RectF(l, b - 2 * c, l + 2 * c, b), 90f, 90f)
        edge.lineTo(l, t + c)
        edge.arcTo(RectF(l, t, l + 2 * c, t + 2 * c), 180f, 90f)
        edge.close()
        measure.setPath(edge, true)
        edgeLength = measure.length
    }

    /** [share] of the ring from [from] (0..1 of the way round), wrapping past the start */
    private fun drawRing(canvas: Canvas, from: Float, share: Float) {
        if (corner < 0) {
            canvas.drawArc(oval, -90f + 360f * from, 360f * share, false, arcPaint)
            return
        }
        val a = from * edgeLength
        val b = a + share * edgeLength
        edgePart.reset()
        measure.getSegment(a, minOf(b, edgeLength), edgePart, true)
        if (b > edgeLength) measure.getSegment(0f, b - edgeLength, edgePart, true)
        canvas.drawPath(edgePart, arcPaint)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - ringWidth / 2f

        fillPaint.color = if (isPressed) darker(fill) else fill
        ringPaint.strokeWidth = ringWidth
        if (corner < 0) {
            canvas.drawCircle(cx, cy, r, fillPaint)
            canvas.drawCircle(cx, cy, r, ringPaint)
        } else {
            canvas.drawPath(edge, fillPaint)
            canvas.drawPath(edge, ringPaint)
        }

        oval.set(cx - r, cy - r, cx + r, cy + r)
        arcPaint.strokeWidth = ringWidth
        if (holdFrom > 0) {
            val share = (SystemClock.uptimeMillis() - holdFrom).toFloat() / holdMs
            drawRing(canvas, 0f, share.coerceIn(0f, 1f))
            postInvalidateOnAnimation()
        } else if (spinning) {
            drawRing(canvas, (System.currentTimeMillis() % 1200) / 1200f, 0.25f)
            postInvalidateOnAnimation()
        } else if (progress >= 0) {
            drawRing(canvas, 0f, progress.coerceIn(0f, 1f))
        }

        labelPaint.textSize = labelSize
        subPaint.textSize = subSize
        val text = if (SystemClock.uptimeMillis() < hintUntil) hint else sub
        var lines = if (text.isEmpty()) emptyList() else text.split('\n')
        // A wide button: on one line, if it fits
        if (corner >= 0 && lines.size > 1) {
            val one = lines.joinToString(" ")
            if (subPaint.measureText(one) < width - 2 * maxOf(corner, ringWidth * 2)) lines = listOf(one)
        }
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

    /** The hint the last tap showed is still on, for tests */
    val hintShown get() = SystemClock.uptimeMillis() < hintUntil

    private fun darker(c: Int) =
        Color.rgb(Color.red(c) * 85 / 100, Color.green(c) * 85 / 100, Color.blue(c) * 85 / 100)

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        const val HINT_MS = 2_000L
    }
}
