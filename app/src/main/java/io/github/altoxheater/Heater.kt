package io.github.altoxheater

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
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
 *
 * A run: START, STATUS a second later (HEATER ON, the flame icon flickers), STATUS after
 * flameDelay() (FLAME: YES, the icon lights up), the periodic STATUS, and STATUS END_DELAY
 * after the end. The module stops by itself; if it is still on, STOP and STATUS a second later.
 * Off: the finish sound, still on: the error state and the error sound.
 * No flame at the flame STATUS, or no reply to it: one more STATUS FLAME_RECHECK later; still none is an error:
 * the error sound and STOP, again with one STATUS a second later.
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

    // After a command: the first STATUS after COMMAND_DELAY (the module shows HEATER ON / HEATER
    // OFF right away, the flame and the purge come later, so normally that confirms it), then
    // every POLL_EVERY while the state is not the wanted one, for up to POLL_WINDOW more
    const val COMMAND_DELAY = 1_000L
    private const val POLL_EVERY = 12_000L
    private const val POLL_WINDOW = 50_000L
    /** From the end of a run to its STATUS: the module counts from when the START reached it */
    const val END_DELAY = 30_000L
    /** No flame at the flame STATUS: the second one, before giving up */
    const val FLAME_RECHECK = 60_000L

    /** Settings' defaults and ranges: minutes from START to FLAME: YES, seconds for the reply */
    const val FLAME_DELAY_DEFAULT = 4
    val FLAME_DELAY_RANGE = 1..9
    const val REPLY_TIMEOUT_DEFAULT = 60
    val REPLY_TIMEOUT_RANGE = 10..300
    /** Periodic STATUS per run, evenly spread over it; 0 is off, PERIODIC_AUTO picks it from the run */
    val PERIODIC_RANGE = 1..20
    const val PERIODIC_AUTO = -1
    /** No periodic STATUS this close to the end of a run: the end has its own STATUS */
    const val END_QUIET = 60_000L

    /** A heater that stops this much before the end of the run stopped on its own: a problem */
    private const val EARLY_STOP = 2 * 60_000L
    /** Some phones never report the SMS as sent: then count it as sent after this */
    private const val SENT_TIMEOUT = 60_000L

    /** Run times on the slider: fine steps for short runs, coarse for long ones */
    const val MINUTES_DEFAULT = 20
    val RUN_MINUTES = listOf(10, 11, 12, 13, 14, 15, 17, 20, 25, 30, 35, 40, 45, 50, 60, 70, 80, 90, 100, 120)

    // Settings
    const val PHONE = "phone"
    const val SIM_SLOT = "simSlot" // -1: the phone's default SIM for SMS
    const val PASSWORD = "password" // written before each command, if PASS is set on the module
    const val MINUTES = "minutes" // run time for START, the slider on the main screen
    const val REPLY_TIMEOUT = "replyTimeout" // seconds, see replyTimeout()
    const val FLAME_DELAY = "flameDelay" // minutes from START to the flame STATUS
    const val FINISH_SOUND = "finishSound" // ringtone URI, "" silent, missing: the first of ownSounds()
    const val ERROR_SOUND = "errorSound"
    const val PERIODIC = "periodic" // before v1.14: a checkbox, PERIODIC_COUNT only while it was on
    const val PERIODIC_COUNT = "periodicCount" // per run: 0, PERIODIC_RANGE or PERIODIC_AUTO

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
    const val END_CHECKED = "endChecked" // the STATUS after the end of the run is sent
    const val FORCED_STOP = "forcedStop" // why we sent STOP ourselves: STOP_END, STOP_NO_FLAME; "" the user did
    const val STOP_END = "end" // the heater did not stop at the end of the run
    const val STOP_NO_FLAME = "noFlame"
    const val FLAME_CHECK_AT = "flameCheckAt" // the flame STATUS; 0: sent or none
    const val FLAME_RECHECKED = "flameRechecked" // the flame STATUS was repeated
    const val IGNITING = "igniting" // started, no flame seen yet: the icon flickers
    const val ERROR = "error" // still on after the end and our STOP

    const val MESSAGE = "message" // the last error, shown until the next command
    const val UNKNOWN_REPLY = "unknownReply" // an SMS from Altox that is not a status reply
    const val UNKNOWN_AT = "unknownAt" // when it came: it explains a timeout of a STATUS sent before it

    // Status
    const val STATUS_SENT = "statusSent" // 0 if no STATUS is waiting for a reply
    const val STATUS_AUTO = "statusAuto" // that STATUS is a periodic one: the buttons stay free
    const val AUTO_AT = "autoAt" // the next periodic STATUS, see planPeriodic(); 0: none
    const val STATUS_TIME = "statusTime"
    const val COOLANT = "coolant"
    const val CABIN = "cabin"
    const val VOLTAGE = "voltage"
    const val FLAME = "flame"

    const val KIND_COMMAND = "command"
    const val KIND_STATUS = "status"

    /** State is written with commit(): after a receiver returns, the process may be killed at once */
    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("heater", Context.MODE_PRIVATE)

    fun isConfigured(ctx: Context) = !prefs(ctx).getString(PHONE, "").isNullOrBlank()

    /** The saved run time, snapped to the nearest step */
    fun minutes(ctx: Context): Int {
        val saved = prefs(ctx).getInt(MINUTES, MINUTES_DEFAULT).takeIf { it > 0 } ?: MINUTES_DEFAULT
        return RUN_MINUTES.minBy { abs(it - saved) }
    }

    /** From START to the STATUS that should show FLAME: YES, ms */
    fun flameDelay(ctx: Context) =
        prefs(ctx).getInt(FLAME_DELAY, FLAME_DELAY_DEFAULT).coerceIn(FLAME_DELAY_RANGE) * 60_000L

    /** Periodic STATUS per run while heating, PERIODIC_AUTO resolved for periodicRun(); 0 if it is off */
    fun periodic(ctx: Context) = periodicCount(ctx, periodicSetting(ctx))

    /** The saved setting: 0 (off), a count from PERIODIC_RANGE or PERIODIC_AUTO, the default */
    fun periodicSetting(ctx: Context): Int {
        val p = prefs(ctx)
        if (p.contains(PERIODIC) && !p.getBoolean(PERIODIC, false)) return 0
        val saved = p.getInt(PERIODIC_COUNT, PERIODIC_AUTO)
        return if (saved == PERIODIC_AUTO) saved else saved.coerceIn(0, PERIODIC_RANGE.last)
    }

    /** The count a [setting] gives for the run periodicRun() */
    fun periodicCount(ctx: Context, setting: Int) =
        if (setting == PERIODIC_AUTO) autoCount((periodicRun(ctx) / 60_000).toInt()) else setting

    /** PERIODIC_AUTO: more STATUS for longer runs; the flame STATUS covers the first part */
    fun autoCount(minutes: Int) = when {
        minutes <= 20 -> 0
        minutes <= 35 -> 1
        minutes <= 49 -> 2
        else -> 3
    }

    /** SMS a run sends: START, the STATUS after it, the flame one, [count] periodic ones, the end one */
    fun smsPerRun(count: Int) = count + 4

    private const val FIXED_STATUS = "пуск, статус после пуска, статус пламени"

    /**
     * What a run sends, for [count] periodic STATUS (0: off) and the flame STATUS after [flame] ms, shown in the settings:
     * "Раз в 15 минут 20 секунд за цикл 50 мин (выбран сейчас)
     * Всего за цикл 6 SMS: пуск, статус после пуска, статус пламени, 2 опроса, статус в конце"
     */
    fun periodicText(ctx: Context, count: Int, flame: Long = flameDelay(ctx)): String {
        if (count == 0) return "Всего за цикл ${smsPerRun(0)} SMS: $FIXED_STATUS, статус в конце"
        val p = prefs(ctx)
        val run = periodicRun(ctx)
        val heating = p.getString(STATE, OFF) == ON && p.getLong(RUN_TOTAL, 0) > 0
        val s = (periodicStep(count, run, flame) / 1000).toInt()
        val m = s / 60
        val time = listOfNotNull(
            if (m > 0) "$m " + plural(m, "минуту", "минуты", "минут") else null,
            if (s % 60 > 0) "${s % 60} " + plural(s % 60, "секунду", "секунды", "секунд") else null,
        ).joinToString(" ")
        val cycle = if (heating) "текущий\u00A0нагрев" else "выбран\u00A0сейчас"
        return "Раз в $time за цикл ${run / 60_000} мин ($cycle)\n" +
            "Всего за цикл ${smsPerRun(count)} SMS: $FIXED_STATUS, $count\u00A0" +
            plural(count, "опрос", "опроса", "опросов") + ", статус в конце"
    }

    /** Under the run slider: "Будет отправлено 6 SMS (пуск, статус после пуска, статус пламени, 2 опроса, статус в конце)." */
    fun smsText(count: Int): String {
        val polls = if (count > 0) "$count\u00A0" + plural(count, "опрос", "опроса", "опросов") + ", " else ""
        return "Будет отправлено ${smsPerRun(count)} SMS ($FIXED_STATUS, ${polls}статус в конце)."
    }

    private fun plural(n: Int, one: String, few: String, many: String) = when {
        n % 100 in 11..14 -> many
        n % 10 == 1 -> one
        n % 10 in 2..4 -> few
        else -> many
    }

    /** Time between periodic STATUS, ms: [count] of them split a run of [run] ms evenly after the flame STATUS at [flame] */
    fun periodicStep(count: Int, run: Long, flame: Long) = (run - flame).coerceAtLeast(0) / (count + 1)

    /** The run the periodic STATUS are spread over, ms: the current one, or the slider's if unknown */
    fun periodicRun(ctx: Context): Long {
        val p = prefs(ctx)
        val total = p.getLong(RUN_TOTAL, 0)
        return if (p.getString(STATE, OFF) == ON && total > 0) total else minutes(ctx) * 60_000L
    }

    /** How long to wait for a reply to STATUS, ms */
    fun replyTimeout(ctx: Context) =
        prefs(ctx).getInt(REPLY_TIMEOUT, REPLY_TIMEOUT_DEFAULT).coerceIn(REPLY_TIMEOUT_RANGE) * 1000L

    fun isPending(state: String?) = state == STARTING || state == STOPPING

    fun start(ctx: Context) {
        prefs(ctx).edit().putBoolean(IGNITING, true).putBoolean(FLAME_RECHECKED, false).commit()
        command(ctx, "START:${minutes(ctx)}", STARTING)
    }

    /** [forced]: STOP_END or STOP_NO_FLAME, one STATUS decides; [message] stays on screen meanwhile */
    fun stop(ctx: Context, forced: String = "", message: String? = null) {
        prefs(ctx).edit().putString(FORCED_STOP, forced).commit()
        command(ctx, "STOP", STOPPING, message)
    }

    fun requestStatus(ctx: Context) {
        prefs(ctx).edit().putLong(STATUS_SENT, now()).putBoolean(STATUS_AUTO, false).remove(MESSAGE).commit()
        if (!send(ctx, "STATUS", KIND_STATUS)) statusFailed(ctx, "Не удалось отправить SMS")
        else scheduleWake(ctx)
    }

    private fun command(ctx: Context, cmd: String, pending: String, message: String? = null) {
        val p = prefs(ctx)
        p.edit()
            .putString(PREV_STATE, p.getString(STATE, OFF))
            .putString(STATE, pending)
            .putLong(PENDING_SINCE, now())
            .putLong(CMD_SENT_AT, 0)
            .putLong(POLL_AT, 0)
            .putLong(STATUS_SENT, 0)
            .putString(MESSAGE, message.orEmpty())
            .remove(ERROR)
            .commit()
        if (!send(ctx, cmd, KIND_COMMAND)) commandFailed(ctx, "Не удалось отправить SMS")
        else scheduleWake(ctx)
    }

    /** The command SMS has left: plan the first STATUS */
    fun commandSent(ctx: Context, at: Long = now()) {
        val p = prefs(ctx)
        val state = p.getString(STATE, OFF)
        if (!isPending(state) || p.getLong(CMD_SENT_AT, 0) > 0) return
        val first = at + COMMAND_DELAY
        val window = if (p.getString(FORCED_STOP, "").isNullOrEmpty()) POLL_WINDOW else 0
        p.edit().putLong(CMD_SENT_AT, at).putLong(DEADLINE, first + window).commit()
        planPoll(ctx, first)
    }

    fun commandFailed(ctx: Context, message: String) {
        val p = prefs(ctx)
        val state = p.getString(STATE, OFF)
        if (!isPending(state)) return
        val e = p.edit()
            .putString(STATE, p.getString(PREV_STATE, OFF))
            .putLong(POLL_AT, 0)
            .putLong(STATUS_SENT, 0)
            .putString(MESSAGE, message)
        if (state == STARTING) e.putBoolean(IGNITING, false)
        if (state == STOPPING && !p.getString(FORCED_STOP, "").isNullOrEmpty()) {
            // Not off after our own STOP: the error state, on with an unknown end
            e.putString(STATE, ON).putBoolean(ERROR, true).putString(FORCED_STOP, "")
                .putLong(ENDS_AT, 0).putLong(RUN_TOTAL, 0).putLong(ON_SINCE, now()).putBoolean(IGNITING, false)
        }
        e.commit()
        play(ctx, ERROR_SOUND)
        planPeriodic(ctx)
        scheduleWake(ctx)
    }

    fun statusFailed(ctx: Context, message: String) {
        val p = prefs(ctx)
        if (isPending(p.getString(STATE, OFF))) return commandFailed(ctx, message)
        val flameLost = p.getString(STATE, OFF) == ON && p.getBoolean(IGNITING, false) &&
            p.getLong(FLAME_CHECK_AT, 0) == 0L
        p.edit().putLong(STATUS_SENT, 0).putString(MESSAGE, message).commit()
        if (flameLost) return flameMissing(ctx, "Нет ответа о пламени")
        scheduleWake(ctx)
    }

    /**
     * The flame STATUS showed no flame or got no reply, [why] says which: once more after
     * FLAME_RECHECK, then the error sound and STOP
     */
    private fun flameMissing(ctx: Context, why: String) {
        val p = prefs(ctx)
        val rechecked = p.getBoolean(FLAME_RECHECKED, false)
        val after = (flameDelay(ctx) + if (rechecked) FLAME_RECHECK else 0) / 60_000
        if (!rechecked) {
            // Ignition may just be slow, an SMS may be lost: ask once more
            p.edit().putBoolean(FLAME_RECHECKED, true).putLong(FLAME_CHECK_AT, now() + FLAME_RECHECK)
                .putString(MESSAGE, "$why через $after мин после пуска, ещё проверка через ${FLAME_RECHECK / 60_000} мин")
                .commit()
            planPeriodic(ctx)
            scheduleWake(ctx)
            return
        }
        p.edit().putBoolean(IGNITING, false).putLong(ENDS_AT, 0).commit()
        play(ctx, ERROR_SOUND)
        stop(ctx, STOP_NO_FLAME, "$why через $after мин после пуска, отправлен STOP")
    }

    /** What a STATUS without a reply gets: "Непонятный ответ" if something unparsable came after it was sent */
    private fun noReply(ctx: Context, message: String): String {
        val p = prefs(ctx)
        val unknown = p.getString(UNKNOWN_REPLY, "").orEmpty()
        val after = p.getLong(UNKNOWN_AT, 0) >= p.getLong(STATUS_SENT, Long.MAX_VALUE)
        return if (unknown.isNotEmpty() && after) "Непонятный ответ Altox:\n$unknown" else message
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
            endCheckAt(ctx).takeIf { state == ON && statusSent == 0L && it > 0 },
            p.getLong(FLAME_CHECK_AT, 0).takeIf { state == ON && statusSent == 0L && it > 0 },
            p.getLong(AUTO_AT, 0).takeIf { state == ON && statusSent == 0L && it > 0 },
        ).minOrNull() ?: 0
    }

    /**
     * Plans the next periodic STATUS into AUTO_AT: when a run starts, after each periodic STATUS,
     * on each reply and when the settings are saved. The run from the flame STATUS to the end is
     * split into count + 1 equal steps, a STATUS at each inner point; the next one is the first
     * point after now (so a change mid-run never sends at once) and at least half a step after the
     * last reply. Without a known end the steps go on, from the slider
     */
    fun planPeriodic(ctx: Context) {
        val p = prefs(ctx)
        val count = periodic(ctx)
        var at = 0L
        if (count > 0 && p.getString(STATE, OFF) == ON) {
            val endsAt = p.getLong(ENDS_AT, 0)
            val total = p.getLong(RUN_TOTAL, 0)
            val flame = flameDelay(ctx)
            val step = periodicStep(count, periodicRun(ctx), flame).coerceAtLeast(1)
            // A run started again after its end keeps ON_SINCE, ENDS_AT - RUN_TOTAL is its start
            val start = flame + if (endsAt > 0 && total > 0) endsAt - total else p.getLong(ON_SINCE, 0)
            val after = maxOf(start, now(), p.getLong(STATUS_TIME, 0) + step / 2 - 1)
            at = start + ((after - start) / step + 1) * step
            if (endsAt > 0 && at > endsAt - minOf(END_QUIET, step / 2)) at = 0
        }
        p.edit().putLong(AUTO_AT, at).commit()
    }

    /** When to ask STATUS after the run ends. Once that STATUS got no reply, the run just ends at that time */
    private fun endCheckAt(ctx: Context): Long {
        val endsAt = prefs(ctx).getLong(ENDS_AT, 0)
        return if (endsAt > 0) endsAt + END_DELAY else 0
    }

    /**
     * Wakes tick() up through PollReceiver at nextWake(), so commands go on while the app is
     * closed; while it is open, MainActivity calls tick() itself
     */
    fun scheduleWake(ctx: Context) {
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
                statusSent > 0 && now - statusSent > replyTimeout(ctx) -> commandFailed(ctx, noReply(ctx, "Нет ответа от Altox"))
            }
            return
        }
        val statusSent = p.getLong(STATUS_SENT, 0)
        if (statusSent > 0 && now - statusSent > replyTimeout(ctx)) statusFailed(ctx, noReply(ctx, "Нет ответа на запрос статуса"))
        val endCheckAt = endCheckAt(ctx)
        if (state == ON && p.getLong(STATUS_SENT, 0) == 0L && endCheckAt in 1..now) {
            if (!p.getBoolean(END_CHECKED, false)) {
                // Confirms the heater is off, with the temperatures it ended at
                p.edit().putLong(STATUS_SENT, now).putBoolean(STATUS_AUTO, true)
                    .putBoolean(END_CHECKED, true).commit()
                if (!send(ctx, "STATUS", KIND_STATUS)) statusFailed(ctx, "Не удалось отправить SMS")
                else scheduleWake(ctx)
                return
            }
            p.edit().putString(STATE, OFF).putBoolean(IGNITING, false).commit()
            scheduleWake(ctx)
            return
        }
        if (state == ON && p.getLong(STATUS_SENT, 0) == 0L && p.getLong(FLAME_CHECK_AT, 0) in 1..now) {
            // The heater should be burning by now
            p.edit().putLong(STATUS_SENT, now).putBoolean(STATUS_AUTO, true).putLong(FLAME_CHECK_AT, 0).commit()
            planPeriodic(ctx)
            if (!send(ctx, "STATUS", KIND_STATUS)) statusFailed(ctx, "Не удалось отправить SMS")
            else scheduleWake(ctx)
            return
        }
        if (state == ON && statusSent == 0L && p.getLong(AUTO_AT, 0) in 1..now) {
            // Fresh temperatures and voltage; does not lock the buttons, see STATUS_AUTO
            p.edit().putLong(STATUS_SENT, now).putBoolean(STATUS_AUTO, true).commit()
            planPeriodic(ctx)
            if (!send(ctx, "STATUS", KIND_STATUS)) statusFailed(ctx, "Не удалось отправить SMS")
            else scheduleWake(ctx)
        }
    }

    /** The ringtone saved in [key] (FINISH_SOUND or ERROR_SOUND); null: silent */
    fun sound(ctx: Context, key: String): Uri? {
        val p = prefs(ctx)
        if (!p.contains(key)) return defaultSound(ctx, key)
        return p.getString(key, "").takeUnless { it.isNullOrEmpty() }?.let(Uri::parse)
    }

    fun defaultSound(ctx: Context, key: String): Uri = ownSounds(ctx, key).first().first

    /** The app's sounds for [key] with their titles, from res/raw (made by tools/make_sounds.py) */
    fun ownSounds(ctx: Context, key: String): List<Pair<Uri, String>> {
        val sounds = if (key == ERROR_SOUND)
            listOf(R.raw.error_alarm to "Тревога", R.raw.error_siren to "Сирена", R.raw.error_buzzer to "Зуммер")
        else
            listOf(R.raw.finish_chime to "Колокольчик", R.raw.finish_arpeggio to "Аккорд", R.raw.finish_ready to "Три сигнала")
        // By name, not by id: the saved URI has to survive the next build
        return sounds.map { (id, title) ->
            Uri.parse("android.resource://${ctx.packageName}/raw/${ctx.resources.getResourceEntryName(id)}") to title
        }
    }

    /** [uri] ready to play as [key]'s sound: the error one as an alarm, heard in silent mode too */
    fun ringtone(ctx: Context, uri: Uri, key: String): Ringtone? = try {
        RingtoneManager.getRingtone(ctx, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(if (key == ERROR_SOUND) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }
    } catch (e: Exception) {
        null
    }

    /** Plays the sound once; replaced in tests */
    var play: (Context, String) -> Unit = ::ring

    /** A missing ringtone is just silence */
    private fun ring(ctx: Context, key: String) {
        val uri = sound(ctx, key) ?: return
        try {
            ringtone(ctx, uri, key)?.play()
        } catch (e: Exception) {
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
            // The real reply may still come; if not, the timeout tells this instead of "Нет ответа"
            val unknown = text.trim().take(160)
            p.edit().putString(MESSAGE, "Непонятный ответ Altox:\n$unknown")
                .putString(UNKNOWN_REPLY, unknown).putLong(UNKNOWN_AT, now()).commit()
            return
        }
        val now = now()
        // Flickers from START to the first FLAME: YES, or to the reply to the flame STATUS
        val igniting = p.getBoolean(IGNITING, false)
        val flameCheckDone = igniting && p.getLong(FLAME_CHECK_AT, 0) == 0L && p.getString(STATE, OFF) == ON
        val e = p.edit()
            .putString(COOLANT, s.coolant.orEmpty())
            .putString(CABIN, s.cabin.orEmpty())
            .putString(VOLTAGE, s.voltage.orEmpty())
            .putBoolean(FLAME, s.flame == true)
            .putLong(STATUS_TIME, now)
            .putLong(STATUS_SENT, 0)
        if (s.flame == true || s.on == false) e.putBoolean(IGNITING, false)
        if (igniting && s.flame == true) e.remove(MESSAGE) // "Пламени пока нет"
        val state = p.getString(STATE, OFF)
        if (flameCheckDone && s.flame != true && s.on != false) {
            e.commit()
            flameMissing(ctx, "Пламени нет")
            return
        }
        var sound: String? = null
        if (s.on == null && !isPending(state)) {
            e.commit()
            planPeriodic(ctx)
            scheduleWake(ctx)
            return
        }
        when {
            state == STARTING && s.on == true -> {
                val run = minutes(ctx) * 60_000L
                val sentAt = p.getLong(CMD_SENT_AT, 0).takeIf { it > 0 } ?: now
                e.putString(STATE, ON).putLong(ON_SINCE, sentAt).putBoolean(END_CHECKED, false)
                    .putLong(RUN_TOTAL, run).putLong(ENDS_AT, sentAt + run).putLong(POLL_AT, 0)
                    .putLong(FLAME_CHECK_AT, if (s.flame == true) 0 else sentAt + flameDelay(ctx))
            }
            state == STOPPING && s.on == false -> {
                e.putString(STATE, OFF).putLong(POLL_AT, 0).putString(FORCED_STOP, "")
                when (p.getString(FORCED_STOP, "")) {
                    STOP_END -> sound = FINISH_SOUND
                    STOP_NO_FLAME -> e.putString(MESSAGE, p.getString(MESSAGE, "").orEmpty()
                        .replace("отправлен STOP", "отопитель остановлен"))
                }
            }
            isPending(state) -> {
                // Not yet the wanted state, or a reply without HEATER ON/OFF: ask again
                e.commit()
                val next = now + POLL_EVERY
                if (next > p.getLong(DEADLINE, 0)) {
                    if (s.on != null) p.edit().putString(PREV_STATE, if (s.on) ON else OFF).commit()
                    commandFailed(ctx, when {
                        state == STARTING -> "Отопитель не запустился"
                        p.getString(FORCED_STOP, "") == STOP_END -> "Отопитель не выключился в конце цикла и после команды STOP"
                        p.getString(FORCED_STOP, "") == STOP_NO_FLAME -> "Нет пламени, и отопитель не выключился после команды STOP"
                        else -> "Отопитель не остановился"
                    })
                } else planPoll(ctx, next)
                return
            }
            s.on == true && state != ON -> {
                // Started elsewhere: from the reply's minutes, if any
                val run = (s.minutes ?: 0) * 60_000
                e.putString(STATE, ON).putLong(ON_SINCE, now).putBoolean(END_CHECKED, false)
                    .putLong(RUN_TOTAL, run).putLong(ENDS_AT, if (run > 0) now + run else 0)
                    .putLong(FLAME_CHECK_AT, 0)
            }
            s.on == true && state == ON && p.getBoolean(END_CHECKED, false) -> {
                // Still on after the end: the module should have stopped it, so we do
                e.putLong(ENDS_AT, 0).commit()
                stop(ctx, STOP_END)
                return
            }
            s.on == false -> {
                e.putString(STATE, OFF).putBoolean(ERROR, false)
                if (state == ON && p.getBoolean(END_CHECKED, false)) sound = FINISH_SOUND
                val endsAt = p.getLong(ENDS_AT, 0)
                if (state == ON && igniting) {
                    // Webasto gives up on a failed ignition and switches off
                    e.putString(MESSAGE, "Отопитель выключился, так и не разгоревшись")
                    sound = ERROR_SOUND
                } else if (state == ON && endsAt > 0 && now < endsAt - EARLY_STOP) {
                    val left = (endsAt - now + 59_999) / 60_000
                    e.putString(MESSAGE, "Отопитель выключился раньше срока, за $left мин до конца")
                    sound = ERROR_SOUND
                }
            }
        }
        e.commit()
        sound?.let { play(ctx, it) }
        planPeriodic(ctx)
        scheduleWake(ctx)
    }

    /** Replaced in tests to move time forward */
    var clock: () -> Long = System::currentTimeMillis

    fun now() = clock()
}
