package io.github.altoxheater

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var power: Button
    private lateinit var caption: TextView
    private lateinit var timer: TextView
    private lateinit var message: TextView
    private lateinit var status: Button
    private lateinit var statusInfo: TextView

    private val prefs by lazy { Heater.prefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> render() }
    private val tick = object : Runnable {
        override fun run() {
            Heater.checkTimeouts(this@MainActivity)
            renderTimer()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        power = findViewById(R.id.power)
        caption = findViewById(R.id.caption)
        timer = findViewById(R.id.timer)
        message = findViewById(R.id.message)
        status = findViewById(R.id.status)
        statusInfo = findViewById(R.id.statusInfo)

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
        ensureSmsPermissions()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        render()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacks(tick)
    }

    private fun ensureSmsPermissions(): Boolean {
        val missing = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        requestPermissions(missing.toTypedArray(), 1)
        return false
    }

    private fun render() {
        val configured = Heater.isConfigured(this)
        val state = prefs.getString(Heater.STATE, Heater.OFF)
        val pending = state == Heater.STARTING || state == Heater.STOPPING

        power.text = when (state) {
            Heater.STARTING -> "Запуск..."
            Heater.ON -> "Стоп"
            Heater.STOPPING -> "Остановка..."
            else -> "Пуск"
        }
        power.isEnabled = configured && !pending
        power.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(when {
                !configured -> R.color.btn_disabled
                pending -> R.color.btn_pending
                state == Heater.ON -> R.color.btn_on
                else -> R.color.btn_off
            }))
            setStroke(dp(6), getColor(R.color.btn_ring))
        }

        caption.text = when {
            !configured -> "Укажите номер Altox в настройках"
            pending -> "Ждём подтверждения по SMS"
            state == Heater.ON -> "Нагрев запущен"
            prefs.contains(Heater.PREV_STATE) -> "Нагрев выключен"
            else -> ""
        }
        message.text = prefs.getString(Heater.MESSAGE, "")

        val statusSent = prefs.getLong(Heater.STATUS_SENT, 0) > 0
        status.isEnabled = configured && !statusSent
        status.text = if (statusSent) "Запрос статуса..." else getString(R.string.status)
        val statusTime = prefs.getLong(Heater.STATUS_TIME, 0)
        statusInfo.text = if (statusTime == 0L) "" else {
            fun temp(key: String) = prefs.getString(key, "").let { if (it.isNullOrEmpty()) "—" else "$it °C" }
            val errors = prefs.getString(Heater.ERRORS, "").let { if (it.isNullOrEmpty()) "—" else it }
            val time = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(statusTime))
            "ОЖ: ${temp(Heater.COOLANT)}    Салон: ${temp(Heater.CABIN)}\nОшибки: $errors\nобновлено $time"
        }
        renderTimer()
    }

    private fun renderTimer() {
        val now = System.currentTimeMillis()
        timer.text = when (prefs.getString(Heater.STATE, Heater.OFF)) {
            Heater.STARTING, Heater.STOPPING -> clock(now - prefs.getLong(Heater.PENDING_SINCE, now))
            Heater.ON -> {
                val endsAt = prefs.getLong(Heater.ENDS_AT, 0)
                if (endsAt > 0) "осталось " + clock(endsAt - now)
                else "работает " + clock(now - prefs.getLong(Heater.ON_SINCE, now))
            }
            else -> ""
        }
    }

    private fun clock(ms: Long): String {
        val s = maxOf(ms, 0) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
