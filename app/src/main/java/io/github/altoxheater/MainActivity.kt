package io.github.altoxheater

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var power: RoundButton
    private lateinit var caption: TextView
    private lateinit var message: TextView
    private lateinit var minutesLabel: TextView
    private lateinit var minutes: SeekBar
    private lateinit var status: RoundButton
    private lateinit var statusInfo: TextView

    private val prefs by lazy { Heater.prefs(this) }
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
        minutesLabel = findViewById(R.id.minutesLabel)
        minutes = findViewById(R.id.minutes)
        status = findViewById(R.id.status)
        statusInfo = findViewById(R.id.statusInfo)

        status.label = getString(R.string.status)
        status.labelSize = power.subSize * 1.1f
        status.subSize = power.subSize * 0.85f

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

        minutes.max = (Heater.MAX_MINUTES - Heater.MIN_MINUTES) / STEP
        minutes.progress = (Heater.minutes(this) - Heater.MIN_MINUTES) / STEP
        minutes.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) prefs.edit().putInt(Heater.MINUTES, Heater.MIN_MINUTES + progress * STEP).apply()
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        ensureSmsPermissions()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
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

        power.label = when (state) {
            Heater.STARTING -> "Запуск..."
            Heater.ON -> "Стоп"
            Heater.STOPPING -> "Остановка..."
            else -> "Пуск"
        }
        power.fill = getColor(when {
            !configured -> R.color.btn_disabled
            pending -> R.color.btn_pending
            state == Heater.ON -> R.color.btn_on
            else -> R.color.btn_off
        })
        power.isEnabled = configured && !pending && !statusBusy
        power.alpha = if (configured && statusBusy) 0.5f else 1f

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
            state == Heater.ON -> "Нагрев запущен"
            prefs.contains(Heater.PREV_STATE) -> "Нагрев выключен"
            else -> ""
        }
        message.text = prefs.getString(Heater.MESSAGE, "")

        val run = Heater.minutes(this)
        minutesLabel.text = "Время работы: $run мин"
        minutes.isEnabled = configured && state == Heater.OFF && !statusBusy

        val statusTime = prefs.getLong(Heater.STATUS_TIME, 0)
        statusInfo.text = if (statusTime == 0L) "" else {
            fun value(key: String, unit: String) =
                prefs.getString(key, "").let { if (it.isNullOrEmpty()) "—" else "$it $unit" }
            val errors = prefs.getString(Heater.ERRORS, "").let { if (it.isNullOrEmpty()) "нет" else it }
            val flame = if (prefs.getBoolean(Heater.FLAME, false)) "есть" else "нет"
            val time = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(statusTime))
            "ОЖ: ${value(Heater.COOLANT, "°C")}    Салон: ${value(Heater.CABIN, "°C")}\n" +
                "Напряжение: ${value(Heater.VOLTAGE, "В")}\n" +
                "Пламя: $flame    Ошибки: $errors\n" +
                "обновлено $time"
        }
        renderFrame()
    }

    /** Timers and progress rings, 10 times a second */
    private fun renderFrame() {
        val now = System.currentTimeMillis()
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
                val endsAt = prefs.getLong(Heater.ENDS_AT, 0)
                val total = prefs.getLong(Heater.RUN_TOTAL, 0)
                if (endsAt > 0 && total > 0) {
                    val left = maxOf(endsAt - now, 0)
                    power.progress = left.toFloat() / total
                    power.sub = String.format(Locale.US, "%02d мин : %04.1f сек", left / 60_000, left % 60_000 / 1000.0)
                } else {
                    power.progress = -1f
                    power.sub = "работает\n" + clock(now - prefs.getLong(Heater.ON_SINCE, now))
                }
            }
            else -> {
                power.spinning = false
                power.progress = -1f
                power.sub = if (Heater.isConfigured(this)) "${Heater.minutes(this)} мин" else ""
            }
        }

        if (!Heater.isPending(state) && statusSent > 0) spin(status, "${(now - statusSent) / 1000} с")
        else {
            status.spinning = false
            status.sub = ""
        }
    }

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

    companion object {
        const val STEP = 5
    }
}
