package com.oemplus.roadster.location

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TopSpeedGate {
    data class Reading(val tNs: Long, val mps: Float, val accuracyM: Float)

    private var prev: Reading? = null

    fun push(r: Reading, distM: Double): Float? {
        val p = prev
        prev = r
        return if (p == null) null else held(p, r, distM)
    }

    fun reset() {
        prev = null
    }

    companion object {
        const val MAX_GAP_S = 3.0
        const val MAX_ACCURACY_M = 20f
        const val MAX_ACCEL = 12f
        const val MAX_MPS = 90f

        fun held(a: Reading, b: Reading, distM: Double): Float? {
            val dt = (b.tNs - a.tNs) / 1e9
            if (dt <= 0.0 || dt > MAX_GAP_S) return null
            if (a.accuracyM >= MAX_ACCURACY_M || b.accuracyM >= MAX_ACCURACY_M) return null
            if (a.mps >= MAX_MPS || b.mps >= MAX_MPS) return null
            if (abs(b.mps - a.mps) / dt > MAX_ACCEL) return null
            // Speed must show on two fixes.
            val held = min(a.mps, b.mps)
            // Distance must agree with speed.
            if (dt >= 0.7) {
                val ground = distM / dt
                if (held > ground + max(3.0, 0.08 * held)) return null
                val avg = (a.mps + b.mps) / 2.0
                if (ground > avg + max(4.0, 0.25 * avg)) return null
            }
            return held
        }
    }
}
