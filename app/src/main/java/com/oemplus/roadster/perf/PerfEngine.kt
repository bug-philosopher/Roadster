package com.oemplus.roadster.perf

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

enum class PerfMode { SHORT, LONG, DRAG }

enum class PerfPhase {
    IDLE,
    STAGING,
    READY,
    RUNNING,
    FINISHED,
    ABORTED,
}

data class Split(val label: String, val ms: Long, val speedMps: Float? = null)

data class PerfResult(
    val mode: PerfMode,
    val metric: Boolean,
    val at: Long,
    val ms: Long,
    val trapMps: Float?,
    val distanceM: Double,
    val splits: List<Split>,
    val hz: Float,
    val altDeltaM: Double?,
)

data class PerfState(
    val phase: PerfPhase = PerfPhase.IDLE,
    val mode: PerfMode = PerfMode.SHORT,
    val metric: Boolean = false,
    val t0Ns: Long = 0L,
    val speedMps: Float = 0f,
    val distanceM: Double = 0.0,
    val note: String? = null,
    val haveFix: Boolean = false,
    val hz: Float = 0f,
    val result: PerfResult? = null,
)

class PerfEngine(metric: Boolean = false, mode: PerfMode = PerfMode.SHORT) {
    data class Sample(val tNs: Long, val v: Float, val alt: Double?)

    var state = PerfState(mode = mode, metric = metric)
        private set

    private var stillSinceNs = 0L
    private var lastStill: Sample? = null
    private var lastZero: Sample? = null
    private val pre = ArrayList<Sample>()
    private val run = ArrayList<Sample>()
    private var t0 = 0L
    private var t0Locked = false
    private var dist = 0.0
    private var vMax = 0f
    private var startAlt: Double? = null
    private var lastFixNs = 0L
    private val intervals = ArrayDeque<Long>()

    fun stage() {
        if (state.phase == PerfPhase.RUNNING) return
        resetRun()
        state = state.copy(phase = PerfPhase.STAGING, note = null, result = null, t0Ns = 0L, distanceM = 0.0)
    }

    fun cancel() {
        resetRun()
        state = state.copy(phase = PerfPhase.IDLE, note = null, t0Ns = 0L, distanceM = 0.0)
    }

    fun setMode(mode: PerfMode) {
        if (state.phase == PerfPhase.RUNNING) return
        state = state.copy(mode = mode, result = if (state.phase == PerfPhase.FINISHED) state.result else null)
    }

    fun setMetric(metric: Boolean) {
        if (state.phase == PerfPhase.RUNNING || metric == state.metric) return
        state = state.copy(metric = metric)
    }

    val armed: Boolean
        get() = state.phase == PerfPhase.STAGING || state.phase == PerfPhase.READY ||
            state.phase == PerfPhase.RUNNING || state.phase == PerfPhase.ABORTED

    fun onFix(tNs: Long, v: Float, accuracyM: Float, alt: Double?, wallMs: Long): PerfResult? {
        if (lastFixNs != 0L) {
            val dt = tNs - lastFixNs
            if (dt in 1..5_000_000_000L) {
                intervals.addLast(dt)
                if (intervals.size > 10) intervals.removeFirst()
            }
        }
        lastFixNs = tNs
        state = state.copy(speedMps = v, haveFix = true, hz = hz())
        val good = accuracyM < MAX_ACCURACY_M

        when (state.phase) {
            PerfPhase.IDLE, PerfPhase.FINISHED -> Unit

            PerfPhase.STAGING, PerfPhase.ABORTED -> {
                if (good && v < STILL) {
                    if (stillSinceNs == 0L) stillSinceNs = tNs
                    lastStill = Sample(tNs, v, alt)
                    if (v < ZERO) lastZero = Sample(tNs, v, alt)
                    if (tNs - stillSinceNs >= STILL_FOR_NS) {
                        pre.clear()
                        state = state.copy(phase = PerfPhase.READY, note = null)
                    }
                } else {
                    stillSinceNs = 0L
                }
            }

            PerfPhase.READY -> {
                if (v < LAUNCH) {
                    when {
                        v < ZERO -> {
                            lastZero = Sample(tNs, v, alt)
                            lastStill = lastZero
                            pre.clear()
                        }
                        pre.isEmpty() || v > pre.last().v -> pre += Sample(tNs, v, alt)
                        else -> pre.clear()
                    }
                    return null
                }
                return launch(tNs, v, alt, wallMs)
            }

            PerfPhase.RUNNING -> return onRunningFix(tNs, v, alt, wallMs)
        }
        return null
    }

    fun onTick(nowNs: Long) {
        val silent = lastFixNs == 0L || nowNs - lastFixNs > GPS_GAP_NS
        when (state.phase) {
            PerfPhase.RUNNING -> if (silent) abort("GPS signal lost")
            PerfPhase.READY -> if (silent) {
                stillSinceNs = 0L
                state = state.copy(phase = PerfPhase.STAGING, haveFix = false)
            }
            PerfPhase.STAGING, PerfPhase.ABORTED -> if (silent && state.haveFix) state = state.copy(haveFix = false)
            else -> Unit
        }
    }

    private fun launch(tNs: Long, v: Float, alt: Double?, wallMs: Long): PerfResult? {
        val floor = launchFloor()
        run.clear()
        if (pre.size >= 2) run.addAll(pre)
        run += Sample(tNs, v, alt)
        pre.clear()
        if (run.size >= 2) {
            t0 = backExtrapolate(run[0], run[1], floor)
            t0Locked = true
        } else {
            t0 = max(floor, tNs - (v / ASSUMED_ACCEL * 1e9f).toLong())
            t0Locked = false
        }
        vMax = run.maxOf { it.v }
        startAlt = lastZero?.alt ?: lastStill?.alt ?: alt
        dist = distanceAt(samples(), tNs)
        state = state.copy(phase = PerfPhase.RUNNING, t0Ns = t0, distanceM = dist, note = null)
        return finishIfReached(wallMs)
    }

    // The launch cannot start before the last stop.
    private fun launchFloor(): Long {
        val z = lastZero?.tNs ?: lastStill?.tNs ?: return Long.MIN_VALUE
        return z - FLOOR_SLACK_NS
    }

    // Find the launch time from the first moving fixes.
    private fun backExtrapolate(a: Sample, b: Sample, floor: Long): Long {
        val acc = (b.v - a.v) / ((b.tNs - a.tNs) / 1e9f)
        val est = a.tNs - (a.v / (if (acc > MIN_ACCEL) acc else ASSUMED_ACCEL) * 1e9f).toLong()
        return est.coerceIn(minOf(floor, a.tNs), a.tNs)
    }

    private fun onRunningFix(tNs: Long, v: Float, alt: Double?, wallMs: Long): PerfResult? {
        val prev = run.last()
        if (tNs <= prev.tNs) return null

        if (v < STILL && tNs - t0 < FALSE_START_NS) {
            resetRun()
            lastStill = Sample(tNs, v, alt)
            if (v < ZERO) lastZero = lastStill
            state = state.copy(phase = PerfPhase.READY, t0Ns = 0L, distanceM = 0.0)
            return null
        }

        if ((v - prev.v) / ((tNs - prev.tNs) / 1e9f) > MAX_ACCEL) {
            abort("GPS jumped, run thrown out")
            return null
        }

        if (!t0Locked) {
            t0 = backExtrapolate(prev, Sample(tNs, v, alt), launchFloor())
            t0Locked = true
        }

        run += Sample(tNs, v, alt)
        dist = distanceAt(samples(), tNs)
        vMax = max(vMax, v)
        state = state.copy(t0Ns = t0, distanceM = dist)

        finishIfReached(wallMs)?.let { return it }

        val elapsed = tNs - t0
        when {
            v < vMax - LIFT_DROP -> abort("Lifted at ${speedText(vMax, state.metric)}")
            elapsed > SLOW_AFTER_NS && v < SLOW_BELOW -> abort("Too slow to time")
            elapsed > timeoutNs(state.mode) -> abort("Took too long")
        }
        return null
    }

    private fun finishIfReached(wallMs: Long): PerfResult? {
        val target = target(state.mode, state.metric)
        val samples = samples()
        val endNs: Long
        var trap: Float? = null
        if (target.speedMps != null) {
            if (run.last().v < target.speedMps) return null
            endNs = crossSpeed(samples, target.speedMps) ?: return null
        } else {
            if (dist < target.distanceM!!) return null
            val c = crossDistance(samples, target.distanceM) ?: return null
            endNs = c.first
            trap = c.second
        }
        val endAlt = run.last().alt
        val result = PerfResult(
            mode = state.mode,
            metric = state.metric,
            at = wallMs,
            ms = (endNs - t0) / 1_000_000,
            trapMps = trap,
            distanceM = distanceAt(samples, endNs),
            splits = splits(samples, endNs),
            hz = hz(),
            altDeltaM = if (endAlt != null && startAlt != null) endAlt - startAlt!! else null,
        )
        resetRun()
        state = state.copy(phase = PerfPhase.FINISHED, result = result, note = null)
        return result
    }

    private fun abort(reason: String) {
        resetRun()
        state = state.copy(phase = PerfPhase.ABORTED, note = reason, t0Ns = 0L, distanceM = 0.0)
    }

    private fun resetRun() {
        run.clear()
        pre.clear()
        lastZero = null
        lastStill = null
        stillSinceNs = 0L
        t0 = 0L
        t0Locked = false
        dist = 0.0
        vMax = 0f
        startAlt = null
    }

    private fun samples(): List<Sample> = listOf(Sample(t0, 0f, startAlt)) + run

    private fun hz(): Float {
        if (intervals.isEmpty()) return 0f
        val sorted = intervals.sorted()
        return (1e9 / sorted[sorted.size / 2]).toFloat()
    }

    private fun splits(samples: List<Sample>, endNs: Long): List<Split> {
        val out = ArrayList<Pair<Long, Split>>()
        for ((label, s) in speedMarks(state.metric)) {
            val t = crossSpeed(samples, s) ?: continue
            if (t <= endNs) out += t to Split(label, (t - t0) / 1_000_000)
        }
        for (m in distanceMarks(state.metric)) {
            val c = crossDistance(samples, m.meters) ?: continue
            if (c.first <= endNs + 1) out += c.first to Split(m.label, (c.first - t0) / 1_000_000, if (m.withSpeed) c.second else null)
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    companion object {
        const val MPH = 0.44704f
        const val KMH = 1f / 3.6f
        const val QUARTER_MILE_M = 402.336

        const val STILL = 0.45f
        const val ZERO = 0.2f
        const val LAUNCH = 0.9f
        const val STILL_FOR_NS = 1_000_000_000L
        const val ASSUMED_ACCEL = 3.0f
        const val MIN_ACCEL = 0.5f
        const val MAX_ACCEL = 15f
        const val LIFT_DROP = 2.2f
        const val SLOW_AFTER_NS = 5_000_000_000L
        const val SLOW_BELOW = 4.5f
        const val GPS_GAP_NS = 3_000_000_000L
        const val FALSE_START_NS = 2_000_000_000L
        const val FLOOR_SLACK_NS = 300_000_000L
        const val MAX_ACCURACY_M = 25f

        data class Target(val speedMps: Float? = null, val distanceM: Double? = null)
        data class Mark(val label: String, val meters: Double, val withSpeed: Boolean)

        fun target(mode: PerfMode, metric: Boolean): Target = if (!metric) {
            when (mode) {
                PerfMode.SHORT -> Target(speedMps = 60 * MPH)
                PerfMode.LONG -> Target(speedMps = 100 * MPH)
                PerfMode.DRAG -> Target(distanceM = QUARTER_MILE_M)
            }
        } else {
            when (mode) {
                PerfMode.SHORT -> Target(speedMps = 100 * KMH)
                PerfMode.LONG -> Target(speedMps = 160 * KMH)
                PerfMode.DRAG -> Target(distanceM = 400.0)
            }
        }

        fun label(mode: PerfMode, metric: Boolean): String = when (mode) {
            PerfMode.SHORT -> if (metric) "0–100" else "0–60"
            PerfMode.LONG -> if (metric) "0–160" else "0–100"
            PerfMode.DRAG -> if (metric) "400 m" else "¼ mile"
        }

        fun speedMarks(metric: Boolean): List<Pair<String, Float>> = if (!metric) {
            listOf("0–30 mph" to 30 * MPH, "0–60 mph" to 60 * MPH, "0–100 mph" to 100 * MPH)
        } else {
            listOf("0–50 km/h" to 50 * KMH, "0–100 km/h" to 100 * KMH, "0–160 km/h" to 160 * KMH)
        }

        fun distanceMarks(metric: Boolean): List<Mark> = if (!metric) {
            listOf(
                Mark("60 ft", 18.288, false), Mark("330 ft", 100.584, false), Mark("⅛ mile", 201.168, true),
                Mark("1000 ft", 304.8, false), Mark("¼ mile", QUARTER_MILE_M, true),
            )
        } else {
            listOf(Mark("100 m", 100.0, false), Mark("200 m", 200.0, true), Mark("400 m", 400.0, true))
        }

        fun timeoutNs(mode: PerfMode): Long = when (mode) {
            PerfMode.SHORT -> 40_000_000_000L
            PerfMode.LONG -> 90_000_000_000L
            PerfMode.DRAG -> 60_000_000_000L
        }

        fun speedText(mps: Float, metric: Boolean): String =
            if (metric) String.format(Locale.US, "%d km/h", (mps * 3.6f).toInt())
            else String.format(Locale.US, "%d mph", (mps / MPH).toInt())

        fun crossSpeed(samples: List<Sample>, s: Float): Long? {
            for (i in 0 until samples.size - 1) {
                val a = samples[i]
                val b = samples[i + 1]
                if (a.v < s && b.v >= s) {
                    val f = (s - a.v) / (b.v - a.v)
                    return a.tNs + ((b.tNs - a.tNs) * f.toDouble()).toLong()
                }
            }
            return null
        }

        fun crossDistance(samples: List<Sample>, x: Double): Pair<Long, Float>? {
            var d = 0.0
            for (i in 0 until samples.size - 1) {
                val a = samples[i]
                val b = samples[i + 1]
                val dt = (b.tNs - a.tNs) / 1e9
                if (dt <= 0) continue
                val seg = (a.v + b.v) / 2.0 * dt
                if (d + seg >= x) {
                    val rem = x - d
                    val acc = (b.v - a.v) / dt
                    val tau = if (abs(acc) < 1e-6) {
                        if (a.v > 0) rem / a.v else dt
                    } else {
                        val disc = a.v.toDouble() * a.v + 2 * acc * rem
                        ((-a.v + sqrt(max(0.0, disc))) / acc)
                    }.coerceIn(0.0, dt)
                    return (a.tNs + (tau * 1e9).toLong()) to (a.v + acc * tau).toFloat()
                }
                d += seg
            }
            return null
        }

        fun distanceAt(samples: List<Sample>, tNs: Long): Double {
            var d = 0.0
            for (i in 0 until samples.size - 1) {
                val a = samples[i]
                val b = samples[i + 1]
                if (a.tNs >= tNs) break
                val dt = (b.tNs - a.tNs) / 1e9
                if (dt <= 0) continue
                val acc = (b.v - a.v) / dt
                val tau = minOf(dt, (tNs - a.tNs) / 1e9)
                d += a.v * tau + acc * tau * tau / 2
            }
            return d
        }
    }
}
