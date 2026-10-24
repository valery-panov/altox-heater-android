package io.github.altoxheater

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.view.MotionEvent
import android.content.DialogInterface
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
import org.robolectric.shadows.ShadowAlertDialog
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
    private val sounds = mutableListOf<String>()

    @Before fun setUp() {
        time = System.currentTimeMillis()
        Heater.clock = { time }
        Heater.play = { _, key -> sounds += key }
        Inbox.rows.clear()
        Robolectric.setupContentProvider(Inbox::class.java, "sms")
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
        assertEquals("Будет отправлено 4 SMS (пуск, статус после пуска, статус пламени, статус в конце).", text(a, R.id.smsLabel))
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
        advance(300)
        assertEquals("статус через\n1 с", power(a).sub)
        shot(a, "04-starting-countdown")

        advance(1_000)
        assertEquals("STATUS", lastSms())
        assertTrue(power(a).sub.startsWith("ждём ответ"))
        shot(a, "05-starting-waiting")

        // On, not burning yet: the flame flickers until the flame STATUS
        Heater.onReply(app, reply(on = true, flame = false, coolant = 8))
        advance(192_500 - 1_300) // 3 min 12.5 s since START left
        assertEquals("Розжиг", a.findViewById<android.widget.ImageView>(R.id.flame).contentDescription)
        assertEquals("Стоп", power(a).label)
        assertEquals("", power(a).sub)
        assertEquals("Осталось", text(a, R.id.minutesTitle))
        assertEquals("26 мин : 47.5 сек", text(a, R.id.minutesLabel))
        // 26.8 of 30 min left: that part of the whole width
        val bar = a.findViewById<SeekBar>(R.id.minutes)
        assertEquals(bar.max * 1607_5 / 1800_0, bar.progress)
        assertTrue(power(a).isEnabled)
        shot(a, "06-on")

        advance(240_000 - 192_500)
        assertEquals("STATUS", lastSms())
        Heater.onReply(app, reply(on = true, flame = true, coolant = 41))
        idle()
        assertEquals("Пламя есть", a.findViewById<android.widget.ImageView>(R.id.flame).contentDescription)
        assertEquals("", text(a, R.id.message))
        shot(a, "06-on-flame")

        power(a).performClick()
        idle()
        assertEquals("STOP", lastSms())
        assertEquals("Остановка...", power(a).label)
        Heater.commandSent(app)
        advance(1_500)
        assertEquals("STATUS", lastSms())
        shot(a, "07-stopping-waiting")

        Heater.onReply(app, reply(on = false, flame = false, coolant = 55))
        idle()
        assertEquals("Пуск", power(a).label)
        assertEquals("Нагрев выключен", a.findViewById<android.widget.TextView>(R.id.caption).text)
        assertEquals(listOf<String>(), sounds) // our own Stop: no sound
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
        // The module keeps saying OFF: STATUS at 1 s, then every 12 s after a reply, up to 51 s
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
        assertEquals(listOf(Heater.ERROR_SOUND), sounds)
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
        assertEquals(listOf(Heater.ERROR_SOUND), sounds)
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
            // The flame STATUS at 4 min
            if (Heater.prefs(app).getLong(Heater.STATUS_SENT, 0) > 0) Heater.onReply(app, reply(on = true, flame = true, coolant = 30))
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
        assertEquals("Будет отправлено 6 SMS (пуск, статус после пуска, статус пламени, 2\u00A0опроса, статус в конце).", text(a, R.id.smsLabel))
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
        val start = time
        assertEquals(time + 1_000, nextAlarm())
        time = nextAlarm()
        PollReceiver().onReceive(app, Intent())
        assertEquals("STATUS", lastSms())
        assertEquals(time + Heater.replyTimeout(app) + 1, nextAlarm())

        time += 5_000
        Heater.onReply(app, reply(on = true, flame = false, coolant = 12))
        assertEquals(Heater.ON, Heater.prefs(app).getString(Heater.STATE, ""))
        // Next wake-up: the flame STATUS
        assertEquals(start + 240_000, nextAlarm())

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
        val count = a.findViewById<SeekBar>(R.id.periodicCount)
        assertEquals("Авто", text(a, R.id.periodicCountLabel))
        count.progress = 0
        assertEquals("Выкл", text(a, R.id.periodicCountLabel))
        assertEquals("Всего за цикл 4 SMS: пуск, статус после пуска, статус пламени, статус в конце", text(a, R.id.periodicLabel))
        // The polls share the run after the flame STATUS: 26 of 30 minutes
        count.progress = 2
        assertEquals("2", text(a, R.id.periodicCountLabel))
        assertEquals("Раз в 8 минут 40 секунд за цикл 30 мин (выбран\u00A0сейчас)\nВсего за цикл 6 SMS: пуск, статус после пуска, статус пламени, 2\u00A0опроса, статус в конце", text(a, R.id.periodicLabel))
        shot(a, "12-settings-periodic")
        count.progress = 3
        assertEquals("Раз в 6 минут 30 секунд за цикл 30 мин (выбран\u00A0сейчас)\nВсего за цикл 7 SMS: пуск, статус после пуска, статус пламени, 3\u00A0опроса, статус в конце", text(a, R.id.periodicLabel))
        count.progress = 1
        assertEquals("Раз в 13 минут за цикл 30 мин (выбран\u00A0сейчас)\nВсего за цикл 5 SMS: пуск, статус после пуска, статус пламени, 1\u00A0опрос, статус в конце", text(a, R.id.periodicLabel))
        count.progress = 20
        assertEquals("20", text(a, R.id.periodicCountLabel))
        assertEquals("Раз в 1 минуту 14 секунд за цикл 30 мин (выбран\u00A0сейчас)\nВсего за цикл 24 SMS: пуск, статус после пуска, статус пламени, 20\u00A0опросов, статус в конце", text(a, R.id.periodicLabel))
        count.progress = 30
        assertEquals("Авто", text(a, R.id.periodicCountLabel))
        assertEquals("Раз в 13 минут за цикл 30 мин (выбран\u00A0сейчас)\nВсего за цикл 5 SMS: пуск, статус после пуска, статус пламени, 1\u00A0опрос, статус в конце", text(a, R.id.periodicLabel))
        shot(a, "12-settings-periodic-auto")

        // Minutes to FLAME: YES, 1 to 9; the polls follow it
        val delay = a.findViewById<SeekBar>(R.id.flameDelay)
        assertEquals("4 мин", text(a, R.id.flameDelayLabel))
        delay.progress = 60
        assertEquals("9 мин", text(a, R.id.flameDelayLabel))
        delay.progress = 1
        assertEquals("2 мин", text(a, R.id.flameDelayLabel))
        assertEquals("Раз в 14 минут за цикл 30 мин (выбран\u00A0сейчас)\nВсего за цикл 5 SMS: пуск, статус после пуска, статус пламени, 1\u00A0опрос, статус в конце", text(a, R.id.periodicLabel))

        // Sounds from our own list: silence, the app's, then the phone's
        assertEquals("Колокольчик", text(a, R.id.finishSound))
        assertEquals("Тревога", text(a, R.id.errorSound))
        a.findViewById<android.widget.TextView>(R.id.errorSound).performClick()
        var dialog = ShadowAlertDialog.getLatestAlertDialog()
        val items = (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString() }
        assertEquals(listOf("Без звука", "Тревога", "Сирена", "Зуммер"), items.take(4))
        assertEquals(1, dialog.listView.checkedItemPosition)
        dialog.listView.performItemClick(null, 2, 2) // plays it
        shot(dialog.window!!.decorView, "12-settings-sound")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("Сирена", text(a, R.id.errorSound))
        a.findViewById<android.widget.TextView>(R.id.finishSound).performClick()
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 0, 0)
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals("Без звука", text(a, R.id.finishSound))
        timeout.setText("5")
        save.performClick()
        assertFalse(a.isFinishing) // out of range
        timeout.setText("45")
        save.performClick()
        assertEquals("+79001112233", Heater.prefs(app).getString(Heater.PHONE, ""))
        assertEquals(45_000L, Heater.replyTimeout(app))
        assertEquals(120_000L, Heater.flameDelay(app))
        assertEquals(null, Heater.sound(app, Heater.FINISH_SOUND))
        assertEquals(Heater.ownSounds(app, Heater.ERROR_SOUND)[1].first, Heater.sound(app, Heater.ERROR_SOUND))
        assertEquals(Heater.PERIODIC_AUTO, Heater.periodicSetting(app))
        assertEquals(1, Heater.periodic(app))
        assertTrue(a.isFinishing)
    }

    /** Auto: 0 to 3 STATUS by the run time, besides the flame one; the old checkbox, if it was off, stays off */
    @Test fun periodicAuto() {
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 3, 3),
            listOf(10, 20, 21, 35, 36, 49, 50, 120).map { Heater.autoCount(it) })
        configure()
        Heater.prefs(app).edit().putInt(Heater.PERIODIC_COUNT, Heater.PERIODIC_AUTO).putInt(Heater.MINUTES, 90).commit()
        val a = launch()
        assertEquals("Будет отправлено 7 SMS (пуск, статус после пуска, статус пламени, 3\u00A0опроса, статус в конце).", text(a, R.id.smsLabel))
        Heater.prefs(app).edit().putInt(Heater.MINUTES, 20).commit()
        idle()
        assertEquals("Будет отправлено 4 SMS (пуск, статус после пуска, статус пламени, статус в конце).", text(a, R.id.smsLabel))

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

        advance(start + 240_000 - time) // the flame STATUS
        Heater.onReply(app, reply(on = true, flame = true, coolant = 30))

        // 9 per run split the 46 minutes after the flame STATUS into 4:36 steps: 8:36, 13:12...
        advance(start + 12 * 60_000 + 1_000 - time)
        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        s.findViewById<SeekBar>(R.id.periodicCount).progress = 9
        assertEquals("Раз в 4 минуты 36 секунд за цикл 50 мин (текущий\u00A0нагрев)\nВсего за цикл 13 SMS: пуск, статус после пуска, статус пламени, 9\u00A0опросов, статус в конце", text(s, R.id.periodicLabel))
        s.findViewById<android.widget.Button>(R.id.save).performClick()
        assertTrue(s.isFinishing)
        assertEquals(start + 792_000, nextAlarm())

        assertEquals(start + 792_000, Heater.prefs(app).getLong(Heater.AUTO_AT, 0))

        // Fewer per run: the grid is redrawn, still from the flame STATUS: 11:30 steps
        Heater.prefs(app).edit().putInt(Heater.PERIODIC_COUNT, 3).commit()
        Heater.planPeriodic(app)
        Heater.scheduleWake(app)
        assertEquals(start + 930_000, nextAlarm())

        // The last slot is 11:30 before the end, then only the end check
        advance(start + 2_310_000 - time)
        assertEquals("STATUS", lastSms())
        Heater.onReply(app, reply(on = true, flame = true, coolant = 60))
        idle()
        assertEquals(0L, Heater.prefs(app).getLong(Heater.AUTO_AT, 0))
        assertEquals(start + 50 * 60_000 + 30_000, nextAlarm())
    }

    /** The run time is over: STATUS 30 s later confirms the heater is off, the finish sound */
    @Test fun endOfRun() {
        configure(periodic = 0)
        val a = launch()
        power(a).performClick()
        Heater.commandSent(app)
        val start = time
        advance(1_500)
        Heater.onReply(app, reply(on = true, flame = false, coolant = 12))
        idle()
        assertEquals(start + 240_000, nextAlarm())
        advance(start + 240_000 - time)
        Heater.onReply(app, reply(on = true, flame = true, coolant = 30))
        idle()
        val endsAt = Heater.prefs(app).getLong(Heater.ENDS_AT, 0)
        assertEquals(endsAt + 30_000, nextAlarm())

        advance(endsAt + 30_000 - time)
        assertEquals("STATUS", lastSms())
        assertEquals(Heater.ON, Heater.prefs(app).getString(Heater.STATE, ""))
        assertTrue(power(a).isEnabled) // Stop stays free
        Heater.onReply(app, reply(on = false, flame = false, coolant = 70))
        idle()
        assertEquals("Пуск", power(a).label)
        assertEquals("", text(a, R.id.message))
        assertEquals(listOf(Heater.FINISH_SOUND), sounds)
        assertEquals(0L, Heater.nextWake(app))
    }

    /** No flame by the flame STATUS: one more a minute later; flame then is fine */
    @Test fun slowFlame() {
        configure(periodic = 0)
        val a = launch()
        val start = startedWithoutFlame(a)
        Heater.onReply(app, reply(on = true, flame = true, coolant = 14))
        idle()
        assertEquals("", text(a, R.id.message))
        assertEquals("Пламя есть", a.findViewById<android.widget.ImageView>(R.id.flame).contentDescription)
        assertEquals(listOf<String>(), sounds)
        assertEquals(Heater.prefs(app).getLong(Heater.ENDS_AT, 0) + 30_000, nextAlarm())
        assertTrue(time >= start + 300_000)
    }

    /** No flame at the second check either: the error sound, STOP, off a second later */
    @Test fun noFlame() {
        configure(periodic = 0)
        val a = launch()
        startedWithoutFlame(a)
        Heater.onReply(app, reply(on = true, flame = false, coolant = 14))
        idle()
        assertEquals("STOP", lastSms())
        assertEquals(listOf(Heater.ERROR_SOUND), sounds)
        assertEquals("Пламени нет через 5 мин после пуска, отправлен STOP", text(a, R.id.message))
        Heater.commandSent(app)
        advance(1_500)
        assertEquals("STATUS", lastSms())
        Heater.onReply(app, reply(on = false, flame = false, coolant = 14))
        idle()
        assertEquals("Пуск", power(a).label)
        assertEquals("Пламени нет через 5 мин после пуска, отопитель остановлен", text(a, R.id.message))
        assertEquals(listOf(Heater.ERROR_SOUND), sounds)
        assertEquals(0L, Heater.nextWake(app))
        shot(a, "16-no-flame")
    }

    /** No flame, and still on after our STOP: the error state */
    @Test fun noFlameStopFails() {
        configure(periodic = 0)
        val a = launch()
        startedWithoutFlame(a)
        Heater.onReply(app, reply(on = true, flame = false, coolant = 14))
        Heater.commandSent(app)
        advance(1_500)
        Heater.onReply(app, reply(on = true, flame = false, coolant = 14))
        idle()
        assertEquals("Ошибка", power(a).label)
        assertEquals("Нет пламени, и отопитель не выключился после команды STOP", text(a, R.id.message))
        assertEquals(listOf(Heater.ERROR_SOUND, Heater.ERROR_SOUND), sounds)
    }

    /** Webasto gives up on ignition by itself: the next STATUS sees it off */
    @Test fun offWhileIgniting() {
        configure(periodic = 0)
        val a = launch()
        startedWithoutFlame(a)
        Heater.onReply(app, reply(on = false, flame = false, coolant = 14))
        idle()
        assertEquals("Пуск", power(a).label)
        assertEquals("Отопитель выключился, так и не разгоревшись", text(a, R.id.message))
        assertEquals(listOf(Heater.ERROR_SOUND), sounds)
    }

    /** START, ON without flame, no flame at 4 min either; returns when START left, the second flame STATUS is sent */
    private fun startedWithoutFlame(a: MainActivity): Long {
        power(a).performClick()
        Heater.commandSent(app)
        val start = time
        advance(1_500)
        Heater.onReply(app, reply(on = true, flame = false, coolant = 12))
        advance(start + 240_000 - time)
        Heater.onReply(app, reply(on = true, flame = false, coolant = 13))
        idle()
        assertEquals("Пламени нет через 4 мин после пуска, ещё проверка через 1 мин", text(a, R.id.message))
        assertEquals("Розжиг", a.findViewById<android.widget.ImageView>(R.id.flame).contentDescription)
        assertEquals(start + 300_000, nextAlarm())
        advance(start + 300_000 - time)
        assertEquals("STATUS", lastSms())
        return start
    }

    /** No reply to the flame STATUS, twice: the same as no flame */
    @Test fun flameNoReply() {
        configure(periodic = 0)
        val a = launch()
        power(a).performClick()
        Heater.commandSent(app)
        val start = time
        advance(1_500)
        Heater.onReply(app, reply(on = true, flame = false, coolant = 12))
        advance(start + 240_000 - time)
        assertEquals("STATUS", lastSms())
        advance(Heater.replyTimeout(app) + 1_000)
        assertEquals("Нет ответа о пламени через 4 мин после пуска, ещё проверка через 1 мин", text(a, R.id.message))
        assertEquals("Розжиг", a.findViewById<android.widget.ImageView>(R.id.flame).contentDescription)
        assertEquals(start + 300_000 + Heater.replyTimeout(app) + 1_000, nextAlarm())
        advance(nextAlarm() - time)
        assertEquals("STATUS", lastSms())
        advance(Heater.replyTimeout(app) + 1_000)
        assertEquals("STOP", lastSms())
        assertEquals("Нет ответа о пламени через 5 мин после пуска, отправлен STOP", text(a, R.id.message))
        assertEquals(listOf(Heater.ERROR_SOUND), sounds)
    }

    /** Something unparsable instead of the reply: the timeout shows it, not "Нет ответа" */
    @Test fun unknownReplyThenTimeout() {
        configure()
        val a = launch()
        power(a).performClick()
        Heater.commandSent(app)
        advance(1_500)
        Heater.onReply(app, "ERROR 17")
        advance(Heater.replyTimeout(app) + 1_000)
        assertEquals("Пуск", power(a).label)
        assertEquals("Непонятный ответ Altox:\nERROR 17", text(a, R.id.message))
        // An old one does not explain a later timeout
        status(a).performClick()
        advance(Heater.replyTimeout(app) + 1_000)
        assertEquals("Нет ответа на запрос статуса", text(a, R.id.message))
    }

    /** A reply without HEATER ON/OFF while starting: asked again, not stuck */
    @Test fun startReplyWithoutState() {
        configure()
        power(launch()).performClick()
        Heater.commandSent(app)
        time += 1_000
        Heater.tick(app)
        Heater.onReply(app, "FLAME: NO\nHEATER: 7C")
        assertEquals(Heater.STARTING, Heater.prefs(app).getString(Heater.STATE, ""))
        assertEquals(time + 12_000, nextAlarm())
    }

    /** The STATUS after the end got no reply: the run ends anyway */
    @Test fun endOfRunNoReply() {
        configure()
        launch()
        Heater.onReply(app, reply(on = true, flame = true, coolant = 50))
        Heater.prefs(app).edit().putLong(Heater.ENDS_AT, time + 60_000).putLong(Heater.RUN_TOTAL, 60_000).commit()
        time += 90_000
        Heater.tick(app)
        assertEquals("STATUS", lastSms())
        time += Heater.replyTimeout(app) + 1
        Heater.tick(app)
        Heater.tick(app)
        assertEquals(Heater.OFF, Heater.prefs(app).getString(Heater.STATE, ""))
        assertEquals(0L, Heater.nextWake(app))
    }

    /** Still on 30 s after the end: STOP, STATUS a second later; off then is a normal finish */
    @Test fun endOfRunStillOn() {
        configure()
        launch()
        stillOnAtEnd()
        Heater.onReply(app, reply(on = false, flame = false, coolant = 60))
        assertEquals(Heater.OFF, Heater.prefs(app).getString(Heater.STATE, ""))
        assertEquals(listOf(Heater.FINISH_SOUND), sounds)
        assertEquals(0L, Heater.nextWake(app))
    }

    /** Still on after our STOP too: the error state, the error sound; a tap sends STOP again */
    @Test fun endOfRunStopFails() {
        configure()
        val a = launch()
        stillOnAtEnd()
        Heater.onReply(app, reply(on = true, flame = true, coolant = 60))
        idle()
        assertEquals(Heater.ON, Heater.prefs(app).getString(Heater.STATE, ""))
        assertEquals(listOf(Heater.ERROR_SOUND), sounds)
        assertEquals("Ошибка", power(a).label)
        assertTrue(power(a).isEnabled)
        assertEquals("Отопитель не выключился в конце цикла и после команды STOP", text(a, R.id.message))
        shot(a, "15-stop-failed")
        power(a).performClick()
        idle()
        assertEquals("STOP", lastSms())
        assertEquals("Остановка...", power(a).label)
    }

    /** A run of a minute, its end STATUS answered ON: STOP goes out, then STATUS a second after it left */
    private fun stillOnAtEnd() {
        Heater.onReply(app, reply(on = true, flame = true, coolant = 50))
        Heater.prefs(app).edit().putLong(Heater.ENDS_AT, time + 60_000).putLong(Heater.RUN_TOTAL, 60_000).commit()
        time += 90_000
        Heater.tick(app)
        assertEquals("STATUS", lastSms())
        Heater.onReply(app, reply(on = true, flame = true, coolant = 60))
        assertEquals("STOP", lastSms())
        assertEquals(Heater.STOPPING, Heater.prefs(app).getString(Heater.STATE, ""))
        Heater.commandSent(app)
        time += 1_000
        Heater.tick(app)
        assertEquals("STATUS", lastSms())
    }

    @Test fun flameDelaySetting() {
        configure(periodic = 0)
        Heater.prefs(app).edit().putInt(Heater.FLAME_DELAY, 2).commit()
        val a = launch()
        power(a).performClick()
        Heater.commandSent(app)
        val start = time
        assertEquals(time + 1_000, nextAlarm())
        advance(1_500)
        Heater.onReply(app, reply(on = true, flame = false, coolant = 12))
        assertEquals(start + 120_000, nextAlarm())
    }

    /** 5 STATUS per 30-minute run, every 4:20 after the flame STATUS: fresh temperatures, Stop stays free */
    @Test fun periodicStatus() {
        configure()
        Heater.prefs(app).edit().putInt(Heater.PERIODIC_COUNT, 5).commit()
        val a = launch()
        power(a).performClick()
        Heater.commandSent(app)
        val start = time
        advance(6_500)
        // Burning already: no separate flame STATUS
        Heater.onReply(app, reply(on = true, flame = true, coolant = 12))
        idle()
        assertEquals(start + 500_000, nextAlarm()) // the 26 minutes after the flame STATUS in 6 steps
        assertEquals("Будет отправлено 9 SMS (пуск, статус после пуска, статус пламени, 5\u00A0опросов, статус в конце).", text(a, R.id.smsLabel))

        advance(500_000)
        assertEquals("STATUS", lastSms())
        assertTrue(Heater.prefs(app).getBoolean(Heater.STATUS_AUTO, false))
        assertTrue(power(a).isEnabled)
        assertFalse(status(a).isEnabled)
        Heater.onReply(app, reply(on = true, flame = true, coolant = 35))
        idle()
        assertEquals("35 °C\n9 °C\n12.9 В", text(a, R.id.statusInfo))
        assertEquals(Heater.ON, Heater.prefs(app).getString(Heater.STATE, ""))

        // Off 20 minutes before the end: a problem, told even with the app on screen
        advance(93_500)
        Heater.onReply(app, reply(on = false, flame = false, coolant = 40))
        idle()
        assertEquals("Пуск", power(a).label)
        assertEquals("Отопитель выключился раньше срока, за 20 мин до конца", text(a, R.id.message))
        assertEquals(listOf(Heater.ERROR_SOUND), sounds)
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

    /** A tap only shows the hint; a hold of a second sends, sliding off or a second finger cancels */
    @Test fun holdToPress() {
        configure()
        val a = launch()
        val b = power(a)
        touch(b, MotionEvent.ACTION_DOWN)
        hold(500)
        touch(b, MotionEvent.ACTION_UP)
        assertEquals(null, lastSms())
        assertTrue(b.hintShown)
        shot(a, "21-tap-hint")
        hold(RoundButton.HINT_MS)
        assertFalse(b.hintShown)

        touch(b, MotionEvent.ACTION_DOWN)
        touch(b, MotionEvent.ACTION_MOVE, x = -10f)
        hold(1_500)
        assertEquals(null, lastSms())

        touch(b, MotionEvent.ACTION_DOWN)
        touch(b, MotionEvent.ACTION_POINTER_DOWN)
        hold(1_500)
        assertEquals(null, lastSms())

        touch(b, MotionEvent.ACTION_DOWN)
        hold(500)
        shot(a, "22-holding")
        hold(600)
        assertEquals("START:30", lastSms())
        assertEquals("Запуск...", b.label)
        touch(b, MotionEvent.ACTION_UP)
        assertFalse(b.hintShown)

        // Статус too
        Heater.commandSent(app)
        advance(1_100)
        Heater.onReply(app, reply(on = true, flame = false, coolant = 8))
        idle()
        shadowOf(app.getSystemService(SmsManager::class.java)).clearLastSentTextMessageParams()
        touch(status(a), MotionEvent.ACTION_DOWN)
        touch(status(a), MotionEvent.ACTION_UP)
        assertEquals(null, lastSms())
        touch(status(a), MotionEvent.ACTION_DOWN)
        hold(1_100)
        assertEquals("STATUS", lastSms())
    }

    /**
     * The app was stopped (swiped away on some phones) with a STATUS waiting: the reply that came
     * meanwhile is taken from the inbox when it is opened, instead of "Нет ответа"
     */
    @Test fun stoppedReplyInInbox() {
        configure(periodic = 0)
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS)
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        power(c.get()).performClick()
        c.pause().stop().destroy()
        Heater.commandSent(app)
        val start = time
        time = nextAlarm()
        PollReceiver().onReceive(app, Intent())
        assertEquals("STATUS", lastSms())
        val sent = time

        // Not delivered to SmsReceiver; the alarms are gone too
        Inbox.rows += Triple("+79990000000", reply(on = false, flame = false, coolant = 1), sent + 2_000)
        Inbox.rows += Triple("8 900 123-45-67", "PASS?", sent + 3_000)
        Inbox.rows += Triple(PHONE, reply(on = true, flame = false, coolant = 12), sent + 4_000)
        Inbox.rows += Triple(PHONE, reply(on = false, flame = false, coolant = 13), sent + Heater.replyTimeout(app) + 5_000)
        time += 10 * 60_000

        val a = launch()
        assertEquals(Heater.ON, Heater.prefs(app).getString(Heater.STATE, ""))
        assertEquals("Стоп", power(a).label)
        assertEquals(sent + 4_000, Heater.prefs(app).getLong(Heater.STATUS_TIME, 0))
        assertEquals("12 °C", text(a, R.id.statusInfo).lines().first())
        // The flame STATUS was due 4 min after START: it goes out now
        assertTrue(start + 240_000 < time)
        assertEquals("STATUS", lastSms())
        shot(a, "23-reopened-from-inbox")
    }

    /** The service runs while something is awaited and shows the state; it stops with the run */
    @Test fun serviceDuringRun() {
        configure(periodic = 0)
        val a = launch()
        power(a).performClick()
        idle()
        assertEquals(HeaterService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        val service = Robolectric.buildService(HeaterService::class.java).create().startCommand(0, 1)
        try {
            Heater.commandSent(app)
            advance(1_100)
            Heater.onReply(app, reply(on = true, flame = true, coolant = 30))
            idle()
            val n = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
            assertEquals("Нагрев запущен", n.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(n.extras.getString(Notification.EXTRA_TEXT)!!.startsWith("Конец цикла в "))
            assertTrue(n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))

            power(a).performClick()
            Heater.commandSent(app)
            advance(1_100)
            Heater.onReply(app, reply(on = false, flame = false, coolant = 30))
            idle()
            assertEquals(0L, Heater.nextWake(app))
            assertEquals(HeaterService::class.java.name, shadowOf(app).nextStoppedService.component!!.className)
        } finally {
            service.destroy()
        }
    }

    /** The timings in the tests are for a 30-minute run, not the default */
    private fun configure(periodic: Int = Heater.PERIODIC_AUTO) =
        Heater.prefs(app).edit().putString(Heater.PHONE, PHONE).putInt(Heater.PERIODIC_COUNT, periodic)
            .putInt(Heater.MINUTES, 30).commit()

    /** The app's sounds are in the APK under the names their saved URIs point to */
    @Test fun ownSounds() {
        for (key in listOf(Heater.FINISH_SOUND, Heater.ERROR_SOUND)) {
            for ((uri, _) in Heater.ownSounds(app, key)) {
                assertTrue("$uri", uri.toString().startsWith("android.resource://io.github.altoxheater/raw/"))
                app.contentResolver.openInputStream(uri)!!.use { assertTrue(it.read() >= 0) }
            }
        }
        assertEquals("finish_chime", Heater.sound(app, Heater.FINISH_SOUND)!!.lastPathSegment)
    }

    /** Before the user moves them: a 20-minute run, flame STATUS 4 min after START */
    @Test fun defaults() {
        assertEquals(20, Heater.minutes(app))
        assertEquals(240_000L, Heater.flameDelay(app))
        assertEquals(Heater.PERIODIC_AUTO, Heater.periodicSetting(app))
    }

    private fun launch(): MainActivity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

    private fun text(a: Activity, id: Int) = a.findViewById<android.widget.TextView>(id).text.toString()

    private fun power(a: Activity) = a.findViewById<RoundButton>(R.id.power)
    private fun status(a: Activity) = a.findViewById<RoundButton>(R.id.status)

    private fun lastSms(): String? =
        shadowOf(app.getSystemService(SmsManager::class.java)).lastSentTextMessageParams?.text

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun touch(v: android.view.View, action: Int, x: Float = v.width / 2f) {
        val t = android.os.SystemClock.uptimeMillis()
        val e = if (action == MotionEvent.ACTION_POINTER_DOWN) {
            val props = Array(2) { MotionEvent.PointerProperties().apply { id = it } }
            val coords = Array(2) { MotionEvent.PointerCoords().apply { this.x = x; y = v.height / 2f } }
            MotionEvent.obtain(t, t, action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        } else MotionEvent.obtain(t, t, action, x, v.height / 2f, 0)
        v.dispatchTouchEvent(e)
        e.recycle()
    }

    /** Holds the finger: the looper's clock moves, not the heater's */
    private fun hold(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

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

    private fun shot(a: Activity, name: String) = shot(a.window.decorView, name)

    private fun shot(v: android.view.View, name: String) {
        idle()
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        const val PHONE = "+79001234567"
    }

    /** The SMS inbox: rows of address, body, date; the query's date range is applied */
    class Inbox : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
            val c = MatrixCursor(arrayOf("address", "body", "date"))
            val (from, to) = args!!.map { it.toLong() }
            rows.filter { it.third in from..to }.sortedBy { it.third }.forEach { c.addRow(arrayOf(it.first, it.second, it.third)) }
            return c
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

        companion object {
            val rows = mutableListOf<Triple<String, String, Long>>()
        }
    }
}
