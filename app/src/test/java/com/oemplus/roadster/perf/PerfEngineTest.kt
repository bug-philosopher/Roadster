package com.oemplus.roadster.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

class PerfEngineTest {
    private class Sim(val t: DoubleArray, val v: DoubleArray, val x: DoubleArray) {
        private val dt = t[1] - t[0]
        fun vAt(time: Double): Double = if (time <= 0) 0.0 else v[minOf(v.size - 1, (time / dt).toInt())]
        fun timeToSpeed(s: Double): Double = t[v.indexOfFirst { it >= s }]
        fun timeToDistance(d: Double): Double = t[x.indexOfFirst { it >= d }]
        fun speedAtDistance(d: Double): Double = v[x.indexOfFirst { it >= d }]
    }

    private fun simulate(): Sim {
        val dt = 0.0005
        val n = (70.0 / dt).toInt()
        val t = DoubleArray(n)
        val v = DoubleArray(n)
        val x = DoubleArray(n)
        val shifts = doubleArrayOf(13.4, 24.6, 33.5, 42.0)
        var next = 0
        var shiftUntil = -1.0
        var vel = 0.0
        var pos = 0.0
        for (i in 0 until n) {
            val time = i * dt
            t[i] = time
            v[i] = vel
            x[i] = pos
            if (next < shifts.size && vel >= shifts[next] && time >= shiftUntil) {
                shiftUntil = time + 0.35
                next++
            }
            val a = if (time < shiftUntil) -0.25 else {
                val base = max(0.45, 4.4 - 0.095 * vel)
                if (time < 0.35) base * (0.45 + 0.55 * time / 0.35) else base
            }
            vel = max(0.0, vel + a * dt)
            pos += vel * dt
        }
        return Sim(t, v, x)
    }

    private val sim = simulate()
    private val base = 100_000_000_000L

    private fun timeRun(mode: PerfMode, period: Double, offset: Double, noise: Float = 0.05f, seed: Int = 1): PerfResult {
        val e = PerfEngine(metric = false, mode = mode)
        e.stage()
        val rnd = Random(seed)
        var k = -(kotlin.math.ceil(1.5 / period).toInt() + 1)
        while (k < 4000) {
            val time = offset + k * period - period
            val v = sim.vAt(time).toFloat() + if (time > 0) (rnd.nextFloat() - 0.5f) * 2 * noise else rnd.nextFloat() * 0.1f
            val r = e.onFix(base + (time * 1e9).toLong(), max(0f, v), 5f, 600.0 + max(0.0, time) * 0.1, 0L)
            if (r != null) return r
            k++
        }
        error("run never finished")
    }

    private fun report(name: String, got: Double, want: Double) =
        println(String.format(Locale.US, "%-34s got %7.3f  want %7.3f  error %+.3f", name, got, want, got - want))

    @Test
    fun zeroToSixty_oneHertz() {
        val want = sim.timeToSpeed(60 * 0.44704)
        for ((i, off) in listOf(0.05, 0.37, 0.71, 0.95).withIndex()) {
            val r = timeRun(PerfMode.SHORT, 1.0, off, seed = i)
            report("0-60 @1 Hz, launch +${off}s", r.ms / 1000.0, want)
            assertEquals(want, r.ms / 1000.0, 0.25)
        }
    }

    @Test
    fun zeroToSixty_tenHertz() {
        val want = sim.timeToSpeed(60 * 0.44704)
        val r = timeRun(PerfMode.SHORT, 0.1, 0.037)
        report("0-60 @10 Hz", r.ms / 1000.0, want)
        assertEquals(want, r.ms / 1000.0, 0.06)
    }

    @Test
    fun zeroToHundred_oneHertz() {
        val want = sim.timeToSpeed(100 * 0.44704)
        val r = timeRun(PerfMode.LONG, 1.0, 0.5)
        report("0-100 @1 Hz", r.ms / 1000.0, want)
        assertEquals(want, r.ms / 1000.0, 0.3)
        assertTrue(r.splits.any { it.label == "0–60 mph" })
    }

    @Test
    fun quarterMile_oneAndTenHertz() {
        val wantEt = sim.timeToDistance(402.336)
        val wantTrap = sim.speedAtDistance(402.336)
        for ((period, tolEt, tolTrap) in listOf(Triple(1.0, 0.25, 0.8), Triple(0.1, 0.06, 0.3))) {
            val r = timeRun(PerfMode.DRAG, period, period * 0.43)
            report("1/4 mile ET @${(1 / period).toInt()} Hz", r.ms / 1000.0, wantEt)
            report("1/4 mile trap mph @${(1 / period).toInt()} Hz", r.trapMps!! / 0.44704, wantTrap / 0.44704)
            assertEquals(wantEt, r.ms / 1000.0, tolEt)
            assertEquals(wantTrap, r.trapMps!!.toDouble(), tolTrap)
            val eighth = r.splits.first { it.label == "⅛ mile" }
            report("1/8 mile ET @${(1 / period).toInt()} Hz", eighth.ms / 1000.0, sim.timeToDistance(201.168))
            assertEquals(sim.timeToDistance(201.168), eighth.ms / 1000.0, tolEt)
        }
    }

    @Test
    fun lateZeroReading_doesNotStartTheClockLate() {
        val want = sim.timeToSpeed(60 * 0.44704)
        val e = PerfEngine(mode = PerfMode.SHORT)
        e.stage()
        val launch = 3.841
        var result: PerfResult? = null
        for (t in listOf(1.012, 2.011, 3.012, 4.016)) e.onFix(base + (t * 1e9).toLong(), 0f, 5f, null, 0L)
        var t = 4.441
        while (result == null && t < 60) {
            result = e.onFix(base + (t * 1e9).toLong(), sim.vAt(t - launch).toFloat(), 5f, null, 0L)
            t += 1.0
        }
        assertNotNull(result)
        report("0-60 @1 Hz, zero reported after launch", result!!.ms / 1000.0, want)
        assertEquals(want, result.ms / 1000.0, 0.1)
    }

    @Test
    fun staging_waitsForAStop_thenReady() {
        val e = PerfEngine()
        e.stage()
        assertEquals(PerfPhase.STAGING, e.state.phase)
        e.onFix(base, 12f, 5f, null, 0L)
        e.onFix(base + 1_000_000_000L, 0.1f, 5f, null, 0L)
        assertEquals(PerfPhase.STAGING, e.state.phase)
        e.onFix(base + 2_000_000_000L, 0.2f, 5f, null, 0L)
        assertEquals(PerfPhase.READY, e.state.phase)
    }

    @Test
    fun noiseSpike_isNotALaunch() {
        val e = PerfEngine()
        e.stage()
        e.onFix(base, 0f, 5f, null, 0L)
        e.onFix(base + 1_000_000_000L, 0f, 5f, null, 0L)
        assertEquals(PerfPhase.READY, e.state.phase)
        e.onFix(base + 2_000_000_000L, 1.0f, 5f, null, 0L)
        assertEquals(PerfPhase.RUNNING, e.state.phase)
        e.onFix(base + 3_000_000_000L, 0.1f, 5f, null, 0L)
        assertEquals(PerfPhase.READY, e.state.phase)
    }

    @Test
    fun liftingAborts_andRestages() {
        val e = PerfEngine()
        e.stage()
        e.onFix(base, 0f, 5f, null, 0L)
        e.onFix(base + 1_000_000_000L, 0f, 5f, null, 0L)
        var t = base + 1_000_000_000L
        for (v in listOf(4f, 8f, 12f, 15f, 11f)) {
            t += 1_000_000_000L
            e.onFix(t, v, 5f, null, 0L)
        }
        assertEquals(PerfPhase.ABORTED, e.state.phase)
        assertNotNull(e.state.note)
        assertTrue(e.state.note!!.startsWith("Lifted"))
        assertTrue(e.armed)
        t += 1_000_000_000L; e.onFix(t, 0f, 5f, null, 0L)
        t += 1_000_000_000L; e.onFix(t, 0f, 5f, null, 0L)
        assertEquals(PerfPhase.READY, e.state.phase)
    }

    @Test
    fun gpsDropout_abortsTheRun() {
        val e = PerfEngine()
        e.stage()
        e.onFix(base, 0f, 5f, null, 0L)
        e.onFix(base + 1_000_000_000L, 0f, 5f, null, 0L)
        e.onFix(base + 2_000_000_000L, 4f, 5f, null, 0L)
        e.onTick(base + 5_500_000_000L)
        assertEquals(PerfPhase.ABORTED, e.state.phase)
        assertEquals("GPS signal lost", e.state.note)
    }

    @Test
    fun impossibleJump_isThrownOut() {
        val e = PerfEngine()
        e.stage()
        e.onFix(base, 0f, 5f, null, 0L)
        e.onFix(base + 1_000_000_000L, 0f, 5f, null, 0L)
        e.onFix(base + 2_000_000_000L, 4f, 5f, null, 0L)
        e.onFix(base + 3_000_000_000L, 30f, 5f, null, 0L)
        assertEquals(PerfPhase.ABORTED, e.state.phase)
        assertEquals("GPS jumped, run thrown out", e.state.note)
    }

    @Test
    fun splitsArriveInOrder() {
        val r = timeRun(PerfMode.DRAG, 1.0, 0.5)
        val times = r.splits.map { it.ms }
        assertEquals(times.sorted(), times)
        assertTrue(abs(r.distanceM - 402.336) < 1.0)
    }
}
