package io.github.altoxheater

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/**
 * Heater state and Altox WBUS SMS protocol. Everything lives in SharedPreferences: the SMS
 * receivers write it, MainActivity listens for changes and redraws.
 *
 * Commands: START (or START:XX, XX = 10..120 min), STOP, STATUS. The status reply looks like
 *   HEATER ON 30min / GSM (0-31): 28 / Voltage: 12.8V / Temp heater: 50C / Temp sensor: 10C /
 *   Flame: no / Output: off / Error code: none
 * With SMSW1 set on the module, the same status comes after START and STOP, which is the
 * confirmation we wait for.
 */
object Heater {
    const val OFF = "OFF"
    const val STARTING = "STARTING"
    const val ON = "ON"
    const val STOPPING = "STOPPING"

    /** How long to wait for a reply before giving up */
    const val TIMEOUT_MS = 3 * 60_000L

    // Settings
    const val PHONE = "phone"
    const val SIM_SLOT = "simSlot" // -1: the phone's default SIM for SMS
    const val PASSWORD = "password" // written before each command, if PASS is set on the module
    const val MINUTES = "minutes" // 0: the module's TIME1

    // State
    const val STATE = "state"
    const val PREV_STATE = "prevState" // to roll back when a command fails
    const val PENDING_SINCE = "pendingSince"
    const val ON_SINCE = "onSince"
    const val ENDS_AT = "endsAt" // 0 if unknown
    const val MESSAGE = "message" // the last error, shown until the next command

    // Status
    const val STATUS_SENT = "statusSent" // 0 if no request is pending
    const val STATUS_TIME = "statusTime"
    const val COOLANT = "coolant"
    const val CABIN = "cabin"
    const val ERRORS = "errors"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("heater", Context.MODE_PRIVATE)

    fun isConfigured(ctx: Context) = !prefs(ctx).getString(PHONE, "").isNullOrBlank()

    fun start(ctx: Context) {
        val minutes = prefs(ctx).getInt(MINUTES, 0)
        command(ctx, if (minutes > 0) "START:$minutes" else "START", STARTING)
    }

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
            .remove(MESSAGE)
            .apply()
        if (!send(ctx, cmd, KIND_COMMAND)) commandFailed(ctx, "Не удалось отправить SMS")
    }

    fun commandFailed(ctx: Context, message: String) {
        val p = prefs(ctx)
        val state = p.getString(STATE, OFF)
        if (state != STARTING && state != STOPPING) return
        p.edit().putString(STATE, p.getString(PREV_STATE, OFF)).putString(MESSAGE, message).apply()
    }

    fun statusFailed(ctx: Context, message: String) {
        prefs(ctx).edit().putLong(STATUS_SENT, 0).putString(MESSAGE, message).apply()
    }

    /** Gives up on commands and status requests left without a reply, ends an expired run */
    fun checkTimeouts(ctx: Context) {
        val p = prefs(ctx)
        val now = now()
        val state = p.getString(STATE, OFF)
        if ((state == STARTING || state == STOPPING) && now - p.getLong(PENDING_SINCE, 0) > TIMEOUT_MS)
            commandFailed(ctx, "Нет ответа от Altox")
        val statusSent = p.getLong(STATUS_SENT, 0)
        if (statusSent > 0 && now - statusSent > TIMEOUT_MS) statusFailed(ctx, "Нет ответа на запрос статуса")
        val endsAt = p.getLong(ENDS_AT, 0)
        if (state == ON && endsAt > 0 && now > endsAt) p.edit().putString(STATE, OFF).apply()
    }

    const val KIND_COMMAND = "command"
    const val KIND_STATUS = "status"

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

    private val heaterRe = Regex("""HEATER\s*(ON|OFF)(?:\s*(\d+)\s*min)?""", RegexOption.IGNORE_CASE)
    private val coolantRe = Regex("""Temp\s*heater\s*:\s*(-?\d+)""", RegexOption.IGNORE_CASE)
    private val cabinRe = Regex("""Temp\s*sensor\s*:\s*(-?\d+)""", RegexOption.IGNORE_CASE)
    private val errorsRe = Regex("""Error\s*code\s*:[ \t]*([^\r\n]*)""", RegexOption.IGNORE_CASE)

    fun onReply(ctx: Context, text: String) {
        val p = prefs(ctx)
        val e = p.edit()
        val now = now()

        val coolant = coolantRe.find(text)?.groupValues?.get(1)
        val cabin = cabinRe.find(text)?.groupValues?.get(1)
        val errors = errorsRe.find(text)?.groupValues?.get(1)?.trim()
        if (coolant != null || cabin != null || errors != null) {
            e.putString(COOLANT, coolant.orEmpty())
            e.putString(CABIN, cabin.orEmpty())
            // "none" or FF: no errors; empty: the heater did not answer on the bus
            e.putString(ERRORS, when {
                errors == null -> ""
                errors.equals("none", true) || errors.equals("FF", true) -> "нет"
                else -> errors
            })
            e.putLong(STATUS_TIME, now).putLong(STATUS_SENT, 0)
        }

        val heater = heaterRe.find(text)
        if (heater != null) {
            val on = heater.groupValues[1].equals("ON", true)
            val state = p.getString(STATE, OFF)
            when {
                state == STARTING && !on -> e.putString(MESSAGE, "Отопитель не запустился")
                state == STOPPING && on -> e.putString(MESSAGE, "Отопитель не остановился")
            }
            if (on && state != ON) e.putLong(ON_SINCE, now)
            val minutes = heater.groupValues[2].toLongOrNull()
            e.putLong(ENDS_AT, if (on && minutes != null) now + minutes * 60_000 else 0)
            e.putString(STATE, if (on) ON else OFF)
        }
        e.apply()
    }

    private fun now() = System.currentTimeMillis()
}
