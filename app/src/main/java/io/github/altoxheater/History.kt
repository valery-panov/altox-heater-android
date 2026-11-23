package io.github.altoxheater

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln

/**
 * What the module reported and the runs, kept for the main screen's history, and the coolant's
 * cooling forecast from them. In SharedPreferences, as JSON.
 *
 * Cooling: Newton's law, T(t) = Ta + (T0 - Ta) * exp(-t / tau), from the last reading T0 down to the
 * air around the car Ta. The cabin sensor is a little above the outside air, so Ta is the cabin's,
 * unless the heater blows into the cabin (FAN): then the cabin is warmed during a run and an hour
 * after it, and Ta comes from a reading outside that time, or from the first STATUS of the run.
 * tau is learned: two readings with the heater off and no run between them give one; the median of
 * the last TAU_SAMPLES is used, TAU_DEFAULT until there is one.
 *
 * The engine: running at a reading when the voltage is the alternator's (ENGINE_VOLTAGE and up; the
 * battery alone, even with the heater on, stays below); ran between two readings when the coolant
 * is warmer than even the slowest cooling allows and no run was between them. Such readings teach
 * no tau. A trip after the last reading cannot be seen until the next STATUS.
 */
object History {
    const val READINGS = "historyReadings"
    const val RUNS = "historyRuns"
    const val TAUS = "coolingTaus"

    // Settings
    const val PREDICT = "predictCooling" // show the cooling forecast, default on
    const val FAN = "heaterFan" // the heater runs the cabin fan, default off

    const val RESULT_OK = "ok"
    const val RESULT_STOPPED = "stopped" // by the user's STOP
    const val RESULT_UNKNOWN = "?" // the STATUS after the end never came

    private const val KEEP_READINGS = 400
    private const val KEEP_FOR = 14 * 24 * 3_600_000L
    private const val KEEP_RUNS = 50
    private const val TAU_SAMPLES = 10
    const val TAU_DEFAULT = 90.0 // minutes
    private val TAU_RANGE = 15.0..1440.0
    /** The cabin stays warm this long after a run with the fan */
    private const val FAN_AFTER = 3_600_000L
    /** A reading this fresh needs no forecast */
    const val FORECAST_AFTER = 5 * 60_000L
    /** Readings this far apart and this far above the air teach tau */
    private const val LEARN_GAP = 20 * 60_000L
    private const val LEARN_ABOVE = 8.0
    /** Volts: the alternator charging, the engine running */
    const val ENGINE_VOLTAGE = 13.4
    /** Warmer than the slowest cooling allows by this much, and than the air: the engine ran */
    private const val ENGINE_MARGIN = 5.0
    /** Engine readings this close together are one trip */
    private const val TRIP_GAP = 3 * 3_600_000L

    data class Reading(val t: Long, val coolant: Double?, val cabin: Double?, val voltage: Double?, val on: Boolean?, val flame: Boolean) {
        /** The engine was running at this reading */
        val engine get() = (voltage ?: 0.0) >= ENGINE_VOLTAGE
    }

    /** The engine ran around [start]..[end] (the readings that show it); [voltage] the alternator's, null if only the coolant showed it */
    data class Trip(val start: Long, val end: Long, val maxCoolant: Double?, val voltage: Double?)

    /** [end] 0 while running; [result] "" while running, RESULT_* or the error */
    data class Run(val start: Long, val end: Long, val minutes: Int, val result: String) {
        val ok get() = result == RESULT_OK || result == RESULT_STOPPED
    }

    /** [warmed]: the engine ran between the reading before and [from] */
    data class Forecast(val coolant: Double, val from: Reading, val ambient: Double, val tau: Double, val samples: Int, val warmed: Boolean)

    fun predict(p: SharedPreferences) = p.getBoolean(PREDICT, true)
    fun fan(p: SharedPreferences) = p.getBoolean(FAN, false)

    fun readings(p: SharedPreferences): List<Reading> = array(p, READINGS).map { a ->
        Reading(a.getLong(0), num(a, 1), num(a, 2), num(a, 3), if (a.isNull(4)) null else a.getBoolean(4), a.optBoolean(5))
    }

    fun runs(p: SharedPreferences): List<Run> = array(p, RUNS).map { a ->
        Run(a.getLong(0), a.getLong(1), a.getInt(2), a.getString(3))
    }

    fun addReading(ctx: Context, r: Reading) {
        val p = Heater.prefs(ctx)
        val all = readings(p)
        val list = (all + r).filter { it.t > r.t - KEEP_FOR }.sortedBy { it.t }.takeLast(KEEP_READINGS)
        val e = p.edit().putString(READINGS, JSONArray(list.map { it.toJson() }).toString())
        learn(p, all, r)?.let { tau ->
            val taus = (taus(p) + tau).takeLast(TAU_SAMPLES)
            e.putString(TAUS, JSONArray(taus).toString())
        }
        e.commit()
    }

    /** A run began; one still open is closed at its planned end */
    fun startRun(ctx: Context, start: Long, minutes: Int) {
        val p = Heater.prefs(ctx)
        val list = runs(p).map { if (it.end == 0L) it.copy(end = it.start + it.minutes * 60_000L, result = RESULT_UNKNOWN) else it }
        save(p, list + Run(start, 0, minutes, ""))
    }

    /** The open run is over: [result] RESULT_* or the error */
    fun endRun(ctx: Context, end: Long, result: String) {
        val p = Heater.prefs(ctx)
        val list = runs(p)
        val last = list.lastOrNull()?.takeIf { it.end == 0L } ?: return
        save(p, list.dropLast(1) + last.copy(end = maxOf(end, last.start), result = result))
    }

    /** A START that never got HEATER ON */
    fun failedRun(ctx: Context, start: Long, end: Long, minutes: Int, result: String) {
        val p = Heater.prefs(ctx)
        save(p, runs(p) + Run(start, end, minutes, result))
    }

    private fun save(p: SharedPreferences, list: List<Run>) {
        p.edit().putString(RUNS, JSONArray(list.takeLast(KEEP_RUNS).map {
            JSONArray(listOf(it.start, it.end, it.minutes, it.result))
        }).toString()).commit()
    }

    /** The coolant at the start of [run] and the highest during it, from the readings */
    fun coolantRange(readings: List<Reading>, run: Run, now: Long): Pair<Double?, Double?> {
        val end = (if (run.end > 0) run.end else now) + 2 * 60_000
        val inRun = readings.filter { it.t >= run.start - 60_000 && it.t <= end && it.coolant != null }
        return inRun.firstOrNull()?.coolant to inRun.maxOfOrNull { it.coolant!! }
    }

    fun taus(p: SharedPreferences): List<Double> = json(p, TAUS).let { a ->
        (0 until a.length()).map { a.getDouble(it) }
    }

    /** Minutes */
    fun tau(p: SharedPreferences): Double {
        val t = taus(p).sorted()
        if (t.isEmpty()) return TAU_DEFAULT
        return if (t.size % 2 == 1) t[t.size / 2] else (t[t.size / 2 - 1] + t[t.size / 2]) / 2
    }

    /**
     * The air around the car at [at]: the cabin of the latest reading by then; with FAN, of the
     * latest one away from the runs, or of the first STATUS of the run that warmed it
     */
    fun ambient(readings: List<Reading>, runs: List<Run>, fan: Boolean, at: Long): Double? {
        val before = readings.filter { it.t <= at && it.cabin != null }
        if (!fan) return before.lastOrNull()?.cabin
        fun warmed(t: Long) = runs.any { r ->
            val end = if (r.end > 0) r.end else r.start + r.minutes * 60_000L
            t >= r.start + 60_000 && t <= end + FAN_AFTER
        }
        val clean = before.lastOrNull { !warmed(it.t) } ?: return before.firstOrNull()?.cabin
        // Over a day old, the air has changed more than a run warms the cabin
        return if (at - clean.t < 24 * 3_600_000L) clean.cabin else before.last().cabin?.let { minOf(it, clean.cabin!!) }
    }

    /** The coolant now, if it is worth forecasting: on in settings, heater off, the reading old enough */
    fun forecast(p: SharedPreferences, now: Long): Forecast? {
        if (!predict(p) || p.getString(Heater.STATE, Heater.OFF) != Heater.OFF) return null
        val readings = readings(p)
        val last = readings.lastOrNull { it.coolant != null } ?: return null
        if (last.on == true || now - last.t < FORECAST_AFTER) return null
        val ambient = ambient(readings, runs(p), fan(p), last.t) ?: return null
        val tau = tau(p)
        val runs = runs(p)
        val prev = readings.lastOrNull { it.t < last.t && it.coolant != null }
        val warmed = prev != null && warmedBetween(prev, last, runs, ambient)
        return Forecast(cool(last.coolant!!, ambient, now - last.t, tau), last, ambient, tau, taus(p).size, warmed)
    }

    /** [r]'s coolant is warmer than the slowest cooling from [prev] and the air allow, with no run between: the engine ran */
    fun warmedBetween(prev: Reading, r: Reading, runs: List<Run>, ambient: Double): Boolean {
        val t1 = prev.coolant ?: return false
        val t2 = r.coolant ?: return false
        if (runs.any { it.start <= r.t && (if (it.end > 0) it.end else Long.MAX_VALUE) >= prev.t }) return false
        val slowest = cool(t1, ambient, r.t - prev.t, TAU_RANGE.endInclusive)
        return t2 > maxOf(slowest, ambient) + ENGINE_MARGIN
    }

    /**
     * When the engine ran outside the runs (a run on the move is marked on its own row): readings
     * with the alternator's voltage, or warmer than cooling allows; close ones are one trip
     */
    fun trips(readings: List<Reading>, runs: List<Run>, fan: Boolean): List<Trip> {
        fun inRun(t: Long) = runs.any { t >= it.start - 60_000 && t <= (if (it.end > 0) it.end else Long.MAX_VALUE) + 60_000 }
        val trips = mutableListOf<Trip>()
        var prev: Reading? = null
        for (r in readings) {
            val ran = !inRun(r.t) && (r.engine || prev != null &&
                (ambient(readings, runs, fan, r.t)?.let { warmedBetween(prev!!, r, runs, it) } ?: false))
            if (r.coolant != null) prev = r
            if (!ran) continue
            val last = trips.lastOrNull()
            val voltage = r.voltage?.takeIf { r.engine }
            if (last != null && r.t - last.end < TRIP_GAP) {
                trips[trips.size - 1] = last.copy(end = r.t, maxCoolant = listOfNotNull(last.maxCoolant, r.coolant).maxOrNull(),
                    voltage = listOfNotNull(last.voltage, voltage).maxOrNull())
            } else trips += Trip(r.t, r.t, r.coolant, voltage)
        }
        return trips
    }

    /** A run with the engine running: the heater was switched on on the move */
    fun onTheMove(readings: List<Reading>, run: Run): Boolean {
        val end = if (run.end > 0) run.end else Long.MAX_VALUE
        return readings.any { it.engine && it.t >= run.start - 60_000 && it.t <= end + 60_000 }
    }

    fun cool(from: Double, ambient: Double, ms: Long, tau: Double): Double =
        if (from <= ambient) from else ambient + (from - ambient) * exp(-ms / 60_000.0 / tau)

    /** tau from the reading before [r] and [r]: both off, no run between, far enough apart and above the air */
    private fun learn(p: SharedPreferences, all: List<Reading>, r: Reading): Double? {
        val prev = all.lastOrNull() ?: return null
        val t1 = prev.coolant ?: return null
        val t2 = r.coolant ?: return null
        if (prev.on != false || r.on != false || r.t - prev.t < LEARN_GAP || t2 >= t1) return null
        // The engine may have run on after prev, or did run before r
        if (prev.engine || r.engine) return null
        val runs = runs(p)
        if (runs.any { it.start in prev.t..r.t }) return null
        val a = ambient(all + r, runs, fan(p), r.t) ?: return null
        if (t1 - a < LEARN_ABOVE || t2 - a < 1.0 || warmedBetween(prev, r, runs, a)) return null
        val tau = (r.t - prev.t) / 60_000.0 / ln((t1 - a) / (t2 - a))
        return tau.takeIf { it in TAU_RANGE }
    }

    private fun Reading.toJson() = JSONArray().apply {
        put(t); put(coolant ?: JSONObject.NULL); put(cabin ?: JSONObject.NULL)
        put(voltage ?: JSONObject.NULL); put(on ?: JSONObject.NULL); put(flame)
    }

    private fun num(a: JSONArray, i: Int) = if (a.isNull(i)) null else a.getDouble(i)

    private fun array(p: SharedPreferences, key: String): List<JSONArray> = json(p, key).let { a ->
        (0 until a.length()).map { a.getJSONArray(it) }
    }

    private fun json(p: SharedPreferences, key: String): JSONArray = try {
        JSONArray(p.getString(key, "[]"))
    } catch (e: Exception) {
        JSONArray()
    }
}
