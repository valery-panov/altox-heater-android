package io.github.altoxheater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.Calendar
import java.util.Locale

/**
 * A foreground service while Heater waits for something (a reply, a planned STATUS, the end of
 * a run): the daemon that runs the run while the app is closed. It keeps the process alive and
 * the CPU awake (a partial wake lock, honoured in Doze for a foreground service) and calls
 * Heater.tick() itself every second, so the STATUS SMS leave on time without relying on alarms,
 * which the system delays or drops for a closed app. The alarms stay as a backup. Its
 * notification shows the state.
 */
class HeaterService : Service() {
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> show() }
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var beats = 0
    private val beat = object : Runnable {
        override fun run() {
            // A reply SmsReceiver missed, from the inbox; not every second, it is a query
            if (beats++ % CATCH_UP_EVERY == 0) Heater.catchUp(this@HeaterService)
            Heater.tick(this@HeaterService)
            if (Heater.nextWake(this@HeaterService) == 0L) {
                stopSelf()
                return
            }
            // A run lasts up to 2 hours: renew the lock with a timeout rather than hold it unbounded
            wakeLock?.acquire(LOCK_MS)
            handler.postDelayed(this, BEAT_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Heater.prefs(this).registerOnSharedPreferenceChangeListener(listener)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AltoxHeater:run")
            .apply { setReferenceCounted(false); acquire(LOCK_MS) }
        handler.postDelayed(beat, BEAT_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(beat)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Heater.prefs(this).unregisterOnSharedPreferenceChangeListener(listener)
        running = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notification())
        if (intent == null) {
            // Restarted after the process died: what came meanwhile, then the alarm again
            Heater.catchUp(this)
            Heater.tick(this)
            Heater.scheduleWake(this)
        }
        return START_STICKY
    }

    private fun show() {
        if (Heater.nextWake(this) > 0) getSystemService(NotificationManager::class.java).notify(ID, notification())
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Нагрев", NotificationManager.IMPORTANCE_LOW))
        val p = Heater.prefs(this)
        val state = p.getString(Heater.STATE, Heater.OFF)
        val error = state == Heater.ON && p.getBoolean(Heater.ERROR, false)
        val endsAt = p.getLong(Heater.ENDS_AT, 0)
        val now = Heater.now()
        val title = when {
            state == Heater.STARTING -> "Запуск отопителя"
            state == Heater.STOPPING -> "Остановка отопителя"
            error -> "Отопитель не выключается"
            state == Heater.ON -> "Нагрев запущен"
            else -> "Запрос статуса"
        }
        val text = p.getString(Heater.MESSAGE, "").orEmpty().ifEmpty {
            when {
                state == Heater.ON && endsAt > now -> "Конец цикла в " + clock(endsAt)
                state == Heater.ON && endsAt > 0 -> "Цикл закончился, проверяем, что отопитель выключен"
                state == Heater.ON -> ""
                else -> "Ждём ответ Altox"
            }
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        // While heating the header counts down to the end of the run, or up from the start if it is unknown
        if (state == Heater.ON && endsAt > now) b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(endsAt)
        else if (state == Heater.ON && endsAt == 0L) b.setUsesChronometer(true).setWhen(p.getLong(Heater.ON_SINCE, now))
        else b.setShowWhen(false)
        return b.build()
    }

    private fun clock(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    companion object {
        private const val ID = 1
        private const val CHANNEL = "run"
        private const val BEAT_MS = 1_000L
        private const val CATCH_UP_EVERY = 10
        private const val LOCK_MS = 60_000L
        /** The service is up in this process */
        var running = false
            private set

        /** Starts the service when there is something to wait for ([active]), stops it when not */
        fun sync(ctx: Context, active: Boolean) {
            val intent = Intent(ctx, HeaterService::class.java)
            if (active && !running) {
                try {
                    ctx.startForegroundService(intent)
                } catch (e: Exception) {
                    // Not allowed from the background on Android 12+: the alarms and catchUp() do without it
                }
            } else if (!active && running) ctx.stopService(intent)
        }
    }
}
