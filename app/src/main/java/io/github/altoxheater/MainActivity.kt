package io.github.altoxheater

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

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
    private lateinit var flame: FlameView
    private lateinit var statusInfo: TextView
    private lateinit var statusTime: TextView
    private lateinit var forecastBlock: View
    private lateinit var forecast: TextView
    private lateinit var forecastNote: TextView
    private lateinit var historyCard: View
    private lateinit var chart: HistoryView
    private lateinit var historyList: LinearLayout
    /** The minute the history was drawn for: it changes slowly, no need to redraw 10 times a second */
    private var historyMinute = -1L

    private val prefs by lazy { Heater.prefs(this) }
    private var sliderEditable = false
    private var setupDialog: AlertDialog? = null
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
        Heater.migrate(this)
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
        forecastBlock = findViewById(R.id.forecastBlock)
        forecast = findViewById(R.id.forecast)
        forecastNote = findViewById(R.id.forecastNote)
        historyCard = findViewById(R.id.historyCard)
        chart = findViewById(R.id.chart)
        historyList = findViewById(R.id.historyList)
        findViewById<TextView>(R.id.legend).text = legend()

        status.label = getString(R.string.status)
        status.labelSize = power.subSize * 1.1f
        status.subSize = power.subSize * 0.85f
        // Against taps from a pocket or a stray finger: a command only after holding the button
        // Пуск is a wide rounded rectangle: the screen has no height to spare for a circle
        power.corner = 24 * resources.displayMetrics.density
        for (b in listOf(power, status)) b.holdMs = HOLD_MS
        power.hint = "удерживайте\n1 секунду"
        status.hint = "удерживайте"

        findViewById<View>(R.id.settings).setOnClickListener {
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

        // Recents would show a screenshot of the old state while the app opens; a plain screen instead
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)

        val missing = missingPermissions()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    /** READ_SMS: a reply that came while the app was stopped, see Heater.catchUp(); notifications: HeaterService's */
    private fun missingPermissions() =
        (listOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** Brings the state up to date before the first frame, so the screen never shows the old one first */
    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // Before the first tick(): it would time out the STATUS whose reply is already in the inbox
        Heater.catchUp(this)
        Heater.tick(this)
        Heater.scheduleWake(this)
        render()
        renderFrame()
    }

    override fun onResume() {
        super.onResume()
        handler.post(frame)
        checkBackground()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(frame)
    }

    override fun onStop() {
        super.onStop()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun ensureSmsPermissions(): Boolean {
        val missing = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        requestPermissions(missing.toTypedArray(), 1)
        return false
    }

    /**
     * At each start, after the permissions: what lets a closed app send the SMS of a run (see
     * HeaterService). Battery optimisation is checked through the system; the vendor's own
     * launch manager (Honor, Huawei) cannot be read, so its dialog shows until the user says it is done
     */
    private fun checkBackground() {
        if (setupDialog?.isShowing == true || missingPermissions().isNotEmpty()) return
        val launch = launchManager()
        setupDialog = when {
            !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) ->
                AlertDialog.Builder(this)
                    .setTitle("Работа в фоне")
                    .setMessage("Чтобы во время нагрева SMS отправлялись и при закрытом приложении, " +
                        "разрешите ему работать без ограничений батареи.")
                    .setPositiveButton("Разрешить") { _, _ -> open(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    ) }
                    .setNegativeButton("Позже", null)
                    .show()
            launch != null && !prefs.getBoolean(Heater.LAUNCH_DONE, false) ->
                AlertDialog.Builder(this)
                    .setTitle("Запуск приложений")
                    .setMessage("${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} останавливает закрытые приложения, " +
                        "и SMS во время нагрева не уходят. В «Запуске приложений» выключите для ${getString(R.string.app_name)} " +
                        "«Управлять автоматически» и оставьте включёнными все три переключателя: " +
                        "автозапуск, косвенный запуск и работа в фоне.")
                    .setPositiveButton("Открыть") { _, _ -> open(launch, appDetails()) }
                    .setNeutralButton("Уже настроено") { _, _ -> prefs.edit().putBoolean(Heater.LAUNCH_DONE, true).apply() }
                    .setNegativeButton("Позже", null)
                    .show()
            else -> null
        }
    }

    /** The launch manager screen on Honor and Huawei, where a closed app may be allowed to run; null elsewhere */
    private fun launchManager(): Intent? {
        val pkg = when (Build.MANUFACTURER.lowercase()) {
            "honor" -> "com.hihonor.systemmanager"
            "huawei" -> "com.huawei.systemmanager"
            else -> return null
        }
        return Intent().setComponent(ComponentName(pkg, "$pkg.startupmgr.ui.StartupNormalAppListActivity"))
    }

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    /** The first of [intents] that opens */
    private fun open(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (e: Exception) {
            }
        }
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
            paused() -> "Пауза: вода нагрета до ${prefs.getString(Heater.COOLANT, "")} °C, пламя погашено до остывания"
            state == Heater.ON -> "Нагрев запущен"
            prefs.contains(Heater.PREV_STATE) -> "Нагрев выключен"
            else -> ""
        }
        message.text = listOf(prefs.getString(Heater.MESSAGE, "").orEmpty(), Heater.faultsText(prefs))
            .filter { it.isNotEmpty() }.joinToString("\n")

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
        historyMinute = -1
        renderFrame()
    }

    /** The forecast, the chart and the last runs; once a minute and on each change */
    private fun renderHistory(now: Long) {
        val f = History.forecast(prefs, now)
        forecastBlock.visibility = if (f == null) View.GONE else View.VISIBLE
        if (f != null) {
            forecast.text = "≈ ${f.coolant.roundToInt()} °C"
            val air = if (History.fan(prefs)) "салон без нагрева" else "салон"
            val engine = when {
                f.from.engine -> " при работающем моторе"
                f.warmed -> " после работы мотора"
                else -> ""
            }
            val speed = if (f.samples == 0) "средняя" else "по прошлым остываниям (${f.samples})"
            forecastNote.text = "От замера ${fmt(f.from.coolant!!)}\u00A0°C ${dayTime(f.from.t)}$engine к воздуху ≈ ${fmt(f.ambient)}\u00A0°C ($air). " +
                "Скорость остывания $speed."
        }

        val readings = History.readings(prefs)
        val runs = History.runs(prefs)
        val trips = History.trips(readings, runs, History.fan(prefs))
        historyCard.visibility = if (readings.isEmpty() && runs.isEmpty()) View.GONE else View.VISIBLE
        chart.readings = readings
        chart.runs = runs
        chart.forecast = f
        chart.now = now
        chart.invalidate()

        historyList.removeAllViews()
        val rows = runs.map { it.start to runRow(it, readings, now) } + trips.map { it.start to tripRow(it) }
        for ((_, v) in rows.sortedBy { it.first }.takeLast(HISTORY_ROWS).reversed()) historyList.addView(v)
    }

    /** "сегодня 08:40   мотор работал · ОЖ 88 °C · 14.2 В" */
    private fun tripRow(t: History.Trip): View {
        val coolant = t.maxCoolant?.let { " · ОЖ ${fmt(it)}\u00A0°C" } ?: ""
        val text = if (t.voltage != null) "мотор работал$coolant · ${fmt(t.voltage)}\u00A0В"
            else "до замера работал мотор$coolant"
        return row(dayTime(t.start), text, R.color.text_secondary)
    }

    /** "сегодня 07:10   20 мин · 8 → 62 °C", the error in red instead of the numbers */
    private fun runRow(r: History.Run, readings: List<History.Reading>, now: Long): View {
        val (from, max) = History.coolantRange(readings, r, now)
        val temps = if (from != null && max != null) " · ${fmt(from)} → ${fmt(max)}\u00A0°C" else ""
        val moving = if (History.onTheMove(readings, r)) ", на ходу" else ""
        val minutes = ((if (r.end > 0) r.end else now) - r.start + 30_000) / 60_000
        val (text, color) = when (r.result) {
            "" -> "идёт $minutes\u00A0мин$temps$moving" to R.color.text
            // The STATUS after the end comes a little later: the run's own time
            History.RESULT_OK -> "${if (r.minutes > 0) r.minutes else minutes}\u00A0мин$temps$moving" to R.color.text
            History.RESULT_STOPPED -> "$minutes\u00A0мин$temps, остановлен$moving" to R.color.text
            History.RESULT_UNKNOWN -> "~${r.minutes}\u00A0мин$temps$moving" to R.color.text
            else -> r.result + temps + moving to R.color.error
        }
        return row(dayTime(r.start), text, color)
    }

    private fun row(time: String, text: CharSequence, color: Int): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pad = (3 * resources.displayMetrics.density).toInt()
        fun cell(text: CharSequence, weight: Float, color: Int) = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(getColor(color))
            setPadding(0, pad, 0, pad)
            fontFeatureSettings = "tnum"
            layoutParams = LinearLayout.LayoutParams(if (weight > 0) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }
        row.addView(cell(time, 0f, R.color.text_secondary).apply { minWidth = (118 * resources.displayMetrics.density).toInt() })
        row.addView(cell(text, 1f, color))
        return row
    }

    private fun legend(): CharSequence {
        val b = SpannableStringBuilder()
        fun item(mark: String, color: Int, name: String) {
            val at = b.length
            b.append(mark)
            b.setSpan(ForegroundColorSpan(getColor(color)), at, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.append(" $name   ")
        }
        item("●", R.color.btn_on, "ОЖ")
        item("●", R.color.btn_status, "салон")
        item("■", R.color.chart_run, "нагрев")
        return b.trimEnd()
    }

    /** 9.0 -> "9", 12.5 -> "12.5" */
    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

    /** "сегодня 07:30", "вчера 23:10", "14 окт 04:01" */
    private fun dayTime(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(Locale.US, "%s %02d:%02d", day(c, Heater.now()), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    private fun day(c: Calendar, now: Long): String {
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val yesterday = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1) }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            sameDay(c, today) -> "сегодня"
            sameDay(c, yesterday) -> "вчера"
            else -> "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}"
        }
    }

    /** Timers and progress rings, 10 times a second */
    private fun renderFrame() {
        val now = Heater.now()
        val state = prefs.getString(Heater.STATE, Heater.OFF)
        val statusSent = prefs.getLong(Heater.STATUS_SENT, 0)
        // The run is over, the STATUS after it is not sent yet
        var endCheckWait = false

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
                // The time left is on the slider, the ring stays for the hold and for waiting on SMS
                power.spinning = false
                power.progress = -1f
                power.sub = if (prefs.getBoolean(Heater.ERROR, false)) "удерживайте,\nчтобы\u00A0остановить" else ""
                val endsAt = prefs.getLong(Heater.ENDS_AT, 0)
                val total = prefs.getLong(Heater.RUN_TOTAL, 0)
                if (endsAt > 0 && total > 0) {
                    val left = maxOf(endsAt - now, 0)
                    minutesTitle.text = "Осталось"
                    minutesLabel.text = String.format(Locale.US, "%02d мин : %04.1f сек",
                        left / 60_000, left % 60_000 / 1000.0)
                    // The whole width is this run: from the right edge at the start to the left at the end
                    minutes.progress = (minutes.max * left / total).toInt()
                    // The run is over, the STATUS after it tells whether the heater is off; that is
                    // shown on Статус, Стоп is not being sent
                    if (left == 0L && !prefs.getBoolean(Heater.ERROR, false)) {
                        caption.text = "Проверяем, что отопитель выключился"
                        if (statusSent == 0L) {
                            endCheckWait = true
                            status.spinning = false
                            status.progress = (now - endsAt).toFloat() / Heater.END_DELAY
                            status.sub = "через ${seconds(endsAt + Heater.END_DELAY - now)} с"
                        }
                    }
                } else {
                    // Started elsewhere, the run time is unknown
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

        if (now / 60_000 != historyMinute) {
            historyMinute = now / 60_000
            renderHistory(now)
        }
        val time = prefs.getLong(Heater.STATUS_TIME, 0)
        if (time > 0) statusTime.text = "Обновлено: " + whenText(time, now)
        val error = state == Heater.ON && prefs.getBoolean(Heater.ERROR, false)
        val endsAt = prefs.getLong(Heater.ENDS_AT, 0)
        flame.mode = when {
            error -> FlameView.Mode.ERROR
            // Our STOP, or the run is over and the STATUS after it should show the heater off
            state == Heater.STOPPING || state == Heater.ON && endsAt in 1..now -> FlameView.Mode.STOPPING
            state == Heater.STARTING -> FlameView.Mode.IGNITING
            paused() -> FlameView.Mode.OFF
            state == Heater.ON -> if (prefs.getBoolean(Heater.FLAME, false)) FlameView.Mode.BURNING else FlameView.Mode.IGNITING
            else -> FlameView.Mode.OFF
        }

        if (!Heater.isPending(state) && statusSent > 0) spin(status, "${(now - statusSent) / 1000} с")
        else if (!endCheckWait) {
            status.spinning = false
            status.progress = -1f
            status.sub = ""
        }
    }

    /** On, no flame, the coolant hot: the heater waits for it to cool, see Heater.heated() */
    private fun paused() = prefs.getString(Heater.STATE, Heater.OFF) == Heater.ON &&
        !prefs.getBoolean(Heater.FLAME, false) &&
        Heater.heated(prefs.getString(Heater.COOLANT, ""), prefs.getString(Heater.START_COOLANT, null))

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
        return String.format(Locale.US, "%s %02d:%02d:%02d", day(c, now),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }

    companion object {
        /** Slider units per step of the scale */
        const val STEP = 100
        /** How long to hold Пуск/Стоп and Статус */
        const val HOLD_MS = 750L
        /** Runs listed under the chart */
        const val HISTORY_ROWS = 3
        private val MONTHS = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
    }
}
