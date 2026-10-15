package io.github.altoxheater

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.telephony.SubscriptionManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup

class SettingsActivity : Activity() {
    private lateinit var phone: EditText
    private lateinit var sim: RadioGroup
    private lateinit var password: EditText
    private lateinit var replyTimeout: EditText
    private lateinit var saveButton: Button

    private val prefs by lazy { Heater.prefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        phone = findViewById(R.id.phone)
        sim = findViewById(R.id.sim)
        password = findViewById(R.id.password)
        replyTimeout = findViewById(R.id.replyTimeout)

        phone.setText(prefs.getString(Heater.PHONE, ""))
        password.setText(prefs.getString(Heater.PASSWORD, ""))
        replyTimeout.setText(savedTimeout().toString())
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
        sim.setOnCheckedChangeListener { _, _ -> updateSave() }
        updateSave()
    }

    private fun updateSave() {
        saveButton.isEnabled = phone.text.toString().trim() != prefs.getString(Heater.PHONE, "") ||
            password.text.toString().trim() != prefs.getString(Heater.PASSWORD, "") ||
            sim.checkedRadioButtonId - ID_BASE != prefs.getInt(Heater.SIM_SLOT, -1) ||
            replyTimeout.text.toString().trim() != savedTimeout().toString()
    }

    private fun savedTimeout() = (Heater.replyTimeout(this) / 1000).toInt()

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
        prefs.edit()
            .putInt(Heater.REPLY_TIMEOUT, timeout)
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
