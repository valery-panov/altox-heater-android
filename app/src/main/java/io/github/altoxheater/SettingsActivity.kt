package io.github.altoxheater

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.telephony.SubscriptionManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var phone: EditText
    private lateinit var sim: RadioGroup
    private lateinit var password: EditText
    private lateinit var replyTimeout: EditText
    private lateinit var pollDelay: SeekBar
    private lateinit var pollDelayLabel: TextView
    private lateinit var periodic: CheckBox
    private lateinit var periodicLabel: TextView
    private lateinit var periodicCount: SeekBar
    private lateinit var periodicCountLabel: TextView
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
        pollDelayLabel = findViewById(R.id.pollDelayLabel)
        periodic = findViewById(R.id.periodic)
        periodicLabel = findViewById(R.id.periodicLabel)
        periodicCount = findViewById(R.id.periodicCount)
        periodicCountLabel = findViewById(R.id.periodicCountLabel)

        phone.setText(prefs.getString(Heater.PHONE, ""))
        password.setText(prefs.getString(Heater.PASSWORD, ""))
        replyTimeout.setText(savedTimeout().toString())
        // Sliders count from the range's first value
        pollDelay.max = Heater.POLL_DELAY_RANGE.last - Heater.POLL_DELAY_RANGE.first
        pollDelay.progress = savedDelay() - Heater.POLL_DELAY_RANGE.first
        periodicCount.max = Heater.PERIODIC_RANGE.last - Heater.PERIODIC_RANGE.first
        periodicCount.progress = savedCount() - Heater.PERIODIC_RANGE.first
        periodic.isChecked = prefs.getBoolean(Heater.PERIODIC, false)
        findViewById<ImageButton>(R.id.back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.pickContact).setOnClickListener { pickContact() }
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
        val slider = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = updateSave()
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        }
        pollDelay.setOnSeekBarChangeListener(slider)
        periodicCount.setOnSeekBarChangeListener(slider)
        sim.setOnCheckedChangeListener { _, _ -> updateSave() }
        periodic.setOnCheckedChangeListener { _, _ -> updateSave() }
        updateSave()
    }

    private fun updateSave() {
        saveButton.isEnabled = phone.text.toString().trim() != prefs.getString(Heater.PHONE, "") ||
            password.text.toString().trim() != prefs.getString(Heater.PASSWORD, "") ||
            sim.checkedRadioButtonId - ID_BASE != prefs.getInt(Heater.SIM_SLOT, -1) ||
            replyTimeout.text.toString().trim() != savedTimeout().toString() ||
            delay() != savedDelay() ||
            periodic.isChecked != prefs.getBoolean(Heater.PERIODIC, false) ||
            count() != savedCount()
        pollDelayLabel.text = "${delay()} с"
        periodicCountLabel.text = count().toString()
        periodicCount.isEnabled = periodic.isChecked
        periodicCountLabel.isEnabled = periodic.isChecked
        periodicLabel.text = Heater.periodicText(this, if (periodic.isChecked) count() else 0)
    }

    private fun savedTimeout() = (Heater.replyTimeout(this) / 1000).toInt()
    private fun savedDelay() = (Heater.pollDelay(this) / 1000).toInt()
    private fun delay() = pollDelay.progress + Heater.POLL_DELAY_RANGE.first
    private fun savedCount() = Heater.periodicCount(this)
    private fun count() = periodicCount.progress + Heater.PERIODIC_RANGE.first

    private fun pickContact() {
        val pick = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        try {
            startActivityForResult(pick, PICK_CONTACT)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Нет приложения контактов", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Activity, not ComponentActivity: no result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode != PICK_CONTACT || resultCode != RESULT_OK || uri == null) return
        val number = contentResolver.query(
            uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return
        phone.setText(cleanNumber(number))
        phone.error = null
    }

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
        val count = count()
        val e = prefs.edit()
        // Mid-run the new slots start from now, not from a slot already past
        if (periodic.isChecked != prefs.getBoolean(Heater.PERIODIC, false) || count != savedCount())
            e.putLong(Heater.PERIODIC_SINCE, Heater.now())
        e
            .putInt(Heater.REPLY_TIMEOUT, timeout)
            .putInt(Heater.POLL_DELAY, delay())
            .putBoolean(Heater.PERIODIC, periodic.isChecked)
            .putInt(Heater.PERIODIC_COUNT, count)
            .putString(Heater.PHONE, number)
            .putInt(Heater.SIM_SLOT, sim.checkedRadioButtonId - ID_BASE)
            .putString(Heater.PASSWORD, password.text.toString().trim())
            .commit()
        // Timeouts, the end check and the periodic STATUS are planned from these settings
        Heater.scheduleWake(this)
        finish()
    }

    companion object {
        const val ID_BASE = 100
        const val PICK_CONTACT = 1

        /** "+7 (900) 123-45-67" -> "+79001234567" */
        fun cleanNumber(number: String) =
            (if (number.trim().startsWith("+")) "+" else "") + number.filter { it.isDigit() }
    }
}
