package io.github.altoxheater

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import kotlin.math.abs

/**
 * Heater state and Altox WBUS SMS protocol. Everything lives in SharedPreferences: the receivers
 * write it, MainActivity listens for changes and redraws.
 *
 * Commands: START:XX (XX = 10..120 min), STOP, STATUS. The module does not confirm START and
 * STOP, so after a command we send STATUS until it shows the state we want or time runs out.
 * The status reply:
 *   HEATER OFF          (HEATER ON while running)
 *   GSM (0-31): 28
 *   VOLTAGE: 12.9V
 *   HEATER: 7C          coolant
 *   SENSOR: 9C          cabin
 *   FLAME: NO
 *   OUTPUT: OFF         the module's OUT, usually an LED in the car
 */
object Heater {
    const val OFF = "OFF"
    const val STARTING = "STARTING"
    const val ON = "ON"
    const val STOPPING = "STOPPING"

    /** When to ask STATUS after a command (counted from sending it), how often to repeat, when to give up */
    private class Timing(val firstPoll: Long, val pollEvery: Long, val timeout: Long)

    // The module shows HEATER ON / HEATER OFF right after the command (the flame and the purge
    // come later), so normally the first STATUS confirms it
    private val START = Timing(6_000, 12_000, 60_000)
    private val STOP = Timing(8_000, 12_000, 60_000)

    /** How long to wait for a reply to STATUS */
    const val REPLY_TIMEOUT = 2 * 60_000L
    /** Some phones never report the SMS as sent: then count it as sent after this */
    private const val SENT_TIMEOUT = 60_000L

    /** Run times on the slider: fine steps for short runs, coarse for long ones */
    val RUN_MINUTES = listOf(10, 11, 12, 13, 14, 15, 20, 25, 30, 40, 60, 90, 120)

    // Settings
    const val PHONE = "phone"
    const val SIM_SLOT = "simSlot" // -1: the phone's default SIM for SMS
    const val PASSWORD = "password" // written before each command, if PASS is set on the module
    const val MINUTES = "minutes" // run time for START, the slider on the main screen

    // Command in progress
    const val STATE = "state"
    const val PREV_STATE = "prevState" // to roll back when a command fails
    const val PENDING_SINCE = "pendingSince"
    const val CMD_SENT_AT = "cmdSentAt" // 0 while the SMS is being sent
    const val DEADLINE = "deadline"
    const val POLL_AT = "pollAt" // 0: no STATUS planned
    const val POLL_FROM = "pollFrom" // when it was planned, for the progress ring

    // Run
    const val ON_SINCE = "onSince"
    const val ENDS_AT = "endsAt" // 0 if unknown
    const val RUN_TOTAL = "runTotal"

    const val MESSAGE = "message" // the last error, shown until the next command

    // Status
    const val STATUS_SENT = "statusSent" // 0 if no STATUS is waiting for a reply
    const val STATUS_TIME = "statusTime"
    const val COOLANT = "coolant"
    const val CABIN = "cabin"
    const val VOLTAGE = "voltage"
    const val FLAME = "flame"

    const val KIND_COMMAND = "command"
    const val KIND_STATUS = "status"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("heater", Context.MODE_PRIVATE)

    fun isConfigured(ctx: Context) = !prefs(ctx).getString(PHONE, "").isNullOrBlank()

    /** The saved run time, snapped to the nearest step */
    fun minutes(ctx: Context): Int {
        val saved = prefs(ctx).getInt(MINUTES, 30).takeIf { it > 0 } ?: 30
        return RUN_MINUTES.minBy { abs(it - saved) }
    }

    fun isPending(state: String?) = state == STARTING || state == STOPPING

    fun start(ctx: Context) = command(ctx, "START:${minutes(ctx)}", STARTING)

    fun stop(ctx: Context) = command(ctx, "STOP", STOPPING)

    fun requestStatus(ctx: Context) {
        prefs(ctx).edit().putLong(STATUS_SENT, now()).remove(MESSAGE).apply()
        if (!send(ctx, "STATUS", KIND_STATUS)) statusFailed(ctx, "Не удалось отправить SMS")
    }

    private fun command(ctx: Context, cmd: String, pending: String) {
        val p = prefs(ctx)
        p.edit()
            .putString(PREV_STATE, p.getString(STATE, OFF))
            .putString(STATE, pending)
            .putLong(PENDING_SINCE, now())
            .putLong(CMD_SENT_AT, 0)
            .putLong(POLL_AT, 0)
            .putLong(STATUS_SENT, 0)
            .remove(MESSAGE)
            .apply()
        if (!send(ctx, cmd, KIND_COMMAND)) commandFailed(ctx, "Не удалось отправить SMS")
    }

    /** The command SMS has left: plan the first STATUS */
    fun commandSent(ctx: Context, at: Long = now()) {
        val p = prefs(ctx)
        val state = p.getString(STATE, OFF)
        if (!isPending(state) || p.getLong(CMD_SENT_AT, 0) > 0) return
        val t = timing(state)
        p.edit().putLong(CMD_SENT_AT, at).putLong(DEADLINE, at + t.timeout).apply()
        planPoll(ctx, at + t.firstPoll)
    }

    fun commandFailed(ctx: Context, message: String) {
        val p = prefs(ctx)
        if (!isPending(p.getString(STATE, OFF))) return
        p.edit()
            .putString(STATE, p.getString(PREV_STATE, OFF))
            .putLong(POLL_AT, 0)
            .putLong(STATUS_SENT, 0)
            .putString(MESSAGE, message)
            .apply()
    }

    fun statusFailed(ctx: Context, message: String) {
        val p = prefs(ctx)
        if (isPending(p.getString(STATE, OFF))) commandFailed(ctx, message)
        else p.edit().putLong(STATUS_SENT, 0).putString(MESSAGE, message).apply()
    }

    private fun timing(state: String?) = if (state == STARTING) START else STOP

    private fun planPoll(ctx: Context, at: Long) {
        prefs(ctx).edit().putLong(POLL_AT, at).putLong(POLL_FROM, now()).apply()
        // Wakes us up if the app is closed; while it is open, MainActivity calls tick() itself
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, PollReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** Sends a planned STATUS, gives up on what got no reply, ends an expired run */
    fun tick(ctx: Context) {
        val p = prefs(ctx)
        val now = now()
        val state = p.getString(STATE, OFF)
        if (isPending(state)) {
            val pendingSince = p.getLong(PENDING_SINCE, now)
            val pollAt = p.getLong(POLL_AT, 0)
            val statusSent = p.getLong(STATUS_SENT, 0)
            when {
                p.getLong(CMD_SENT_AT, 0) == 0L && now - pendingSince > SENT_TIMEOUT -> commandSent(ctx, pendingSince)
                pollAt in 1..now -> {
                    p.edit().putLong(POLL_AT, 0).putLong(STATUS_SENT, now).apply()
                    if (!send(ctx, "STATUS", KIND_STATUS)) commandFailed(ctx, "Не удалось отправить SMS")
                }
                statusSent > 0 && now - statusSent > REPLY_TIMEOUT -> commandFailed(ctx, "Нет ответа от Altox")
            }
            return
        }
        val statusSent = p.getLong(STATUS_SENT, 0)
        if (statusSent > 0 && now - statusSent > REPLY_TIMEOUT) statusFailed(ctx, "Нет ответа на запрос статуса")
        val endsAt = p.getLong(ENDS_AT, 0)
        if (state == ON && endsAt in 1..now) p.edit().putString(STATE, OFF).apply()
    }

    private fun send(ctx: Context, cmd: String, kind: String): Boolean {
        val p = prefs(ctx)
        val phone = p.getString(PHONE, "").orEmpty()
        if (phone.isBlank()) return false
        val text = p.getString(PASSWORD, "").orEmpty() + cmd
        val sent = PendingIntent.getBroadcast(
            ctx, kind.hashCode(),
            Intent(ctx, SentReceiver::class.java).putExtra(SentReceiver.KIND, kind),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return try {
            smsManager(ctx).sendTextMessage(phone, null, text, sent, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(ctx: Context): SmsManager {
        val subId = subscriptionId(ctx)
        return if (Build.VERSION.SDK_INT >= 31) {
            val m = ctx.getSystemService(SmsManager::class.java)
            if (subId != null) m.createForSubscriptionId(subId) else m
        } else {
            if (subId != null) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
    }

    private fun subscriptionId(ctx: Context): Int? {
        val slot = prefs(ctx).getInt(SIM_SLOT, -1)
        if (slot < 0) return null
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)
            return null
        return ctx.getSystemService(SubscriptionManager::class.java)
            .getActiveSubscriptionInfoForSimSlotIndex(slot)?.subscriptionId
    }

    /** Whether an SMS came from the Altox number: the last 10 digits match (+7..., 8...) */
    fun isFromAltox(ctx: Context, sender: String?): Boolean {
        val phone = prefs(ctx).getString(PHONE, "").orEmpty().filter { it.isDigit() }.takeLast(10)
        val from = sender.orEmpty().filter { it.isDigit() }.takeLast(10)
        return phone.isNotEmpty() && phone == from
    }

    class Status(
        val on: Boolean?, val minutes: Long?,
        val coolant: String?, val cabin: String?, val voltage: String?, val flame: Boolean?,
    )

    private val stateRe = Regex("""^HEATER\s+(ON|OFF)\b(?:\D*(\d+)\s*MIN)?""", RegexOption.IGNORE_CASE)
    private val numberRe = Regex("""-?\d+(?:[.,]\d+)?""")

    /** Null if the SMS is not a status reply */
    fun parse(text: String): Status? {
        var on: Boolean? = null
        var minutes: Long? = null
        val fields = mutableMapOf<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            val state = stateRe.find(line)
            if (state != null) {
                on = state.groupValues[1].equals("ON", true)
                minutes = state.groupValues[2].toLongOrNull()
                continue
            }
            val colon = line.indexOf(':')
            if (colon < 0) continue
            // "Temp heater" is the WBUS-4/5 spelling
            val key = line.substring(0, colon).trim().uppercase().removePrefix("TEMP ").trim()
            fields[key] = line.substring(colon + 1).trim()
        }
        fun number(key: String) = fields[key]?.let { numberRe.find(it)?.value?.replace(',', '.') }
        val status = Status(
            on, minutes,
            coolant = number("HEATER"),
            cabin = number("SENSOR"),
            voltage = number("VOLTAGE"),
            flame = fields["FLAME"]?.let { it.equals("YES", true) },
        )
        val known = on != null || status.coolant != null || status.cabin != null || status.flame != null
        return if (known) status else null
    }

    fun onReply(ctx: Context, text: String) {
        val p = prefs(ctx)
        val s = parse(text)
        if (s == null) {
            p.edit().putString(MESSAGE, "Непонятный ответ Altox:\n" + text.trim().take(160)).apply()
            return
        }
        val now = now()
        val e = p.edit()
            .putString(COOLANT, s.coolant.orEmpty())
            .putString(CABIN, s.cabin.orEmpty())
            .putString(VOLTAGE, s.voltage.orEmpty())
            .putBoolean(FLAME, s.flame == true)
            .putLong(STATUS_TIME, now)
            .putLong(STATUS_SENT, 0)
        if (s.on == null) {
            e.apply()
            return
        }
        val state = p.getString(STATE, OFF)
        when {
            state == STARTING && s.on -> {
                val run = minutes(ctx) * 60_000L
                val sentAt = p.getLong(CMD_SENT_AT, 0).takeIf { it > 0 } ?: now
                e.putString(STATE, ON).putLong(ON_SINCE, sentAt)
                    .putLong(RUN_TOTAL, run).putLong(ENDS_AT, sentAt + run).putLong(POLL_AT, 0)
            }
            state == STOPPING && !s.on -> e.putString(STATE, OFF).putLong(POLL_AT, 0)
            isPending(state) -> {
                e.apply()
                val next = now + timing(state).pollEvery
                if (next > p.getLong(DEADLINE, 0)) {
                    p.edit().putString(PREV_STATE, if (s.on) ON else OFF).apply()
                    commandFailed(ctx, if (state == STARTING) "Отопитель не запустился" else "Отопитель не остановился")
                } else planPoll(ctx, next)
                return
            }
            s.on && state != ON -> {
                // Started elsewhere: from the reply's minutes, if any
                val run = (s.minutes ?: 0) * 60_000
                e.putString(STATE, ON).putLong(ON_SINCE, now)
                    .putLong(RUN_TOTAL, run).putLong(ENDS_AT, if (run > 0) now + run else 0)
            }
            !s.on -> e.putString(STATE, OFF)
        }
        e.apply()
    }

    private fun now() = System.currentTimeMillis()
}
