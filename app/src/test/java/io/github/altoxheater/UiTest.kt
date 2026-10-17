package io.github.altoxheater

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
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
        configure(periodic = 0)
        val a = launch()
        assertEquals("Пуск", power(a).label)
        assertEquals("", power(a).sub)
        assertEquals("Время работы", text(a, R.id.minutesTitle))
        assertEquals("30 мин", text(a, R.id.minutesLabel))
        assertEquals("Будет отправлено 3 SMS (пуск, статус после пуска, статус в конце).", text(a, R.id.smsLabel))
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
        assertEquals("Осталось", text(a, R.id.minutesTitle))
        assertEquals("26 мин : 47.5 сек", text(a, R.id.minutesLabel))
        // 26.8 of 30 min left: that part of the whole width
        val bar = a.findViewById<SeekBar>(R.id.minutes)
        assertEquals(bar.max * 1607_5 / 1800_0, bar.progress)
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
        assertEquals("Обновлено: 23 мин 21 с назад", text(a, R.id.statusTime))
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

    /** A 12-minute run takes the whole width: right edge at the start, left at the end */
    @Test fun sliderShortRun() {
        configure()
        Heater.prefs(app).edit().putInt(Heater.MINUTES, 12).commit()
        val a = launch()
        val bar = a.findViewById<SeekBar>(R.id.minutes)
        power(a).performClick()
        Heater.commandSent(app)
        val start = time
        advance(6_500)
        Heater.onReply(app, reply(on = true, flame = false, coolant = 12))
        val seen = (0..11).map { m ->
            advance(start + m * 60_000 + 30_000 - time)
            bar.progress
        }
        assertTrue("$seen", seen.zipWithNext().all { (x, y) -> y < x })
        assertTrue("$seen", seen.first() > bar.max * 9 / 10 && seen.last() in 1 until bar.max / 10)
    }

    @Test fun runTimeSlider() {
        configure()
        Heater.prefs(app).edit().putInt(Heater.PERIODIC_COUNT, 2).commit()
        val a = launch()
        val bar = a.findViewById<SeekBar>(R.id.minutes)
        assertEquals((Heater.RUN_MINUTES.size - 1) * MainActivity.STEP, bar.max)
        // A drag ends near a step: it snaps to it
        shadowOf(bar).onSeekBarChangeListener.onProgressChanged(bar, Heater.RUN_MINUTES.indexOf(90) * MainActivity.STEP + 20, true)
        idle()
        assertEquals("90 мин", text(a, R.id.minutesLabel))
        assertEquals("Будет отправлено 5 SMS (пуск, статус после пуска, 2\u00A0опроса, статус в конце).", text(a, R.id.smsLabel))
        shot(a, "14-slider-sms")
        assertEquals("Будет отправлено 5 SMS (пуск, статус после пуска, 2\u00A0опроса, статус в конце).", text(a, R.id.smsLabel))
        shot(a, "14-slider-sms")
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
        configure(periodic = 0)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        power(c.get()).performClick()
        c.pause().stop().destroy()

        Heater.commandSent(app) // what SentReceiver does
        assertEquals(time + 6_000, nextAlarm())
        time = nextAlarm()
        PollReceiver().onReceive(app, Intent())
        assertEquals("STATUS", lastSms())
        assertEquals(time + Heater.replyTimeout(app) + 1, nextAlarm())

        time += 5_000
        Heater.onReply(app, reply(on = true, flame = false, coolant = 12))
        assertEquals(Heater.ON, Heater.prefs(app).getString(Heater.STATE, ""))
        // Next wake-up: STATUS after the end of the run
        assertEquals(Heater.prefs(app).getLong(Heater.ENDS_AT, 0) + 6_000, nextAlarm())

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
        assertEquals(0L, Heater.nextWake(app))
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
        assertEquals("60", timeout.text.toString())
        val delay = a.findViewById<SeekBar>(R.id.pollDelay)
        assertEquals("6 с", text(a, R.id.pollDelayLabel))
        delay.progress = 60
        assertEquals("30 с", text(a, R.id.pollDelayLabel))
        delay.progress = 12
        assertEquals("13 с", text(a, R.id.pollDelayLabel))
        val count = a.findViewById<SeekBar>(R.id.periodicCount)
        assertEquals("Авто", text(a, R.id.periodicCountLabel))
        count.progress = 0
        assertEquals("Выкл", text(a, R.id.periodicCountLabel))
        assertEquals("Всего за цикл 3 SMS: пуск, статус после пуска, статус в конце", text(a, R.id.periodicLabel))
        count.progress = 2
        assertEquals("2", text(a, R.id.periodicCountLabel))
        assertEquals("Раз в 10 минут за цикл 30 мин (выбран\u00A0сейчас)\nВсего за цикл 5 SMS: пуск, статус после пуска, 2\u00A0опроса, статус в конце", text(a, R.id.periodicLabel))
        shot(a, "12-settings-periodic")
        count.progress = 3
        assertEquals("Раз в 7 минут 30 секунд за цикл 30 мин (выбран\u00A0сейчас)\nВсего за цикл 6 SMS: пуск, статус после пуска, 3\u00A0опроса, статус в конце", text(a, R.id.periodicLabel))
        count.progress = 1
        assertEquals("Раз в 15 минут за цикл 30 мин (выбран\u00A0сейчас)\nВсего за цикл 4 SMS: пуск, статус после пуска, 1\u00A0опрос, статус в конце", text(a, R.id.periodicLabel))
        count.progress = 20
        assertEquals("20", text(a, R.id.periodicCountLabel))
        assertEquals("Раз в 1 минуту 25 секунд за цикл 30 мин (выбран\u00A0сейчас)\nВсего за цикл 23 SMS: пуск, статус после пуска, 20\u00A0опросов, статус в конце", text(a, R.id.periodicLabel))
        count.progress = 30
        assertEquals("Авто", text(a, R.id.periodicCountLabel))
        assertEquals("Раз в 10 минут за цикл 30 мин (выбран\u00A0сейчас)\nВсего за цикл 5 SMS: пуск, статус после пуска, 2\u00A0опроса, статус в конце", text(a, R.id.periodicLabel))
        shot(a, "12-settings-periodic-auto")
        timeout.setText("5")
        save.performClick()
        assertFalse(a.isFinishing) // out of range
        timeout.setText("45")
        save.performClick()
        assertEquals("+79001112233", Heater.prefs(app).getString(Heater.PHONE, ""))
        assertEquals(45_000L, Heater.replyTimeout(app))
        assertEquals(13_000L, Heater.pollDelay(app))
        assertEquals(Heater.PERIODIC_AUTO, Heater.periodicSetting(app))
        assertEquals(2, Heater.periodic(app))
        assertTrue(a.isFinishing)
    }

    /** Auto: 1 to 5 STATUS by the run time; the old checkbox, if it was off, stays off */
    @Test fun periodicAuto() {
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 4),
            listOf(10, 20, 21, 35, 36, 49, 50, 120).map { Heater.autoCount(it) })
        configure()
        Heater.prefs(app).edit().putInt(Heater.PERIODIC_COUNT, Heater.PERIODIC_AUTO).putInt(Heater.MINUTES, 90).commit()
        val a = launch()
        assertEquals("Будет отправлено 7 SMS (пуск, статус после пуска, 4\u00A0опроса, статус в конце).", text(a, R.id.smsLabel))
        Heater.prefs(app).edit().putInt(Heater.MINUTES, 20).commit()
        idle()
        assertEquals("Будет отправлено 4 SMS (пуск, статус после пуска, 1\u00A0опрос, статус в конце).", text(a, R.id.smsLabel))

        Heater.prefs(app).edit().putBoolean(Heater.PERIODIC, false).putInt(Heater.PERIODIC_COUNT, 4).commit()
        assertEquals(0, Heater.periodicSetting(app))
        Heater.prefs(app).edit().putBoolean(Heater.PERIODIC, true).commit()
        assertEquals(4, Heater.periodicSetting(app))
    }

    /** Periodic STATUS turned on mid-run: the next slot of the new grid, nothing at once */
    @Test fun periodicChangedMidRun() {
        configure(periodic = 0)
        Heater.prefs(app).edit().putInt(Heater.MINUTES, 50).commit()
        val a = launch()
        power(a).performClick()
        Heater.commandSent(app)
        val start = time
        advance(6_500)
        Heater.onReply(app, reply(on = true, flame = false, coolant = 12))
        idle()
        Heater.prefs(app).edit().putInt(Heater.MINUTES, 30).commit() // the slider does not matter now

        advance(start + 12 * 60_000 + 1_000 - time) // the 12-minute slot of 9 per run has just passed
        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        s.findViewById<SeekBar>(R.id.periodicCount).progress = 9
        assertEquals("Раз в 5 минут за цикл 50 мин (текущий\u00A0нагрев)\nВсего за цикл 12 SMS: пуск, статус после пуска, 9\u00A0опросов, статус в конце", text(s, R.id.periodicLabel))
        s.findViewById<android.widget.Button>(R.id.save).performClick()
        assertTrue(s.isFinishing)
        assertEquals(start + 15 * 60_000, nextAlarm())

        assertEquals(start + 15 * 60_000, Heater.prefs(app).getLong(Heater.AUTO_AT, 0))

        // Fewer per run: the grid is redrawn, still from the start of the run
        Heater.prefs(app).edit().putInt(Heater.PERIODIC_COUNT, 4).commit()
        Heater.planPeriodic(app)
        Heater.scheduleWake(app)
        assertEquals(start + 20 * 60_000, nextAlarm())

        // The last slot is 10 minutes before the end, then only the end check
        advance(start + 40 * 60_000 - time)
        assertEquals("STATUS", lastSms())
        Heater.onReply(app, reply(on = true, flame = true, coolant = 60))
        idle()
        assertEquals(0L, Heater.prefs(app).getLong(Heater.AUTO_AT, 0))
        assertEquals(start + 50 * 60_000 + 6_000, nextAlarm())
    }

    /** The run time is over: STATUS confirms the heater is off */
    @Test fun endOfRun() {
        configure(periodic = 0)
        val a = launch()
        power(a).performClick()
        Heater.commandSent(app)
        advance(6_500)
        Heater.onReply(app, reply(on = true, flame = false, coolant = 12))
        idle()
        val endsAt = Heater.prefs(app).getLong(Heater.ENDS_AT, 0)
        assertEquals(endsAt + 6_000, nextAlarm())

        advance(endsAt + 6_000 - time)
        assertEquals("STATUS", lastSms())
        assertEquals(Heater.ON, Heater.prefs(app).getString(Heater.STATE, ""))
        assertTrue(power(a).isEnabled) // Stop stays free
        Heater.onReply(app, reply(on = false, flame = false, coolant = 70))
        idle()
        assertEquals("Пуск", power(a).label)
        assertEquals("", text(a, R.id.message))
        assertEquals(0L, Heater.nextWake(app))
    }

    /** The STATUS after the end got no reply: the run ends anyway */
    @Test fun endOfRunNoReply() {
        configure()
        launch()
        Heater.onReply(app, reply(on = true, flame = true, coolant = 50))
        Heater.prefs(app).edit().putLong(Heater.ENDS_AT, time + 60_000).putLong(Heater.RUN_TOTAL, 60_000).commit()
        time += 66_000
        Heater.tick(app)
        assertEquals("STATUS", lastSms())
        time += Heater.replyTimeout(app) + 1
        Heater.tick(app)
        Heater.tick(app)
        assertEquals(Heater.OFF, Heater.prefs(app).getString(Heater.STATE, ""))
        assertEquals(0L, Heater.nextWake(app))
    }

    /** Still on after the end: started again elsewhere */
    @Test fun endOfRunStillOn() {
        configure()
        launch()
        Heater.onReply(app, reply(on = true, flame = true, coolant = 50))
        Heater.prefs(app).edit().putLong(Heater.ENDS_AT, time + 60_000).putLong(Heater.RUN_TOTAL, 60_000).commit()
        time += 66_000
        Heater.tick(app)
        Heater.onReply(app, reply(on = true, flame = true, coolant = 60))
        assertEquals(Heater.ON, Heater.prefs(app).getString(Heater.STATE, ""))
        assertEquals(0L, Heater.prefs(app).getLong(Heater.ENDS_AT, -1))
    }

    @Test fun pollDelaySetting() {
        configure()
        Heater.prefs(app).edit().putInt(Heater.POLL_DELAY, 15).commit()
        val a = launch()
        power(a).performClick()
        Heater.commandSent(app)
        assertEquals(time + 15_000, nextAlarm())
    }

    /** 5 STATUS per 30-minute run, every 5 minutes: fresh temperatures, Stop stays free */
    @Test fun periodicStatus() {
        configure()
        Heater.prefs(app).edit().putInt(Heater.PERIODIC_COUNT, 5).commit()
        val a = launch()
        power(a).performClick()
        Heater.commandSent(app)
        val start = time
        advance(6_500)
        Heater.onReply(app, reply(on = true, flame = false, coolant = 12))
        idle()
        assertEquals(start + 300_000, nextAlarm()) // 5 per 30 minutes: the run in 6 steps
        assertEquals("Будет отправлено 8 SMS (пуск, статус после пуска, 5\u00A0опросов, статус в конце).", text(a, R.id.smsLabel))

        advance(300_000)
        assertEquals("STATUS", lastSms())
        assertTrue(Heater.prefs(app).getBoolean(Heater.STATUS_AUTO, false))
        assertTrue(power(a).isEnabled)
        assertFalse(status(a).isEnabled)
        Heater.onReply(app, reply(on = true, flame = true, coolant = 35))
        idle()
        assertEquals("35 °C\n9 °C\n12.9 В", text(a, R.id.statusInfo))
        assertEquals(Heater.ON, Heater.prefs(app).getString(Heater.STATE, ""))

        // Off 20 minutes before the end: a problem, told even with the app on screen
        advance(300_000)
        Heater.onReply(app, reply(on = false, flame = false, coolant = 40))
        idle()
        assertEquals("Пуск", power(a).label)
        assertEquals("Отопитель выключился раньше срока, за 20 мин до конца", text(a, R.id.message))
        assertEquals(0L, Heater.nextWake(app))
    }

    /** A number from the contacts, without the spaces and dashes */
    @Test fun settingsContact() {
        configure()
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        a.findViewById<android.widget.ImageButton>(R.id.pickContact).performClick()
        val pick = shadowOf(a).nextStartedActivityForResult
        assertEquals(Intent.ACTION_PICK, pick.intent.action)
        assertEquals("+79001234567", SettingsActivity.cleanNumber(" +7 (900) 123-45-67"))
        assertEquals("89001234567", SettingsActivity.cleanNumber("8 900 123 45 67"))
    }

    @Test fun settingsBack() {
        configure()
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        a.findViewById<android.widget.EditText>(R.id.phone).setText("+79001112233")
        a.findViewById<android.widget.ImageButton>(R.id.back).performClick()
        assertTrue(a.isFinishing)
        assertEquals(PHONE, Heater.prefs(app).getString(Heater.PHONE, ""))
    }

    /** The timings in the tests are for a 30-minute run and a 6-second poll delay, not the defaults */
    private fun configure(periodic: Int = Heater.PERIODIC_AUTO) =
        Heater.prefs(app).edit().putString(Heater.PHONE, PHONE).putInt(Heater.PERIODIC_COUNT, periodic)
            .putInt(Heater.MINUTES, 30).putInt(Heater.POLL_DELAY, 6).commit()

    /** Before the user moves them: a 20-minute run, STATUS 3 s after a command */
    @Test fun defaults() {
        assertEquals(20, Heater.minutes(app))
        assertEquals(3_000L, Heater.pollDelay(app))
        assertEquals(Heater.PERIODIC_AUTO, Heater.periodicSetting(app))
    }

    private fun launch(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun text(a: Activity, id: Int) = a.findViewById<android.widget.TextView>(id).text.toString()

    private fun power(a: Activity) = a.findViewById<RoundButton>(R.id.power)
    private fun status(a: Activity) = a.findViewById<RoundButton>(R.id.status)

    private fun lastSms(): String? =
        shadowOf(app.getSystemService(SmsManager::class.java)).lastSentTextMessageParams?.text

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun nextAlarm(): Long =
        shadowOf(app.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm()!!.triggerAtMs

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
