package io.github.altoxheater

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var faultsInfo: TextView
    private lateinit var readFaults: TextView
    private lateinit var eraseFaults: TextView
    private lateinit var predict: CheckBox
    private lateinit var fan: CheckBox
    private lateinit var coolingNote: TextView
    /** Picked sounds, saved with the rest: "" silent */
    private var finishUri = ""
    private var errorUri = ""

    private val prefs by lazy { Heater.prefs(this) }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> renderFaults() }

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
        faultsInfo = findViewById(R.id.faultsInfo)
        readFaults = findViewById(R.id.readFaults)
        readFaults.setOnClickListener { if (canSend()) Heater.readFaultsNow(this) }
        eraseFaults = findViewById(R.id.eraseFaults)
        eraseFaults.setOnClickListener { if (canSend()) confirmErase() }

        predict = findViewById(R.id.predict)
        fan = findViewById(R.id.fan)
        coolingNote = findViewById(R.id.coolingNote)
        predict.isChecked = History.predict(prefs)
        fan.isChecked = History.fan(prefs)
        predict.setOnCheckedChangeListener { _, _ -> updateSave() }
        fan.setOnCheckedChangeListener { _, _ -> updateSave() }

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

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        renderFaults()
    }

    override fun onStop() {
        super.onStop()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    /** The codes the last READ got, how READ or ERASE goes; the buttons only with a saved number and none under way */
    private fun renderFaults() {
        val before = if (prefs.contains(Heater.LAST_FAULTS_BEFORE))
            Faults.split(prefs.getString(Heater.LAST_FAULTS_BEFORE, "").orEmpty()) else null
        val last = if (prefs.contains(Heater.LAST_FAULTS))
            Faults.text(Faults.split(prefs.getString(Heater.LAST_FAULTS, "").orEmpty()), before)
                .let { "READ ${time(prefs.getLong(Heater.LAST_FAULTS_TIME, 0))}:" + if (it == "нет") " $it" else "\n$it" }
        else "Ещё не читались"
        faultsInfo.text = listOf(last, prefs.getString(Heater.FAULTS_NOTE, "").orEmpty())
            .filter { it.isNotEmpty() }.joinToString("\n")
        val free = Heater.isConfigured(this) && !Heater.isReadingFaults(this)
        for (b in listOf(readFaults, eraseFaults)) {
            b.isEnabled = free
            b.alpha = if (free) 1f else 0.4f
        }
    }

    private fun time(ms: Long) = SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(ms))

    private fun canSend(): Boolean {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 2)
        return false
    }

    private fun confirmErase() {
        AlertDialog.Builder(this)
            .setTitle("Стереть коды ошибок?")
            .setMessage("Будет отправлено 2 SMS: ERASE сотрёт память ошибок отопителя, " +
                "через ${Heater.ERASE_READ_DELAY / 1000} с READ проверит, что она пуста. " +
                "Старые коды пропадут и из диагностики в сервисе.")
            .setPositiveButton("Стереть") { _, _ -> Heater.eraseFaults(this) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateSave() {
        saveButton.isEnabled = phone.text.toString().trim() != prefs.getString(Heater.PHONE, "") ||
            password.text.toString().trim() != prefs.getString(Heater.PASSWORD, "") ||
            sim.checkedRadioButtonId - ID_BASE != prefs.getInt(Heater.SIM_SLOT, -1) ||
            replyTimeout.text.toString().trim() != savedTimeout().toString() ||
            delay() != savedDelay() ||
            count() != savedCount() ||
            finishUri != savedSound(Heater.FINISH_SOUND) ||
            errorUri != savedSound(Heater.ERROR_SOUND) ||
            predict.isChecked != History.predict(prefs) ||
            fan.isChecked != History.fan(prefs)
        fan.isEnabled = predict.isChecked
        val air = if (fan.isChecked) "ОЖ остывает к температуре воздуха. Салон греется вместе с мотором, " +
            "поэтому воздух берётся по салону до нагрева или через час после него."
            else "ОЖ остывает к температуре воздуха, её даёт салон: он почти не греется от мотора."
        coolingNote.text = if (!predict.isChecked) "Прогноза нет: на главном экране только последний замер."
            else "$air Скорость остывания уточняется по статусам между нагревами. Работу мотора видно по статусу " +
                "(от ${History.ENGINE_VOLTAGE}\u00A0В или ОЖ теплее, чем после остывания); поездку после последнего статуса прогноз не знает."
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
        Heater.ownSounds(this, Heater.FINISH_SOUND).plus(Heater.ownSounds(this, Heater.ERROR_SOUND))
            .firstOrNull { it.first.toString() == uri }?.let { return it.second }
        return try {
            RingtoneManager.getRingtone(this, Uri.parse(uri))?.getTitle(this)
        } catch (e: Exception) {
            null
        } ?: "Системный звук"
    }

    /**
     * The sounds to pick from, in our own list (the system's picker may be another app, with ads):
     * silence, the app's sounds, then the phone's notification and alarm sounds. A tap plays it
     */
    private fun pickSound(key: String) {
        val error = key == Heater.ERROR_SOUND
        val current = if (error) errorUri else finishUri
        val options = (listOf("" to "Без звука") +
            Heater.ownSounds(this, key).map { it.first.toString() to it.second } +
            systemSounds()).distinctBy { it.first }.toMutableList()
        if (options.none { it.first == current }) options.add(1, current to soundTitle(current))
        var chosen = options.indexOfFirst { it.first == current }
        var preview: Ringtone? = null
        AlertDialog.Builder(this)
            .setTitle(if (error) R.string.error_sound else R.string.finish_sound)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), chosen) { _, i ->
                chosen = i
                preview?.stop()
                preview = options[i].first.takeIf { it.isNotEmpty() }?.let { Heater.ringtone(this, Uri.parse(it), key) }
                preview?.play()
            }
            .setPositiveButton("Выбрать") { _, _ ->
                if (error) errorUri = options[chosen].first else finishUri = options[chosen].first
                updateSave()
            }
            .setNegativeButton("Отмена", null)
            .setOnDismissListener { preview?.stop() }
            .show()
    }

    /** The phone's notification and alarm sounds; none if it does not list them */
    private fun systemSounds(): List<Pair<String, String>> = try {
        val m = RingtoneManager(this).apply { setType(RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM) }
        val c = m.cursor
        (0 until c.count).mapNotNull { i ->
            c.moveToPosition(i)
            m.getRingtoneUri(i)?.let { it.toString() to c.getString(RingtoneManager.TITLE_COLUMN_INDEX) }
        }
    } catch (e: Exception) {
        emptyList()
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
            .putBoolean(History.PREDICT, predict.isChecked)
            .putBoolean(History.FAN, fan.isChecked)
            .commit()
        // Timeouts, the end check and the periodic STATUS are planned from these settings
        Heater.planPeriodic(this)
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
