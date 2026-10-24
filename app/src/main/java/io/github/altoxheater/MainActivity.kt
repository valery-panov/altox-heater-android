package io.github.altoxheater

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import kotlin.math.sin

class MainActivity : Activity() {
    private lateinit var power: RoundButton
    private lateinit var caption: TextView
    private lateinit var message: TextView
    private lateinit var minutesTitle: TextView
    private lateinit var minutesLabel: TextView
    private lateinit var minutes: SeekBar
    private lateinit var smsLabel: TextView
    private lateinit var status: RoundButton
    private lateinit var statusBlock: View
    private lateinit var flame: ImageView
    private lateinit var statusInfo: TextView
    private lateinit var statusTime: TextView

    private val prefs by lazy { Heater.prefs(this) }
    private var sliderEditable = false
    private val handler = Handler(Looper.getMainLooper())
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }
    private val frame = object : Runnable {
        override fun run() {
            Heater.tick(this@MainActivity)
            renderFrame()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        power = findViewById(R.id.power)
        caption = findViewById(R.id.caption)
        message = findViewById(R.id.message)
        minutesTitle = findViewById(R.id.minutesTitle)
        minutesLabel = findViewById(R.id.minutesLabel)
        minutes = findViewById(R.id.minutes)
        smsLabel = findViewById(R.id.smsLabel)
        status = findViewById(R.id.status)
        statusBlock = findViewById(R.id.statusBlock)
        flame = findViewById(R.id.flame)
        statusInfo = findViewById(R.id.statusInfo)
        statusTime = findViewById(R.id.statusTime)

        status.label = getString(R.string.status)
        status.labelSize = power.subSize * 1.1f
        status.subSize = power.subSize * 0.85f
        // Against taps from a pocket or a stray finger: a command only after holding the button
        for (b in listOf(power, status)) b.holdMs = HOLD_MS
        power.hint = "удерживайте\n1 секунду"
        status.hint = "удерживайте"

        findViewById<TextView>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        power.setOnClickListener {
            if (!ensureSmsPermissions()) return@setOnClickListener
            when (prefs.getString(Heater.STATE, Heater.OFF)) {
                Heater.OFF -> Heater.start(this)
                Heater.ON -> Heater.stop(this)
            }
        }
        status.setOnClickListener {
            if (ensureSmsPermissions()) Heater.requestStatus(this)
        }

        // While heating the slider shows the part of the run left, see renderFrame()
        minutes.max = (Heater.RUN_MINUTES.size - 1) * STEP
        minutes.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) prefs.edit().putInt(Heater.MINUTES, Heater.RUN_MINUTES[(progress + STEP / 2) / STEP]).apply()
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {
                bar.progress = position(Heater.minutes(this@MainActivity).toDouble())
            }
        })
        // Looks active all the time (it shows the time left), but takes touches only when idle
        minutes.setOnTouchListener { _, _ -> !sliderEditable }

        // READ_SMS: a reply that came while the app was stopped, see Heater.catchUp(); notifications: HeaterService's
        val wanted = listOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // Before the first tick(): it would time out the STATUS whose reply is already in the inbox
        Heater.catchUp(this)
        Heater.scheduleWake(this)
        render()
        handler.post(frame)
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacks(frame)
    }

    private fun ensureSmsPermissions(): Boolean {
        val missing = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        requestPermissions(missing.toTypedArray(), 1)
        return false
    }

    /** Everything that changes with the state; the ticking parts are in renderFrame */
    private fun render() {
        val configured = Heater.isConfigured(this)
        val state = prefs.getString(Heater.STATE, Heater.OFF)
        val pending = Heater.isPending(state)
        // A manual status request; while a command runs, STATUS is part of it
        val statusBusy = !pending && prefs.getLong(Heater.STATUS_SENT, 0) > 0
        // A periodic STATUS while heating does not lock Stop
        val powerLocked = statusBusy && !prefs.getBoolean(Heater.STATUS_AUTO, false)
        // Still on after the end and our STOP; a tap sends STOP again
        val error = state == Heater.ON && prefs.getBoolean(Heater.ERROR, false)

        power.label = when {
            state == Heater.STARTING -> "Запуск..."
            error -> "Ошибка"
            state == Heater.ON -> "Стоп"
            state == Heater.STOPPING -> "Остановка..."
            else -> "Пуск"
        }
        power.fill = getColor(when {
            !configured -> R.color.btn_disabled
            pending -> R.color.btn_pending
            error -> R.color.error
            state == Heater.ON -> R.color.btn_on
            else -> R.color.btn_off
        })
        power.isEnabled = configured && !pending && !powerLocked
        power.alpha = if (configured && powerLocked) 0.5f else 1f

        status.fill = getColor(when {
            !configured -> R.color.btn_disabled
            statusBusy -> R.color.btn_pending
            else -> R.color.btn_status
        })
        status.isEnabled = configured && !pending && !statusBusy
        status.alpha = if (configured && pending) 0.5f else 1f

        caption.text = when {
            !configured -> "Укажите номер Altox в настройках"
            state == Heater.STARTING -> "Запуск отопителя"
            state == Heater.STOPPING -> "Остановка отопителя"
            error -> "Отопитель не выключается"
            state == Heater.ON -> "Нагрев запущен"
            prefs.contains(Heater.PREV_STATE) -> "Нагрев выключен"
            else -> ""
        }
        message.text = prefs.getString(Heater.MESSAGE, "")

        sliderEditable = configured && state == Heater.OFF && !statusBusy
        minutes.isEnabled = configured
        val tint = ColorStateList.valueOf(getColor(if (state == Heater.ON) R.color.btn_on else R.color.accent))
        minutes.progressTintList = tint
        minutes.thumbTintList = tint
        if (state != Heater.ON) {
            minutesTitle.text = "Время работы"
            minutesLabel.text = "${Heater.minutes(this)} мин"
            minutes.progress = position(Heater.minutes(this).toDouble())
        }

        smsLabel.visibility = if (configured) View.VISIBLE else View.INVISIBLE
        smsLabel.text = Heater.smsText(Heater.periodic(this))

        val time = prefs.getLong(Heater.STATUS_TIME, 0)
        statusBlock.visibility = if (time == 0L && !igniting()) View.INVISIBLE else View.VISIBLE
        if (time > 0) {
            fun value(key: String, unit: String) =
                prefs.getString(key, "").let { if (it.isNullOrEmpty()) "—" else "$it $unit" }
            statusInfo.text = "${value(Heater.COOLANT, "°C")}\n" +
                "${value(Heater.CABIN, "°C")}\n" +
                value(Heater.VOLTAGE, "В")
        }
        val burning = prefs.getBoolean(Heater.FLAME, false)
        flame.setImageResource(if (burning || igniting()) R.drawable.ic_flame else R.drawable.ic_flame_off)
        flame.contentDescription = when {
            igniting() -> "Розжиг"
            burning -> "Пламя есть"
            else -> "Пламени нет"
        }
        renderFrame()
    }

    /** Timers and progress rings, 10 times a second */
    private fun renderFrame() {
        val now = Heater.now()
        val state = prefs.getString(Heater.STATE, Heater.OFF)
        val statusSent = prefs.getLong(Heater.STATUS_SENT, 0)

        when (state) {
            Heater.STARTING, Heater.STOPPING -> {
                val pollAt = prefs.getLong(Heater.POLL_AT, 0)
                when {
                    prefs.getLong(Heater.CMD_SENT_AT, 0) == 0L -> spin(power, "отправка SMS")
                    pollAt > 0 -> {
                        val from = prefs.getLong(Heater.POLL_FROM, now)
                        power.spinning = false
                        power.progress = (now - from).toFloat() / maxOf(pollAt - from, 1)
                        power.sub = "статус через\n${seconds(pollAt - now)} с"
                    }
                    statusSent > 0 -> spin(power, "ждём ответ\n${(now - statusSent) / 1000} с")
                    else -> spin(power, "")
                }
            }
            Heater.ON -> {
                power.spinning = false
                power.sub = if (prefs.getBoolean(Heater.ERROR, false)) "удерживайте,\nчтобы\u00A0остановить" else ""
                val endsAt = prefs.getLong(Heater.ENDS_AT, 0)
                val total = prefs.getLong(Heater.RUN_TOTAL, 0)
                if (endsAt > 0 && total > 0) {
                    val left = maxOf(endsAt - now, 0)
                    power.progress = left.toFloat() / total
                    minutesTitle.text = "Осталось"
                    minutesLabel.text = String.format(Locale.US, "%02d мин : %04.1f сек",
                        left / 60_000, left % 60_000 / 1000.0)
                    // The whole width is this run: from the right edge at the start to the left at the end
                    minutes.progress = (minutes.max * left / total).toInt()
                    // The run is over, the STATUS after it tells whether the heater is off
                    if (left == 0L) spin(power, if (statusSent > 0) "ждём ответ\n${(now - statusSent) / 1000} с" else "")
                } else {
                    // Started elsewhere, the run time is unknown
                    power.progress = -1f
                    minutesTitle.text = "Работает"
                    minutesLabel.text = clock(now - prefs.getLong(Heater.ON_SINCE, now))
                }
            }
            else -> {
                power.spinning = false
                power.progress = -1f
                power.sub = ""
            }
        }

        val time = prefs.getLong(Heater.STATUS_TIME, 0)
        if (time > 0) statusTime.text = "Обновлено: " + whenText(time, now)
        // Flickers until the heater reaches FLAME: YES
        flame.alpha = if (igniting()) 0.6f + 0.4f * sin(now / 120.0).toFloat() * sin(now / 47.0).toFloat() else 1f

        if (!Heater.isPending(state) && statusSent > 0) spin(status, "${(now - statusSent) / 1000} с")
        else {
            status.spinning = false
            status.sub = ""
        }
    }

    private fun igniting() = prefs.getBoolean(Heater.IGNITING, false) &&
        prefs.getString(Heater.STATE, Heater.OFF).let { it == Heater.STARTING || it == Heater.ON }

    private fun spin(b: RoundButton, sub: String) {
        b.progress = -1f
        b.spinning = true
        b.sub = sub
    }

    /** Countdown in whole seconds, rounded up */
    private fun seconds(ms: Long) = (maxOf(ms, 0) + 999) / 1000

    private fun clock(ms: Long): String {
        val s = maxOf(ms, 0) / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    /** Slider position for a run time in minutes, between the steps of the scale */
    private fun position(minutes: Double): Int {
        val steps = Heater.RUN_MINUTES
        if (minutes <= steps.first()) return 0
        val i = steps.indexOfLast { it <= minutes }
        if (i == steps.size - 1) return i * STEP
        val part = (minutes - steps[i]) / (steps[i + 1] - steps[i])
        return ((i + part) * STEP).toInt()
    }

    /** "только что", "15 с назад", "23 мин 21 с назад" within an hour (short, to stay on one line), then
     * "сегодня 19:38:25", "вчера 23:10:05", "14 окт 04:01:00" */
    private fun whenText(ms: Long, now: Long): String {
        val ago = (now - ms) / 1000
        if (ago in 0 until 3600) {
            val m = ago / 60
            val s = ago % 60
            return when {
                ago == 0L -> "только что"
                m == 0L -> "$s с назад"
                s == 0L -> "$m мин назад"
                else -> "$m мин $s с назад"
            }
        }
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val yesterday = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1) }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val day = when {
            sameDay(c, today) -> "сегодня"
            sameDay(c, yesterday) -> "вчера"
            else -> "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}"
        }
        return String.format(Locale.US, "%s %02d:%02d:%02d", day,
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }

    companion object {
        /** Slider units per step of the scale */
        const val STEP = 100
        /** How long to hold Пуск/Стоп and Статус */
        const val HOLD_MS = 1_000L
        private val MONTHS = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
    }
}
