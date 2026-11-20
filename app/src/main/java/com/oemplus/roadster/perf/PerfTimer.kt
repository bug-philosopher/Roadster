package com.oemplus.roadster.perf

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.oemplus.roadster.BuildConfig
import com.oemplus.roadster.core.Prefs
import com.oemplus.roadster.core.SpeedUnit
import com.oemplus.roadster.core.enumOr
import com.oemplus.roadster.location.Fix
import com.oemplus.roadster.location.GpsHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class PerfTimer(ctx: Context, private val gps: GpsHub, prefs: Prefs, scope: CoroutineScope) {
    private val sp = ctx.getSharedPreferences("perf", Context.MODE_PRIVATE)
    private val engine = PerfEngine(
        metric = prefs.current.speedUnit == SpeedUnit.KMH,
        mode = enumOr(sp.getString("mode", null), PerfMode.SHORT),
    )

    private val _state = MutableStateFlow(engine.state)
    val state: StateFlow<PerfState> = _state.asStateFlow()

    private val _last = MutableStateFlow(read("last"))
    val last: StateFlow<PerfResult?> = _last.asStateFlow()

    private val _bests = MutableStateFlow(loadBests())
    val bests: StateFlow<Map<String, PerfResult>> = _bests.asStateFlow()

    private var armed = false

    init {
        scope.launch { gps.fixes.collect { onFix(it) } }
        scope.launch {
            prefs.settings.map { it.speedUnit }.distinctUntilChanged().collect {
                engine.setMetric(it == SpeedUnit.KMH)
                publish()
            }
        }
        scope.launch {
            while (true) {
                delay(500)
                if (armed) {
                    engine.onTick(SystemClock.elapsedRealtimeNanos())
                    publish()
                }
            }
        }
    }

    fun stage() {
        engine.stage()
        setArmed(true)
        publish()
    }

    fun cancel() {
        engine.cancel()
        setArmed(false)
        publish()
    }

    fun setMode(mode: PerfMode) {
        engine.setMode(mode)
        sp.edit().putString("mode", mode.name).apply()
        publish()
    }

    fun resetBests(metric: Boolean) {
        val e = sp.edit()
        PerfMode.entries.forEach { e.remove("best_" + key(it, metric)) }
        e.apply()
        _bests.value = loadBests()
    }

    private fun onFix(f: Fix) {
        if (BuildConfig.DEBUG && armed) {
            Log.d("PerfFix", "t=${f.elapsedNs} v=${f.speedMps} acc=${f.accuracyM} phase=${engine.state.phase}")
        }
        val result = engine.onFix(f.elapsedNs, f.speedMps, f.accuracyM, f.altM, System.currentTimeMillis())
        if (result != null) {
            record(result)
            setArmed(false)
        }
        publish()
    }

    private fun record(r: PerfResult) {
        write("last", r)
        _last.value = r
        val k = key(r.mode, r.metric)
        val best = _bests.value[k]
        if (best == null || r.ms < best.ms) {
            write("best_$k", r)
            _bests.value = _bests.value + (k to r)
        }
    }

    private fun setArmed(on: Boolean) {
        if (armed == on) return
        armed = on
        if (on) {
            gps.setFast(TAG, true)
            gps.acquire(TAG)
        } else {
            gps.setFast(TAG, false)
            gps.release(TAG)
        }
    }

    private fun publish() {
        _state.value = engine.state
    }

    private fun loadBests(): Map<String, PerfResult> {
        val m = HashMap<String, PerfResult>()
        for (metric in listOf(false, true)) for (mode in PerfMode.entries) {
            val k = key(mode, metric)
            read("best_$k")?.let { m[k] = it }
        }
        return m
    }

    private fun write(name: String, r: PerfResult) {
        val splits = JSONArray()
        r.splits.forEach { s ->
            splits.put(JSONObject().put("l", s.label).put("ms", s.ms).apply { s.speedMps?.let { put("v", it.toDouble()) } })
        }
        val o = JSONObject()
            .put("mode", r.mode.name).put("metric", r.metric).put("at", r.at).put("ms", r.ms)
            .put("d", r.distanceM).put("hz", r.hz.toDouble()).put("splits", splits)
        r.trapMps?.let { o.put("trap", it.toDouble()) }
        r.altDeltaM?.let { o.put("alt", it) }
        sp.edit().putString(name, o.toString()).apply()
    }

    private fun read(name: String): PerfResult? = try {
        sp.getString(name, null)?.let { s ->
            val o = JSONObject(s)
            val arr = o.getJSONArray("splits")
            PerfResult(
                mode = enumOr(o.getString("mode"), PerfMode.SHORT),
                metric = o.getBoolean("metric"),
                at = o.getLong("at"),
                ms = o.getLong("ms"),
                trapMps = if (o.has("trap")) o.getDouble("trap").toFloat() else null,
                distanceM = o.optDouble("d", 0.0),
                splits = (0 until arr.length()).map { i ->
                    val so = arr.getJSONObject(i)
                    Split(so.getString("l"), so.getLong("ms"), if (so.has("v")) so.getDouble("v").toFloat() else null)
                },
                hz = o.optDouble("hz", 0.0).toFloat(),
                altDeltaM = if (o.has("alt")) o.getDouble("alt") else null,
            )
        }
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val TAG = "perf"
        fun key(mode: PerfMode, metric: Boolean) = mode.name + if (metric) "_km" else "_mi"
    }
}
