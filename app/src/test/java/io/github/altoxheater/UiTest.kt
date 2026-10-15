package io.github.altoxheater

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.telephony.SmsManager
import android.widget.SeekBar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * The main screen through a whole start/stop cycle, with the module's replies fed in by hand
 * and a fake clock. Screenshots go to app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// SDK 36 needs Java 21 in Robolectric, the build runs on 17
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class UiTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private var time = 0L

    @Before fun setUp() {
        time = System.currentTimeMillis()
        Heater.clock = { time }
        shadowOf(app).grantPermissions(
            Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @After fun tearDown() {
        Heater.clock = System::currentTimeMillis
    }

    @Test fun notConfigured() {
        val a = launch()
        assertFalse(power(a).isEnabled)
        assertFalse(status(a).isEnabled)
        shot(a, "01-not-configured")
    }

    @Test fun startAndStop() {
        configure()
        val a = launch()
        assertEquals("Пуск", power(a).label)
        assertEquals("", power(a).sub)
        assertEquals("Время работы: 30 мин", text(a, R.id.minutesLabel))
        shot(a, "02-idle")

        power(a).performClick()
        idle()
        assertEquals("START:30", lastSms())
        assertEquals("Запуск...", power(a).label)
        assertEquals("отправка SMS", power(a).sub)
        assertFalse(power(a).isEnabled)
        assertFalse(status(a).isEnabled)
        shot(a, "03-starting-sending")

        Heater.commandSent(app)
        advance(3_000)
        assertEquals("статус через\n3 с", power(a).sub)
        shot(a, "04-starting-countdown")

        advance(3_500)
        assertEquals("STATUS", lastSms())
        assertTrue(power(a).sub.startsWith("ждём ответ"))
        shot(a, "05-starting-waiting")

        Heater.onReply(app, reply(on = true, flame = true, coolant = 41))
        advance(192_500 - 6_500) // 3 min 12.5 s since START left
        assertEquals("Стоп", power(a).label)
        assertEquals("", power(a).sub)
        assertEquals("Осталось 26 мин : 47.5 сек", text(a, R.id.minutesLabel))
        // 26.8 min sits between the 25 and 30 steps of the scale
        val bar = a.findViewById<SeekBar>(R.id.minutes)
        val i25 = Heater.RUN_MINUTES.indexOf(25) * MainActivity.STEP
        assertTrue("${bar.progress}", bar.progress in i25 + 30..i25 + 40)
        assertTrue(power(a).isEnabled)
        shot(a, "06-on")

        power(a).performClick()
        idle()
        assertEquals("STOP", lastSms())
        assertEquals("Остановка...", power(a).label)
        Heater.commandSent(app)
        advance(8_500)
        assertEquals("STATUS", lastSms())
        shot(a, "07-stopping-waiting")

        Heater.onReply(app, reply(on = false, flame = false, coolant = 55))
        idle()
        assertEquals("Пуск", power(a).label)
        assertEquals("Нагрев выключен", a.findViewById<android.widget.TextView>(R.id.caption).text)
        shot(a, "08-off")

    }


    @Test fun statusRequest() {
        configure()
        val a = launch()
        status(a).performClick()
        advance(4_000)
        assertEquals("STATUS", lastSms())
        assertFalse(power(a).isEnabled)
        assertFalse(status(a).isEnabled)
        shot(a, "09-status-waiting")

        Heater.onReply(app, reply(on = false, flame = false, coolant = 7))
        idle()
        assertTrue(power(a).isEnabled)
        assertTrue(status(a).isEnabled)
        assertEquals("Обновлено: только что", text(a, R.id.statusTime))
        advance(23 * 60_000 + 21_000)
        assertEquals("Обновлено: 23 минуты 21 секунду назад", text(a, R.id.statusTime))
        shot(a, "10-status")
        advance(37 * 60_000)
        assertTrue(text(a, R.id.statusTime).matches(Regex("Обновлено: (сегодня|вчера) \\d\\d:\\d\\d:\\d\\d")))
    }

    @Test fun startNotConfirmed() {
        configure()
        val a = launch()
        power(a).performClick()
        Heater.commandSent(app)
        // The module keeps saying OFF: STATUS at 6 s, then every 12 s after a reply, up to a minute
        var polls = 0
        repeat(80) {
            advance(1_000)
            if (Heater.prefs(app).getLong(Heater.STATUS_SENT, 0) > 0) {
                polls++
                Heater.onReply(app, reply(on = false, flame = false, coolant = 7))
            }
        }
        idle()
        assertEquals(Heater.OFF, Heater.prefs(app).getString(Heater.STATE, ""))
        assertEquals("Отопитель не запустился", a.findViewById<android.widget.TextView>(R.id.message).text)
        assertTrue("polls: $polls", polls in 3..5)
        shot(a, "11-start-failed")
    }

    @Test fun noReply() {
        configure()
        val a = launch()
        power(a).performClick()
        Heater.commandSent(app)
        advance(7_000)
        advance(Heater.replyTimeout(app) + 1_000)
        assertEquals("Пуск", power(a).label)
        assertEquals("Нет ответа от Altox", a.findViewById<android.widget.TextView>(R.id.message).text)
    }

    @Test fun unknownSms() {
        configure()
        val a = launch()
        Heater.onReply(app, "Баланс 120 руб.")
        idle()
        assertTrue(a.findViewById<android.widget.TextView>(R.id.message).text.startsWith("Непонятный ответ"))
    }

    @Test fun runTimeSlider() {
        configure()
        val a = launch()
        val bar = a.findViewById<SeekBar>(R.id.minutes)
        assertEquals((Heater.RUN_MINUTES.size - 1) * MainActivity.STEP, bar.max)
        // A drag ends near a step: it snaps to it
        shadowOf(bar).onSeekBarChangeListener.onProgressChanged(bar, Heater.RUN_MINUTES.indexOf(90) * MainActivity.STEP + 20, true)
        idle()
        assertEquals("Время работы: 90 мин", a.findViewById<android.widget.TextView>(R.id.minutesLabel).text)
        power(a).performClick()
        assertEquals("START:90", lastSms())
    }

    @Test fun senderNumber() {
        configure()
        assertTrue(Heater.isFromAltox(app, "+79001234567"))
        assertTrue(Heater.isFromAltox(app, "89001234567"))
        assertFalse(Heater.isFromAltox(app, "+79001234568"))
        assertFalse(Heater.isFromAltox(app, "ALTOX"))
    }

    /** The app is closed right after Start: the alarm and the SMS receiver finish the job */
    @Test fun closedDuringStart() {
        configure()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        power(c.get()).performClick()
        c.pause().stop().destroy()
        assertFalse(Heater.foreground)

        Heater.commandSent(app) // what SentReceiver does
        assertEquals(time + 6_000, nextAlarm())
        time = nextAlarm()
        PollReceiver().onReceive(app, Intent())
        assertEquals("STATUS", lastSms())
        assertEquals(time + Heater.replyTimeout(app) + 1, nextAlarm())

        time += 5_000
        Heater.onReply(app, reply(on = true, flame = false, coolant = 12))
        assertEquals(Heater.ON, Heater.prefs(app).getString(Heater.STATE, ""))
        assertEquals("Нагрев запущен" to "На 30 мин, ОЖ 12 °C", lastNotification())
        // Next wake-up: the end of the run
        assertEquals(Heater.prefs(app).getLong(Heater.ENDS_AT, 0), nextAlarm())

        val a = launch()
        assertEquals("Стоп", power(a).label)
        shot(a, "13-reopened-on")
    }

    /** The app is closed and Altox does not answer: the timeout fires from the alarm */
    @Test fun closedNoReply() {
        configure()
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        power(c.get()).performClick()
        c.pause().stop().destroy()
        Heater.commandSent(app)
        time = nextAlarm()
        PollReceiver().onReceive(app, Intent())
        time = nextAlarm()
        PollReceiver().onReceive(app, Intent())
        assertEquals(Heater.OFF, Heater.prefs(app).getString(Heater.STATE, ""))
        assertEquals("Пуск не подтверждён" to "Нет ответа от Altox", lastNotification())
        assertEquals(0L, Heater.nextWake(app))
    }

    /** No notifications while the screen is open */
    @Test fun noNotificationInForeground() {
        configure()
        val a = launch()
        power(a).performClick()
        Heater.commandSent(app)
        Heater.onReply(app, reply(on = true, flame = false, coolant = 12))
        idle()
        assertEquals("Стоп", power(a).label)
        assertEquals(null, lastNotification())
    }

    @Test fun settings() {
        configure()
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val save = a.findViewById<android.widget.Button>(R.id.save)
        assertFalse(save.isEnabled)
        shot(a, "12-settings")
        a.findViewById<android.widget.EditText>(R.id.phone).setText("+79001112233")
        assertTrue(save.isEnabled)
        shot(a, "12-settings-changed")
        val timeout = a.findViewById<android.widget.EditText>(R.id.replyTimeout)
        assertEquals("120", timeout.text.toString())
        timeout.setText("5")
        save.performClick()
        assertFalse(a.isFinishing) // out of range
        timeout.setText("45")
        save.performClick()
        assertEquals("+79001112233", Heater.prefs(app).getString(Heater.PHONE, ""))
        assertEquals(45_000L, Heater.replyTimeout(app))
        assertTrue(a.isFinishing)
    }

    @Test fun settingsBack() {
        configure()
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        a.findViewById<android.widget.EditText>(R.id.phone).setText("+79001112233")
        a.findViewById<android.widget.ImageButton>(R.id.back).performClick()
        assertTrue(a.isFinishing)
        assertEquals(PHONE, Heater.prefs(app).getString(Heater.PHONE, ""))
    }

    private fun configure() = Heater.prefs(app).edit().putString(Heater.PHONE, PHONE).commit()

    private fun launch(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun text(a: Activity, id: Int) = a.findViewById<android.widget.TextView>(id).text.toString()

    private fun power(a: Activity) = a.findViewById<RoundButton>(R.id.power)
    private fun status(a: Activity) = a.findViewById<RoundButton>(R.id.status)

    private fun lastSms(): String? =
        shadowOf(app.getSystemService(SmsManager::class.java)).lastSentTextMessageParams?.text

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun nextAlarm(): Long =
        shadowOf(app.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm()!!.triggerAtMs

    private fun lastNotification(): Pair<String, String>? =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.lastOrNull()?.let {
            shadowOf(it).contentTitle.toString() to shadowOf(it).contentText.toString()
        }

    /** Moves the fake clock and lets the screen's 100 ms timer run */
    private fun advance(ms: Long) {
        time += ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    }

    private fun reply(on: Boolean, flame: Boolean, coolant: Int) = """
        HEATER ${if (on) "ON" else "OFF"}
        GSM (0-31): 28
        VOLTAGE: 12.9V
        HEATER: ${coolant}C
        SENSOR: 9C
        FLAME: ${if (flame) "YES" else "NO"}
        OUTPUT: OFF
        _______________
        www.altox.ru
    """.trimIndent()

    private fun shot(a: Activity, name: String) {
        idle()
        val v = a.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        const val PHONE = "+79001234567"
    }
}
