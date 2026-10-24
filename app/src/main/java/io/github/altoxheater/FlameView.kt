package io.github.altoxheater

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * The flame icon, the shapes of ic_flame in a 40x40 box: the outer flame, the middle one and the
 * core at the wick. [mode] picks the picture:
 *   OFF       the outer outline
 *   IGNITING  the outline, the core rises from the wick again and again, flickering
 *   BURNING   all three filled, swaying and stretching slowly, each at its own pace
 *   STOPPING  the outline, the core sinks back into the wick, greyish: ignition in reverse
 *   ERROR     the outline with a question mark over it
 */
class FlameView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    enum class Mode { OFF, IGNITING, BURNING, STOPPING, ERROR }

    var mode = Mode.OFF
        set(v) {
            if (field == v) return
            field = v
            contentDescription = when (v) {
                Mode.IGNITING -> "Розжиг"
                Mode.BURNING -> "Пламя есть"
                Mode.STOPPING -> "Выключение"
                Mode.ERROR -> "Неизвестно, выключен ли отопитель"
                Mode.OFF -> "Пламени нет"
            }
            invalidate()
        }

    private val outer = path("M20,2 C23,9 33,14 33,25 C33,32.5 27.2,38 20,38 C12.8,38 7,32.5 7,25 C7,19 10,15.5 13,12.5 C13,17.5 15,20.5 17.5,21.5 C16,14.5 16.5,7.5 20,2 Z")
    private val middle = path("M20,18 C22.5,22 27,24.5 27,29.5 C27,33.6 23.9,37 20,37 C16.1,37 13,33.6 13,29.5 C13,25.5 16.5,22.5 20,18 Z")
    private val core = path("M20,27 C21.5,29 23.5,30.5 23.5,33 C23.5,35.2 21.9,36.8 20,36.8 C18.1,36.8 16.5,35.2 16.5,33 C16.5,31 18,29.3 20,27 Z")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        strokeJoin = Paint.Join.ROUND
        color = OUTLINE
    }
    private val question = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ctx.getColor(R.color.error)
        textAlign = Paint.Align.CENTER
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        contentDescription = "Пламени нет"
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) / 40f
        canvas.save()
        canvas.translate((width - 40 * s) / 2, (height - 40 * s) / 2)
        canvas.scale(s, s)
        val t = SystemClock.uptimeMillis() / 1000.0
        when (mode) {
            Mode.OFF -> canvas.drawPath(outer, outline)
            Mode.ERROR -> {
                canvas.drawPath(outer, outline)
                canvas.drawText("?", 20f, 34.5f, question)
            }
            Mode.BURNING -> {
                // Sums of sines with unrelated periods: smooth, and never quite the same twice
                layer(canvas, outer, 0xFFF4511E.toInt(), 255,
                    1 + 0.04f * wave(t, 1.7, 2.9), 0.035f * wave(t, 2.3, 3.7))
                layer(canvas, middle, 0xFFFFB300.toInt(), 255,
                    1 + 0.07f * wave(t + 0.6, 1.3, 2.1), 0.05f * wave(t + 1.1, 1.9, 3.1))
                layer(canvas, core, 0xFFFFF3C4.toInt(), 255,
                    1 + 0.10f * wave(t + 0.3, 0.9, 1.6), 0.04f * wave(t + 0.4, 1.4, 2.3))
                postInvalidateOnAnimation()
            }
            Mode.IGNITING -> {
                canvas.drawPath(outer, outline)
                // Rises from the wick over a cycle and flickers on the way
                val p = (t % CYCLE / CYCLE).toFloat()
                val flicker = 0.75f + 0.25f * (sin(t * 52) * sin(t * 21)).toFloat()
                val alpha = (minOf(p * 4, 1f) * flicker * 255).toInt()
                layer(canvas, core, 0xFFFFB300.toInt(), alpha, 0.3f + 0.9f * p, 0f, 0.5f + 0.5f * p)
                postInvalidateOnAnimation()
            }
            Mode.STOPPING -> {
                canvas.drawPath(outer, outline)
                // The same cycle backwards: from full size down into the wick, fading
                val p = (t % CYCLE / CYCLE).toFloat()
                val flicker = 0.8f + 0.2f * (sin(t * 37) * sin(t * 17)).toFloat()
                val alpha = (minOf((1 - p) * 4, 1f) * flicker * 255).toInt()
                val k = 1 - p
                layer(canvas, core, STOPPING_GREY, alpha, 0.3f + 0.9f * k, 0f, 0.5f + 0.5f * k)
                postInvalidateOnAnimation()
            }
        }
        canvas.restore()
    }

    /** [path] stretched by [scaleY] and leaned by [lean] around the wick, [scaleX] across */
    private fun layer(canvas: Canvas, path: Path, color: Int, alpha: Int, scaleY: Float, lean: Float, scaleX: Float = 1f) {
        canvas.save()
        canvas.translate(WICK_X, WICK_Y)
        canvas.skew(lean, 0f)
        canvas.scale(scaleX, scaleY)
        canvas.translate(-WICK_X, -WICK_Y)
        fill.color = color
        fill.alpha = alpha.coerceIn(0, 255)
        canvas.drawPath(path, fill)
        canvas.restore()
    }

    private fun wave(t: Double, a: Double, b: Double) =
        (0.6 * sin(2 * PI * t / a) + 0.4 * sin(2 * PI * t / b)).toFloat()

    companion object {
        private const val WICK_X = 20f
        private const val WICK_Y = 38f
        private const val CYCLE = 1.1 // s, one rise or sink of the core
        private const val OUTLINE = 0xFF8A9096.toInt()
        private const val STOPPING_GREY = 0xFFB0B6BC.toInt()

        /** Absolute M, C and Z only, as in ic_flame */
        private fun path(d: String): Path {
            val p = Path()
            val n = Regex("""[MCZ]|-?[\d.]+""").findAll(d).map { it.value }.toList()
            var i = 0
            fun f() = n[i++].toFloat()
            while (i < n.size) {
                when (n[i++]) {
                    "M" -> p.moveTo(f(), f())
                    "C" -> p.cubicTo(f(), f(), f(), f(), f(), f())
                    "Z" -> p.close()
                }
            }
            return p
        }
    }
}
