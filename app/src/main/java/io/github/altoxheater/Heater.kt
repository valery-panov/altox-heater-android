package io.github.altoxheater

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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

    // After a command: the first STATUS after pollDelay() (the module shows HEATER ON / HEATER
    // OFF right away, the flame and the purge come later, so normally that confirms it), then
    // every POLL_EVERY while the state is not the wanted one, for up to POLL_WINDOW more
    private const val POLL_EVERY = 12_000L
    private const val POLL_WINDOW = 50_000L

    /** Settings' defaults and ranges, seconds */
    const val POLL_DELAY_DEFAULT = 6
    val POLL_DELAY_RANGE = 3..60
    const val REPLY_TIMEOUT_DEFAULT = 60
    val REPLY_TIMEOUT_RANGE = 10..300
    const val PERIODIC_DEFAULT = 600
    val PERIODIC_STEPS = listOf(300, 600, 900, 1200, 1800, 2700, 3600)

    /** A heater that stops this much before the end of the run stopped on its own: a problem */
    private const val EARLY_STOP = 2 * 60_000L
    /** Some phones never report the SMS as sent: then count it as sent after this */
    private const val SENT_TIMEOUT = 60_000L

    /** Run times on the slider: fine steps for short runs, coarse for long ones */
    val RUN_MINUTES = listOf(10, 11, 12, 13, 14, 15, 17, 20, 25, 30, 35, 40, 45, 50, 60, 70, 80, 90, 100, 120)

    // Settings
    const val PHONE = "phone"
    const val SIM_SLOT = "simSlot" // -1: the phone's default SIM for SMS
    const val PASSWORD = "password" // written before each command, if PASS is set on the module
    const val MINUTES = "minutes" // run time for START, the slider on the main screen
    const val REPLY_TIMEOUT = "replyTimeout" // seconds, see replyTimeout()
    const val POLL_DELAY = "pollDelay" // seconds from a command to its STATUS
    const val PERIODIC = "periodic" // STATUS every PERIODIC_INTERVAL while heating
    const val PERIODIC_INTERVAL = "periodicInterval" // seconds, one of PERIODIC_STEPS

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
    const val STATUS_AUTO = "statusAuto" // that STATUS is a periodic one: the buttons stay free
    const val AUTO_SENT = "autoSent" // the last periodic STATUS
    const val STATUS_TIME = "statusTime"
    const val COOLANT = "coolant"
    const val CABIN = "cabin"
    const val VOLTAGE = "voltage"
    const val FLAME = "flame"

    const val KIND_COMMAND = "command"
    const val KIND_STATUS = "status"

    /** MainActivity is on screen: no notifications then */
    @Volatile var foreground = false

    /** State is written with commit(): after a receiver returns, the process may be killed at once */
    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("heater", Context.MODE_PRIVATE)

    fun isConfigured(ctx: Context) = !prefs(ctx).getString(PHONE, "").isNullOrBlank()

    /** The saved run time, snapped to the nearest step */
    fun minutes(ctx: Context): Int {
        val saved = prefs(ctx).getInt(MINUTES, 30).takeIf { it > 0 } ?: 30
        return RUN_MINUTES.minBy { abs(it - saved) }
    }

    fun pollDelay(ctx: Context) =
        prefs(ctx).getInt(POLL_DELAY, POLL_DELAY_DEFAULT).coerceIn(POLL_DELAY_RANGE) * 1000L

    /** Interval of the periodic STATUS while heating, ms; 0 if it is off */
    fun periodic(ctx: Context): Long {
        val p = prefs(ctx)
        if (!p.getBoolean(PERIODIC, false)) return 0
        val saved = p.getInt(PERIODIC_INTERVAL, PERIODIC_DEFAULT)
        return PERIODIC_STEPS.minBy { abs(it - saved) } * 1000L
    }

    /** How long to wait for a reply to STATUS, ms */
    fun replyTimeout(ctx: Context) =
        prefs(ctx).getInt(REPLY_TIMEOUT, REPLY_TIMEOUT_DEFAULT).coerceIn(REPLY_TIMEOUT_RANGE) * 1000L

    fun isPending(state: String?) = state == STARTING || state == STOPPING

    fun start(ctx: Context) = command(ctx, "START:${minutes(ctx)}", STARTING)

    fun stop(ctx: Context) = command(ctx, "STOP", STOPPING)

    fun requestStatus(ctx: Context) {
        prefs(ctx).edit().putLong(STATUS_SENT, now()).putBoolean(STATUS_AUTO, false).remove(MESSAGE).commit()
        if (!send(ctx, "STATUS", KIND_STATUS)) statusFailed(ctx, "Не удалось отправить SMS")
        else scheduleWake(ctx)
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
            .commit()
        if (!send(ctx, cmd, KIND_COMMAND)) commandFailed(ctx, "Не удалось отправить SMS")
        else scheduleWake(ctx)
    }

    /** The command SMS has left: plan the first STATUS */
    fun commandSent(ctx: Context, at: Long = now()) {
        val p = prefs(ctx)
        val state = p.getString(STATE, OFF)
        if (!isPending(state) || p.getLong(CMD_SENT_AT, 0) > 0) return
        val first = at + pollDelay(ctx)
        p.edit().putLong(CMD_SENT_AT, at).putLong(DEADLINE, first + POLL_WINDOW).commit()
        planPoll(ctx, first)
    }

    fun commandFailed(ctx: Context, message: String) {
        val p = prefs(ctx)
        val state = p.getString(STATE, OFF)
        if (!isPending(state)) return
        p.edit()
            .putString(STATE, p.getString(PREV_STATE, OFF))
            .putLong(POLL_AT, 0)
            .putLong(STATUS_SENT, 0)
            .putString(MESSAGE, message)
            .commit()
        scheduleWake(ctx)
        notify(ctx, if (state == STARTING) "Пуск не подтверждён" else "Остановка не подтверждена", message)
    }

    fun statusFailed(ctx: Context, message: String) {
        val p = prefs(ctx)
        if (isPending(p.getString(STATE, OFF))) return commandFailed(ctx, message)
        p.edit().putLong(STATUS_SENT, 0).putString(MESSAGE, message).commit()
        scheduleWake(ctx)
        notify(ctx, "Статус не получен", message)
    }

    private fun planPoll(ctx: Context, at: Long) {
        prefs(ctx).edit().putLong(POLL_AT, at).putLong(POLL_FROM, now()).commit()
        scheduleWake(ctx)
    }

    /** When tick() has something to do next: a STATUS to send, a timeout, the end of a run; 0 if nothing */
    fun nextWake(ctx: Context): Long {
        val p = prefs(ctx)
        val state = p.getString(STATE, OFF)
        val statusSent = p.getLong(STATUS_SENT, 0)
        return listOfNotNull(
            p.getLong(PENDING_SINCE, 0).takeIf { isPending(state) && p.getLong(CMD_SENT_AT, 0) == 0L }
                ?.let { it + SENT_TIMEOUT + 1 },
            p.getLong(POLL_AT, 0).takeIf { isPending(state) && it > 0 },
            statusSent.takeIf { it > 0 }?.let { it + replyTimeout(ctx) + 1 },
            p.getLong(ENDS_AT, 0).takeIf { state == ON && it > 0 },
            periodicAt(ctx).takeIf { state == ON && statusSent == 0L && it > 0 },
        ).minOrNull() ?: 0
    }

    /** When the next periodic STATUS is due: an interval after the last status or request */
    private fun periodicAt(ctx: Context): Long {
        val interval = periodic(ctx)
        if (interval == 0L) return 0
        val p = prefs(ctx)
        return maxOf(p.getLong(STATUS_TIME, 0), p.getLong(AUTO_SENT, 0), p.getLong(ON_SINCE, 0)) + interval
    }

    /**
     * Wakes tick() up through PollReceiver at nextWake(), so commands go on while the app is
     * closed; while it is open, MainActivity calls tick() itself
     */
    private fun scheduleWake(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, PollReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val at = nextWake(ctx)
        when {
            at == 0L -> am.cancel(pi)
            Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms() ->
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private const val CHANNEL = "heater"

    /** Tells the result of a command when the app is not on screen */
    /** [always]: also with the app on screen, for something going wrong on its own */
    private fun notify(ctx: Context, title: String, text: String, always: Boolean = false) {
        if (foreground && !always) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Отопитель", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(1, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
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
                    p.edit().putLong(POLL_AT, 0).putLong(STATUS_SENT, now).commit()
                    if (!send(ctx, "STATUS", KIND_STATUS)) commandFailed(ctx, "Не удалось отправить SMS")
                    else scheduleWake(ctx)
                }
                statusSent > 0 && now - statusSent > replyTimeout(ctx) -> commandFailed(ctx, "Нет ответа от Altox")
            }
            return
        }
        val statusSent = p.getLong(STATUS_SENT, 0)
        if (statusSent > 0 && now - statusSent > replyTimeout(ctx)) statusFailed(ctx, "Нет ответа на запрос статуса")
        val endsAt = p.getLong(ENDS_AT, 0)
        if (state == ON && endsAt in 1..now) {
            p.edit().putString(STATE, OFF).commit()
            scheduleWake(ctx)
            notify(ctx, "Нагрев завершён", "Время работы истекло")
            return
        }
        val periodicAt = periodicAt(ctx)
        if (state == ON && statusSent == 0L && periodicAt in 1..now) {
            // Fresh temperatures and voltage; does not lock the buttons, see STATUS_AUTO
            p.edit().putLong(STATUS_SENT, now).putBoolean(STATUS_AUTO, true).putLong(AUTO_SENT, now).commit()
            if (!send(ctx, "STATUS", KIND_STATUS)) statusFailed(ctx, "Не удалось отправить SMS")
            else scheduleWake(ctx)
        }
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
            p.edit().putString(MESSAGE, "Непонятный ответ Altox:\n" + text.trim().take(160)).commit()
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
            e.commit()
            scheduleWake(ctx)
            return
        }
        val state = p.getString(STATE, OFF)
        when {
            state == STARTING && s.on -> {
                val run = minutes(ctx) * 60_000L
                val sentAt = p.getLong(CMD_SENT_AT, 0).takeIf { it > 0 } ?: now
                e.putString(STATE, ON).putLong(ON_SINCE, sentAt)
                    .putLong(RUN_TOTAL, run).putLong(ENDS_AT, sentAt + run).putLong(POLL_AT, 0)
                notify(ctx, "Нагрев запущен", "На ${minutes(ctx)} мин" + (s.coolant?.let { ", ОЖ $it °C" } ?: ""))
            }
            state == STOPPING && !s.on -> {
                e.putString(STATE, OFF).putLong(POLL_AT, 0)
                notify(ctx, "Нагрев выключен", s.coolant?.let { "ОЖ $it °C" } ?: "")
            }
            isPending(state) -> {
                e.commit()
                val next = now + POLL_EVERY
                if (next > p.getLong(DEADLINE, 0)) {
                    p.edit().putString(PREV_STATE, if (s.on) ON else OFF).commit()
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
            !s.on -> {
                e.putString(STATE, OFF)
                val endsAt = p.getLong(ENDS_AT, 0)
                if (state == ON && endsAt > 0 && now < endsAt - EARLY_STOP) {
                    val left = (endsAt - now + 59_999) / 60_000
                    val text = "Выключился за $left мин до конца" + (s.coolant?.let { ", ОЖ $it °C" } ?: "")
                    e.putString(MESSAGE, "Отопитель выключился раньше срока")
                    notify(ctx, "Отопитель выключился раньше срока", text, always = true)
                } else if (state == ON) notify(ctx, "Нагрев завершён", s.coolant?.let { "ОЖ $it °C" } ?: "")
            }
        }
        e.commit()
        scheduleWake(ctx)
    }

    /** Replaced in tests to move time forward */
    var clock: () -> Long = System::currentTimeMillis

    fun now() = clock()
}
