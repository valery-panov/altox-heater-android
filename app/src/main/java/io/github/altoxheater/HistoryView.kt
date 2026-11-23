package io.github.altoxheater

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The coolant and the cabin over the last hours: the runs as bands, the readings as dots joined by
 * lines, the cooling forecast as a dashed line from the last reading to now, its end labelled.
 * The window: from half an hour before the last run (or the last reading), at least WINDOW_MIN
 * long, at most WINDOW_MAX, up to now or the planned end of the run under way.
 */
class HistoryView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var readings: List<History.Reading> = emptyList()
    var runs: List<History.Run> = emptyList()
    var trips: List<History.Trip> = emptyList()
    var forecast: History.Forecast? = null
    var now = 0L

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density

    private val coolantColor = ctx.getColor(R.color.btn_on)
    private val cabinColor = ctx.getColor(R.color.btn_status)
    private val band = Paint().apply { color = ctx.getColor(R.color.chart_run) }
    private val failedBand = Paint().apply { color = ctx.getColor(R.color.chart_run_failed) }
    private val engineBand = Paint().apply { color = ctx.getColor(R.color.chart_engine) }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.btn_disabled); strokeWidth = dp(1f) }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.text_secondary)
        textSize = 11 * scaled
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = coolantColor
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = coolantColor
        textSize = 12 * scaled
        isFakeBoldText = true
    }

    /** A run under way: up to its planned end, so its band shows how much is left */
    fun window(): Pair<Long, Long> {
        val anchor = runs.lastOrNull()?.start ?: readings.lastOrNull()?.t ?: now
        val open = runs.lastOrNull()?.takeIf { it.end == 0L }
        val to = maxOf(now, open?.let { it.start + it.minutes * 60_000L } ?: 0)
        val from = minOf(anchor - 30 * 60_000L, to - WINDOW_MIN).coerceAtLeast(now - WINDOW_MAX)
        return from to to
    }

    override fun onDraw(c: Canvas) {
        val (from, to) = window()
        val shown = readings.filter { it.t in from..to }
        val f = forecast
        val curve = if (f != null) (0..CURVE_STEPS).map { i ->
            val t = maxOf(f.from.t, from) + (now - maxOf(f.from.t, from)) * i / CURVE_STEPS
            t to History.cool(f.from.coolant!!, f.ambient, t - f.from.t, f.tau)
        } else emptyList()
        val values = shown.flatMap { listOfNotNull(it.coolant, it.cabin) } + curve.map { it.second }
        if (values.isEmpty()) return
        var lo = floor(values.min() / 10) * 10
        var hi = ceil(values.max() / 10) * 10
        if (hi - lo < 20) hi = lo + 20

        val left = axis.measureText("-00°") + dp(6f)
        val right = width - dp(30f)
        val top = dp(8f)
        val bottom = height - axis.textSize - dp(6f)
        fun x(t: Long) = left + (right - left) * (t - from).toFloat() / maxOf(to - from, 1)
        fun y(v: Double) = (bottom - (bottom - top) * (v - lo) / (hi - lo)).toFloat()

        // Runs
        for (r in runs) {
            val end = if (r.end > 0) r.end else maxOf(now, r.start + r.minutes * 60_000L)
            if (end < from) continue
            c.drawRect(x(maxOf(r.start, from)), top, maxOf(x(end), x(maxOf(r.start, from)) + dp(2f)), bottom,
                if (r.end > 0 && !r.ok) failedBand else band)
        }
        // Trips: a reading shows only that the engine ran by then, so at least a few dp around it
        for (t in trips) {
            if (t.end < from) continue
            val l = x(maxOf(t.start, from))
            c.drawRect(l - dp(3f), top, maxOf(x(t.end), l) + dp(3f), bottom, engineBand)
        }
        // Grid: 3 lines, °C at the left
        val step = ((hi - lo) / 2)
        for (i in 0..2) {
            val v = lo + step * i
            val yy = y(v)
            c.drawLine(left, yy, right, yy, grid)
            c.drawText("${v.roundToInt()}°", 0f, yy + axis.textSize / 3, axis)
        }
        // Times along the bottom, at most 6 labels: every half hour on a short window, else on whole hours
        val hours = (to - from) / 3_600_000.0
        val every = when {
            hours <= 2 -> 30
            hours <= 5 -> 60
            hours <= 10 -> 120
            hours <= 15 -> 180
            else -> 360
        }
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        axis.textAlign = Paint.Align.CENTER
        while (cal.timeInMillis < to - 10 * 60_000L) {
            val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            if (cal.timeInMillis > from + 10 * 60_000L && m % every == 0)
                c.drawText("%d:%02d".format(m / 60, m % 60), x(cal.timeInMillis), height.toFloat() - dp(2f), axis)
            cal.add(Calendar.MINUTE, 30)
        }
        axis.textAlign = Paint.Align.LEFT

        series(c, shown.mapNotNull { r -> r.cabin?.let { r.t to it } }, cabinColor, ::x, ::y)
        series(c, shown.mapNotNull { r -> r.coolant?.let { r.t to it } }, coolantColor, ::x, ::y)

        if (curve.isNotEmpty()) {
            val p = Path()
            curve.forEachIndexed { i, (t, v) -> if (i == 0) p.moveTo(x(t), y(v)) else p.lineTo(x(t), y(v)) }
            c.drawPath(p, dashed)
            val (t, v) = curve.last()
            dot.color = coolantColor
            c.drawCircle(x(t), y(v), dp(4f), dot)
            val text = "≈${v.roundToInt()}°"
            val ly = (y(v) - dp(7f)).coerceAtLeast(top + label.textSize)
            c.drawText(text, (x(t) - label.measureText(text) / 2).coerceAtMost(width - label.measureText(text)), ly, label)
        }
    }

    private fun series(c: Canvas, pts: List<Pair<Long, Double>>, color: Int, x: (Long) -> Float, y: (Double) -> Float) {
        if (pts.isEmpty()) return
        line.color = color
        dot.color = color
        val p = Path()
        pts.forEachIndexed { i, (t, v) -> if (i == 0) p.moveTo(x(t), y(v)) else p.lineTo(x(t), y(v)) }
        c.drawPath(p, line)
        for ((t, v) in pts) c.drawCircle(x(t), y(v), dp(3f), dot)
    }

    companion object {
        const val WINDOW_MIN = 3_600_000L
        const val WINDOW_MAX = 24 * 3_600_000L
        private const val CURVE_STEPS = 40
    }
}
