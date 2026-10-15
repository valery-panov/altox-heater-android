package io.github.altoxheater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseTest {
    // A real reply of WBUS-6
    private val off = """
        HEATER OFF
        GSM (0-31): 28
        VOLTAGE: 12.9V
        HEATER: 7C
        SENSOR: 9C
        FLAME: NO
        OUTPUT: OFF
        _______________
        www.altox.ru
    """.trimIndent()

    @Test fun off() {
        val s = Heater.parse(off)!!
        assertEquals(false, s.on)
        assertEquals("7", s.coolant)
        assertEquals("9", s.cabin)
        assertEquals("12.9", s.voltage)
        assertEquals(false, s.flame)
        assertNull(s.errors)
        assertNull(s.minutes)
    }

    @Test fun burning() {
        val s = Heater.parse(off.replace("HEATER OFF", "HEATER ON").replace("FLAME: NO", "FLAME: YES")
            .replace("HEATER: 7C", "HEATER: -12C"))!!
        assertEquals(true, s.on)
        assertEquals(true, s.flame)
        assertEquals("-12", s.coolant)
    }

    // The WBUS-4/5 manual's spelling
    @Test fun wbus5() {
        val s = Heater.parse("HEATER ON 30min\r\nVoltage: 12.8V\r\nTemp heater: 50C\r\nTemp sensor: 10C\r\nFlame: no\r\nError code: 0A")!!
        assertEquals(true, s.on)
        assertEquals(30L, s.minutes)
        assertEquals("50", s.coolant)
        assertEquals("10", s.cabin)
        assertEquals("0A", s.errors)
    }

    @Test fun notStatus() {
        assertNull(Heater.parse("Баланс 120 руб."))
    }
}
