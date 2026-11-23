package io.github.altoxheater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    private val h = 3_600_000L
    private fun r(t: Long, coolant: Double, cabin: Double, on: Boolean = false) =
        History.Reading(t, coolant, cabin, 12.9, on, false)

    /** Cooling goes down to the air and never below it */
    @Test fun cool() {
        assertEquals(50.0, History.cool(50.0, 10.0, 0, 60.0), 1e-9)
        assertEquals(10.0 + 40 / Math.E, History.cool(50.0, 10.0, h, 60.0), 1e-9)
        assertEquals(5.0, History.cool(5.0, 10.0, 10 * h, 60.0), 1e-9)
    }

    /** Without the fan the cabin is the air; with it, the cabin before the run, or an hour after it */
    @Test fun ambient() {
        val run = History.Run(10 * h, 10 * h + 30 * 60_000, 30, History.RESULT_OK)
        val readings = listOf(
            r(9 * h, 20.0, -5.0),
            r(10 * h + 1_000, 20.0, -4.0, on = true),
            r(10 * h + 20 * 60_000, 60.0, 15.0, on = true),
            r(10 * h + 31 * 60_000, 70.0, 18.0),
        )
        val end = 10 * h + 31 * 60_000
        assertEquals(18.0, History.ambient(readings, listOf(run), fan = false, at = end)!!, 1e-9)
        // The first STATUS of the run is 1 s after START, before the fan warmed anything
        assertEquals(-4.0, History.ambient(readings, listOf(run), fan = true, at = end)!!, 1e-9)
        val later = readings + r(12 * h, 30.0, -2.0)
        assertEquals(-2.0, History.ambient(later, listOf(run), fan = true, at = 12 * h)!!, 1e-9)
    }

    /** The alternator's voltage, or a coolant cooling cannot explain: the engine ran; inside a run it is the run's */
    @Test fun trips() {
        fun v(t: Long, coolant: Double, volts: Double) = History.Reading(t, coolant, 0.0, volts, false, false)
        val readings = listOf(
            v(0, 60.0, 12.6),
            v(2 * h, 30.0, 12.5), // cooled: no trip
            v(4 * h, 70.0, 12.6), // warmer than 2 h before, no run: the engine ran before it
            v(10 * h, 88.0, 14.2), // running now
            v(11 * h, 89.0, 14.1), // the same trip
        )
        val trips = History.trips(readings, emptyList(), fan = false)
        assertEquals(listOf(4 * h to null, 10 * h to 14.2), trips.map { it.start to it.voltage })
        assertEquals(11 * h, trips[1].end)
        assertEquals(89.0, trips[1].maxCoolant!!, 1e-9)
        // A run between explains the warmth
        val run = History.Run(3 * h, 3 * h + 30 * 60_000, 30, History.RESULT_OK)
        assertEquals(listOf(10 * h), History.trips(readings, listOf(run), fan = false).map { it.start })
        // Heating on the move: the run's row says so, no separate trip
        val moving = History.Run(10 * h - 60_000, 11 * h, 60, History.RESULT_OK)
        assertTrue(History.onTheMove(readings, moving))
        assertEquals(listOf(4 * h), History.trips(readings, listOf(moving), fan = false).map { it.start })
    }
}
