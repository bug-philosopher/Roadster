package com.oemplus.roadster.location

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.max
import kotlin.random.Random

class TopSpeedGateTest {
    private fun pullAndHold(t: Double): Double = when {
        t < 20.0 -> 2.5 * t
        t < 23.0 -> 50.0
        else -> max(0.0, 50.0 - 3.0 * (t - 23.0))
    }

    private fun spikePeak(t: Double): Double = if (t < 20.0) 2.5 * t else max(0.0, 50.0 - 3.0 * (t - 20.0))

    private fun cruise(@Suppress("UNUSED_PARAMETER") t: Double): Double = 30.0

    private fun record(
        seconds: Double,
        period: Double,
        speed: (Double) -> Double,
        reported: (Int, Double) -> Double = { _, v -> v },
        accuracy: (Int) -> Float = { 6f },
        jitterM: Double = 0.0,
        jitterS: Double = 0.0,
        seed: Int = 1,
    ): Float {
        val rnd = Random(seed)
        val gate = TopSpeedGate()
        var best = 0f
        var prevErr = 0.0
        val n = (seconds / period).toInt()
        for (i in 0..n) {
            val t = i * period
            val err = if (jitterM > 0.0) rnd.nextDouble(-jitterM, jitterM) else 0.0
            val stamp = t + if (jitterS > 0.0) rnd.nextDouble(-jitterS, jitterS) else 0.0
            val dist = if (i == 0) 0.0 else max(0.0, ground(speed, t - period, t) + err - prevErr)
            prevErr = err
            val r = TopSpeedGate.Reading(BASE + (stamp * 1e9).toLong(), reported(i, speed(t)).toFloat(), accuracy(i))
            gate.push(r, dist)?.let { best = maxOf(best, it) }
        }
        return best
    }

    private fun ground(speed: (Double) -> Double, from: Double, to: Double): Double {
        val steps = 200
        val h = (to - from) / steps
        return (0 until steps).sumOf { speed(from + (it + 0.5) * h) * h }
    }

    @Test
    fun takesATopSpeedHeldForAMoment() {
        assertEquals(50f, record(40.0, 1.0, ::pullAndHold), 0.01f)
    }

    @Test
    fun anInstantPeakCountsWhatWasHeldThroughIt() {
        assertEquals(47.5f, record(40.0, 1.0, ::spikePeak), 0.01f)
    }

    @Test
    fun takesThePeakAtTenHz() {
        val noise = Random(7)
        assertEquals(50f, record(40.0, 0.1, ::spikePeak, reported = { _, v -> v + noise.nextDouble(-0.3, 0.3) }), 0.6f)
    }

    @Test
    fun survivesOrdinaryNoise() {
        for (seed in 1..20) {
            val noise = Random(seed)
            val best = record(
                40.0, 1.0, ::pullAndHold,
                reported = { _, v -> v + noise.nextDouble(-0.4, 0.4) },
                jitterM = 1.0, jitterS = 0.04, seed = seed,
            )
            assertEquals("seed $seed", 50f, best, 0.8f)
        }
    }

    @Test
    fun ignoresAOneReadingSpikeOfAnySize() {
        for (spike in listOf(33.0, 40.0, 75.0)) {
            assertEquals("spike $spike", 30f, record(30.0, 1.0, ::cruise, reported = { i, v -> if (i == 12) spike else v }), 0.01f)
        }
    }

    @Test
    fun ignoresATwoReadingSpike() {
        for (spike in listOf(36.0, 40.0, 75.0)) {
            val best = record(30.0, 1.0, ::cruise, reported = { i, v -> if (i == 12 || i == 13) spike else v })
            assertEquals("spike $spike", 30f, best, 0.01f)
        }
    }

    @Test
    fun ignoresASpeedTheGroundCoveredDoesNotBackUp() {
        assertEquals(30f, record(40.0, 1.0, ::cruise, reported = { i, v -> if (i in 10..19) 55.0 else v }), 0.01f)
    }

    @Test
    fun ignoresAPositionJump() {
        val gate = TopSpeedGate()
        var best = 0f
        val speeds = listOf(30f, 30f, 30f, 40f, 30f, 30f)
        val dists = listOf(0.0, 30.0, 30.0, 110.0, 30.0, 30.0)
        speeds.forEachIndexed { i, v ->
            gate.push(TopSpeedGate.Reading(BASE + i * 1_000_000_000L, v, 6f), dists[i])?.let { best = maxOf(best, it) }
        }
        assertEquals(30f, best, 0.01f)
    }

    @Test
    fun ignoresInaccurateFixes() {
        assertEquals(37.5f, record(40.0, 1.0, ::pullAndHold, accuracy = { if (it in 17..26) 35f else 6f }), 0.01f)
    }

    @Test
    fun needsFixesCloseTogether() {
        assertEquals(0f, record(60.0, 5.0, ::cruise), 0.0f)
    }

    private companion object {
        const val BASE = 50_000_000_000L
    }
}
