package io.github.altoxheater

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.telephony.SubscriptionManager
import android.widget.Button
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
    private lateinit var flameDelay: SeekBar
    private lateinit var flameDelayLabel: TextView
    private lateinit var periodicLabel: TextView
    private lateinit var periodicCount: SeekBar
    private lateinit var periodicCountLabel: TextView
    private lateinit var finishSound: TextView
    private lateinit var errorSound: TextView
    private lateinit var saveButton: Button
    /** Picked sounds, saved with the rest: "" silent */
    private var finishUri = ""
    private var errorUri = ""

    private val prefs by lazy { Heater.prefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        phone = findViewById(R.id.phone)
        sim = findViewById(R.id.sim)
        password = findViewById(R.id.password)
        replyTimeout = findViewById(R.id.replyTimeout)
        flameDelay = findViewById(R.id.flameDelay)
        flameDelayLabel = findViewById(R.id.flameDelayLabel)
        periodicLabel = findViewById(R.id.periodicLabel)
        periodicCount = findViewById(R.id.periodicCount)
        periodicCountLabel = findViewById(R.id.periodicCountLabel)
        finishSound = findViewById(R.id.finishSound)
        errorSound = findViewById(R.id.errorSound)
        finishUri = savedSound(Heater.FINISH_SOUND)
        errorUri = savedSound(Heater.ERROR_SOUND)
        finishSound.setOnClickListener { pickSound(Heater.FINISH_SOUND) }
        errorSound.setOnClickListener { pickSound(Heater.ERROR_SOUND) }

        phone.setText(prefs.getString(Heater.PHONE, ""))
        password.setText(prefs.getString(Heater.PASSWORD, ""))
        replyTimeout.setText(savedTimeout().toString())
        // Sliders count from the range's first value
        flameDelay.max = Heater.FLAME_DELAY_RANGE.last - Heater.FLAME_DELAY_RANGE.first
        flameDelay.progress = savedDelay() - Heater.FLAME_DELAY_RANGE.first
        // Periodic STATUS: off at the left end, auto at the right one
        periodicCount.max = Heater.PERIODIC_RANGE.last + 1
        periodicCount.progress = savedCount().let { if (it == Heater.PERIODIC_AUTO) periodicCount.max else it }
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
        flameDelay.setOnSeekBarChangeListener(slider)
        periodicCount.setOnSeekBarChangeListener(slider)
        sim.setOnCheckedChangeListener { _, _ -> updateSave() }
        updateSave()
    }

    private fun updateSave() {
        saveButton.isEnabled = phone.text.toString().trim() != prefs.getString(Heater.PHONE, "") ||
            password.text.toString().trim() != prefs.getString(Heater.PASSWORD, "") ||
            sim.checkedRadioButtonId - ID_BASE != prefs.getInt(Heater.SIM_SLOT, -1) ||
            replyTimeout.text.toString().trim() != savedTimeout().toString() ||
            delay() != savedDelay() ||
            count() != savedCount() ||
            finishUri != savedSound(Heater.FINISH_SOUND) ||
            errorUri != savedSound(Heater.ERROR_SOUND)
        flameDelayLabel.text = "${delay()} мин"
        finishSound.text = soundTitle(finishUri)
        errorSound.text = soundTitle(errorUri)
        periodicCountLabel.text = when (count()) {
            0 -> "Выкл"
            Heater.PERIODIC_AUTO -> "Авто"
            else -> count().toString()
        }
        periodicLabel.text = Heater.periodicText(this, Heater.periodicCount(this, count()), delay() * 60_000L)
    }

    private fun savedTimeout() = (Heater.replyTimeout(this) / 1000).toInt()
    private fun savedDelay() = (Heater.flameDelay(this) / 60_000).toInt()
    private fun delay() = flameDelay.progress + Heater.FLAME_DELAY_RANGE.first
    private fun savedCount() = Heater.periodicSetting(this)
    private fun count() = periodicCount.progress.let { if (it == periodicCount.max) Heater.PERIODIC_AUTO else it }

    private fun savedSound(key: String) = Heater.sound(this, key)?.toString().orEmpty()

    private fun soundTitle(uri: String): String {
        if (uri.isEmpty()) return "Без звука"
        return try {
            RingtoneManager.getRingtone(this, Uri.parse(uri))?.getTitle(this)
        } catch (e: Exception) {
            null
        } ?: "Системный звук"
    }

    /** The system's sound picker: notification sounds for the finish, alarm ones for the error */
    private fun pickSound(key: String) {
        val current = if (key == Heater.ERROR_SOUND) errorUri else finishUri
        val pick = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                if (key == Heater.ERROR_SOUND) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE,
                getString(if (key == Heater.ERROR_SOUND) R.string.error_sound else R.string.finish_sound))
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Heater.defaultSound(key))
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, if (current.isEmpty()) null else Uri.parse(current))
        try {
            startActivityForResult(pick, if (key == Heater.ERROR_SOUND) PICK_ERROR_SOUND else PICK_FINISH_SOUND)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Нет выбора звуков", Toast.LENGTH_SHORT).show()
        }
    }

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
        if (requestCode == PICK_FINISH_SOUND || requestCode == PICK_ERROR_SOUND) {
            if (resultCode != RESULT_OK || data == null) return
            @Suppress("DEPRECATION")
            val picked = data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)?.toString().orEmpty()
            if (requestCode == PICK_ERROR_SOUND) errorUri = picked else finishUri = picked
            updateSave()
            return
        }
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
        prefs.edit()
            .putInt(Heater.REPLY_TIMEOUT, timeout)
            .putInt(Heater.FLAME_DELAY, delay())
            .putString(Heater.FINISH_SOUND, finishUri)
            .putString(Heater.ERROR_SOUND, errorUri)
            .remove(Heater.PERIODIC)
            .putInt(Heater.PERIODIC_COUNT, count())
            .putString(Heater.PHONE, number)
            .putInt(Heater.SIM_SLOT, sim.checkedRadioButtonId - ID_BASE)
            .putString(Heater.PASSWORD, password.text.toString().trim())
            .commit()
        // Timeouts, the end check and the periodic STATUS are planned from these settings
        Heater.planPeriodic(this)
        Heater.scheduleWake(this)
        finish()
    }

    companion object {
        const val ID_BASE = 100
        const val PICK_CONTACT = 1
        const val PICK_FINISH_SOUND = 2
        const val PICK_ERROR_SOUND = 3

        /** "+7 (900) 123-45-67" -> "+79001234567" */
        fun cleanNumber(number: String) =
            (if (number.trim().startsWith("+")) "+" else "") + number.filter { it.isDigit() }
    }
}
