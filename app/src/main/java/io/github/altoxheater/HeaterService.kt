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
import android.os.IBinder
import java.util.Calendar
import java.util.Locale

/**
 * A foreground service while Heater waits for something (a reply, a planned STATUS, the end of
 * a run): with it the system does not stop the app when it is closed or swiped away, so the
 * replies and the alarms still reach it. Does nothing itself but show the state in its notification.
 */
class HeaterService : Service() {
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> show() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Heater.prefs(this).registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onDestroy() {
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
