package com.oemplus.roadster.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.oemplus.roadster.core.AnalyzerInput
import com.oemplus.roadster.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class AnalyzerStatus {
    OFF,
    LIVE,
    SIMULATED,
    NEEDS_PERMISSION,
    UNAVAILABLE,
}

class Analyzer(
    private val ctx: Context,
    private val media: MediaHub,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) : MtcBluetooth.AudioProbe {
    companion object {
        const val BANDS = 32
        const val WAVE = 256
        const val LOW_HZ = 45f
        const val HIGH_HZ = 16000f
        private const val DB_FLOOR = 13f
        private const val DB_CEIL = 51f
    }

    private val _status = MutableStateFlow(AnalyzerStatus.OFF)
    val status: StateFlow<AnalyzerStatus> = _status.asStateFlow()

    private val _audible = MutableStateFlow(false)
    val audible: StateFlow<Boolean> = _audible.asStateFlow()

    private val lock = Any()
    private val bands = FloatArray(BANDS)
    private val wave = FloatArray(WAVE)
    private val edges = FloatArray(BANDS + 1) { i -> LOW_HZ * (HIGH_HZ / LOW_HZ).pow(i / BANDS.toFloat()) }

    private var users = 0
    private var job: Job? = null
    @Volatile private var viz: Visualizer? = null
    private val rnd = Random(7)

    override fun hold() = acquire()

    override fun hears(): Boolean? {
        if (viz == null) return null
        val since = heardSince
        return since != 0L && SystemClock.elapsedRealtime() - since >= 1_000L
    }

    @Volatile private var heardSince = 0L

    fun bandCenter(i: Int): Float = sqrt(edges[i] * edges[i + 1])

    @Synchronized
    fun acquire() {
        users++
        if (users == 1) start()
    }

    @Synchronized
    override fun release() {
        users = max(0, users - 1)
        if (users == 0) stop()
    }

    @Synchronized
    fun restart() {
        if (users > 0) {
            stop()
            start()
        }
    }

    fun read(outBands: FloatArray, outWave: FloatArray?) {
        synchronized(lock) {
            bands.copyInto(outBands, 0, 0, min(outBands.size, BANDS))
            outWave?.let { wave.copyInto(it, 0, 0, min(it.size, WAVE)) }
        }
    }

    private fun start() {
        job = scope.launch(Dispatchers.Default) { loop() }
    }

    private fun stop() {
        job?.cancel()
        job = null
        closeViz()
        synchronized(lock) { bands.fill(0f); wave.fill(0f) }
        _status.value = AnalyzerStatus.OFF
        _audible.value = false
        heardSince = 0L
    }

    private suspend fun loop() {
        val wantSim = prefs.current.analyzerInput == AnalyzerInput.SIMULATED
        val mic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val v = if (!wantSim && mic) openViz() else null
        val fallback = when {
            wantSim -> AnalyzerStatus.SIMULATED
            !mic -> AnalyzerStatus.NEEDS_PERMISSION
            else -> AnalyzerStatus.UNAVAILABLE
        }
        _status.value = if (v != null) AnalyzerStatus.LIVE else fallback

        val size = v?.captureSize ?: 1024
        val fft = ByteArray(size)
        val wf = ByteArray(size)
        val rate = (v?.samplingRate ?: 44_100_000) / 1000f
        val tmpBands = FloatArray(BANDS)
        val tmpWave = FloatArray(WAVE)
        var silentSince = 0L
        var heardAt = 0L
        val t0 = SystemClock.elapsedRealtime()

        while (currentCoroutineContext().isActive) {
            val now = SystemClock.elapsedRealtime()
            val playing = media.current.value?.playing == true
            var live = false
            if (v != null) {
                try {
                    if (v.getFft(fft) == Visualizer.SUCCESS && v.getWaveForm(wf) == Visualizer.SUCCESS) {
                        val energy = computeBands(fft, rate, tmpBands)
                        computeWave(wf, tmpWave)
                        if (energy > 0.02f) {
                            silentSince = 0L
                            heardAt = now
                            if (heardSince == 0L) heardSince = now
                            live = true
                        } else if (playing) {
                            if (silentSince == 0L) silentSince = now
                            live = now - silentSince < 5000
                        } else {
                            silentSince = 0L
                            live = true
                        }
                    }
                } catch (_: Throwable) {
                }
                _status.value = if (live) AnalyzerStatus.LIVE else AnalyzerStatus.UNAVAILABLE
            }
            if (live) {
                synchronized(lock) {
                    tmpBands.copyInto(bands)
                    tmpWave.copyInto(wave)
                }
            } else {
                simulate((now - t0) / 1000.0, playing)
            }
            val heard = heardAt != 0L && now - heardAt < 1500
            if (heardSince != 0L && now - heardAt > 400) heardSince = 0L
            if (_audible.value != heard) _audible.value = heard
            delay(if (playing || heard) 28L else 150L)
        }
    }

    // Output mix. Android gives it only with RECORD_AUDIO.
    private fun openViz(): Visualizer? = try {
        val v = Visualizer(0)
        v.enabled = false
        val range = Visualizer.getCaptureSizeRange()
        v.captureSize = 1024.coerceIn(range[0], range[1])
        try { v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED } catch (_: Throwable) {}
        v.enabled = true
        viz = v
        v
    } catch (_: Throwable) {
        viz = null
        null
    }

    private fun closeViz() {
        try { viz?.enabled = false } catch (_: Throwable) {}
        try { viz?.release() } catch (_: Throwable) {}
        viz = null
    }

    private fun computeBands(fft: ByteArray, sampleRate: Float, out: FloatArray): Float {
        val n = fft.size
        val half = n / 2
        val binHz = sampleRate / n
        var sum = 0f
        for (b in 0 until BANDS) {
            val k0 = (edges[b] / binHz).toInt().coerceIn(1, half - 1)
            val k1 = (edges[b + 1] / binHz).toInt().coerceIn(k0, half - 1)
            var peak = 0f
            for (k in k0..k1) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                val m = sqrt(re * re + im * im)
                if (m > peak) peak = m
            }
            val db = 20f * log10(peak + 1e-3f)
            val tilt = 3.2f * (ln(max(edges[b], 80f) / 120f) / ln(2f))
            val lvl = ((db + tilt - DB_FLOOR) / (DB_CEIL - DB_FLOOR)).coerceIn(0f, 1f)
            out[b] = lvl
            sum += lvl
        }
        return sum / BANDS
    }

    private fun computeWave(wf: ByteArray, out: FloatArray) {
        val step = wf.size / WAVE.toFloat()
        for (i in 0 until WAVE) {
            val v = wf[(i * step).toInt().coerceAtMost(wf.size - 1)].toInt() and 0xFF
            out[i] = (v - 128) / 128f
        }
    }

    private fun simulate(t: Double, playing: Boolean) {
        synchronized(lock) {
            if (!playing) {
                bands.fill(0f)
                wave.fill(0f)
                return
            }
            val beat = t * 2.0
            val kick = exp(-(beat - floor(beat)) * 6.0).toFloat()
            val half = beat * 2.0
            val hat = exp(-(half - floor(half)) * 12.0).toFloat()
            for (i in 0 until BANDS) {
                val x = i / (BANDS - 1f)
                val base = 0.58f - 0.30f * x
                val wob = 0.16f * sin(t * (1.1 + i * 0.29) + i * 1.9).toFloat()
                val noise = rnd.nextFloat() * 0.14f
                val k = if (i < 7) kick * (0.42f - i * 0.05f) else 0f
                val h = if (i > 22) hat * 0.22f else 0f
                bands[i] = ((base + wob + noise + k + h) * 0.9f).coerceIn(0f, 1f)
            }
            for (j in 0 until WAVE) {
                val ph = j / WAVE.toDouble()
                val s = 0.45 * sin(2 * PI * 3 * ph + t * 7) + 0.22 * sin(2 * PI * 13 * ph + t * 19) * kick +
                    (rnd.nextFloat() - 0.5f) * 0.06
                wave[j] = (s * (0.55 + 0.45 * kick)).toFloat()
            }
        }
    }
}
