package io.github.altoxheater

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.telephony.SubscriptionManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var phone: EditText
    private lateinit var sim: RadioGroup
    private lateinit var password: EditText
    private lateinit var replyTimeout: EditText
    private lateinit var pollDelay: EditText
    private lateinit var periodic: CheckBox
    private lateinit var periodicLabel: TextView
    private lateinit var periodicInterval: EditText
    private lateinit var saveButton: Button

    private val prefs by lazy { Heater.prefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        phone = findViewById(R.id.phone)
        sim = findViewById(R.id.sim)
        password = findViewById(R.id.password)
        replyTimeout = findViewById(R.id.replyTimeout)
        pollDelay = findViewById(R.id.pollDelay)
        periodic = findViewById(R.id.periodic)
        periodicLabel = findViewById(R.id.periodicLabel)
        periodicInterval = findViewById(R.id.periodicInterval)

        phone.setText(prefs.getString(Heater.PHONE, ""))
        password.setText(prefs.getString(Heater.PASSWORD, ""))
        replyTimeout.setText(savedTimeout().toString())
        pollDelay.setText(savedDelay().toString())
        periodic.isChecked = prefs.getBoolean(Heater.PERIODIC, false)
        periodicInterval.setText(savedInterval().toString())
        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        saveButton = findViewById(R.id.save)
        saveButton.setOnClickListener { save() }

        fillSims()
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 1)

        // Save is active only when something differs from what is saved
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateSave()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        phone.addTextChangedListener(watcher)
        password.addTextChangedListener(watcher)
        replyTimeout.addTextChangedListener(watcher)
        pollDelay.addTextChangedListener(watcher)
        sim.setOnCheckedChangeListener { _, _ -> updateSave() }
        periodic.setOnCheckedChangeListener { _, _ -> updateSave() }
        periodicInterval.addTextChangedListener(watcher)
        updateSave()
    }

    private fun updateSave() {
        saveButton.isEnabled = phone.text.toString().trim() != prefs.getString(Heater.PHONE, "") ||
            password.text.toString().trim() != prefs.getString(Heater.PASSWORD, "") ||
            sim.checkedRadioButtonId - ID_BASE != prefs.getInt(Heater.SIM_SLOT, -1) ||
            replyTimeout.text.toString().trim() != savedTimeout().toString() ||
            pollDelay.text.toString().trim() != savedDelay().toString() ||
            periodic.isChecked != prefs.getBoolean(Heater.PERIODIC, false) ||
            periodicInterval.text.toString().trim() != savedInterval().toString()
        periodicInterval.isEnabled = periodic.isChecked
        periodicLabel.text = periodicText()
    }

    /** "22 минуты 12 секунд, это 2 SMS за цикл 50 мин плюс одна SMS в конце" */
    private fun periodicText(): String {
        val run = Heater.minutes(this)
        if (!periodic.isChecked) return "Только одна SMS в конце нагрева"
        val s = interval() ?: return "Недопустимое значение - приемлемый интервал от ${Heater.PERIODIC_RANGE.first} до ${Heater.PERIODIC_RANGE.last} сек"
        val m = s / 60
        val time = listOfNotNull(
            if (m > 0) "$m " + plural(m, "минута", "минуты", "минут") else null,
            if (s % 60 > 0) "${s % 60} " + plural(s % 60, "секунда", "секунды", "секунд") else null,
        ).joinToString(" ")
        return "$time, это ${Heater.periodicPerRun(s, run)} SMS за цикл $run мин плюс одна SMS в конце"
    }

    private fun plural(n: Int, one: String, few: String, many: String) = when {
        n % 100 in 11..14 -> many
        n % 10 == 1 -> one
        n % 10 in 2..4 -> few
        else -> many
    }

    private fun savedTimeout() = (Heater.replyTimeout(this) / 1000).toInt()
    private fun savedDelay() = (Heater.pollDelay(this) / 1000).toInt()
    private fun savedInterval() = (Heater.periodicInterval(this) / 1000).toInt()
    /** The typed interval, seconds; null if out of range */
    private fun interval() = periodicInterval.text.toString().trim().toIntOrNull()?.takeIf { it in Heater.PERIODIC_RANGE }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        fillSims()
    }

    /** Radio buttons: the default SIM (slot -1) plus each active SIM; view id = slot + ID_BASE */
    private fun fillSims() {
        sim.removeAllViews()
        sim.addView(radio(-1, getString(R.string.sim_default)))
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
                .sortedBy { it.simSlotIndex }
                .forEach { sim.addView(radio(it.simSlotIndex, "SIM ${it.simSlotIndex + 1} — ${it.displayName}")) }
        }
        val saved = prefs.getInt(Heater.SIM_SLOT, -1) + ID_BASE
        sim.check(if (sim.findViewById<RadioButton>(saved) != null) saved else ID_BASE - 1)
    }

    private fun radio(slot: Int, label: String) = RadioButton(this).apply {
        id = slot + ID_BASE
        text = label
        textSize = 16f
    }

    private fun save() {
        val number = phone.text.toString().trim()
        if (number.count { it.isDigit() } < 10) {
            phone.error = "Введите номер полностью, например +79001234567"
            return
        }
        val timeout = replyTimeout.text.toString().trim().toIntOrNull()
        if (timeout == null || timeout !in Heater.REPLY_TIMEOUT_RANGE) {
            replyTimeout.error = "От ${Heater.REPLY_TIMEOUT_RANGE.first} до ${Heater.REPLY_TIMEOUT_RANGE.last} секунд"
            return
        }
        val delay = pollDelay.text.toString().trim().toIntOrNull()
        if (delay == null || delay !in Heater.POLL_DELAY_RANGE) {
            pollDelay.error = "От ${Heater.POLL_DELAY_RANGE.first} до ${Heater.POLL_DELAY_RANGE.last} секунд"
            return
        }
        val interval = interval()
        if (periodic.isChecked && interval == null) {
            periodicInterval.error = "От ${Heater.PERIODIC_RANGE.first} до ${Heater.PERIODIC_RANGE.last} секунд"
            return
        }
        prefs.edit()
            .putInt(Heater.REPLY_TIMEOUT, timeout)
            .putInt(Heater.POLL_DELAY, delay)
            .putBoolean(Heater.PERIODIC, periodic.isChecked)
            .putInt(Heater.PERIODIC_INTERVAL, interval ?: savedInterval())
            .putString(Heater.PHONE, number)
            .putInt(Heater.SIM_SLOT, sim.checkedRadioButtonId - ID_BASE)
            .putString(Heater.PASSWORD, password.text.toString().trim())
            .apply()
        finish()
    }

    companion object {
        const val ID_BASE = 100
    }
}
