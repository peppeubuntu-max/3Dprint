package it.mototrack

import kotlin.math.abs

enum class TraceMetric(val label: String) {
    SPEED("Velocità"), LEAN("Piega"), ACCELERATION("Accel.")
}

// Fixed scales make colors comparable across rides. Missing data stays gray.
// Red: 120 km/h, 45 degrees of lean, or 1 G of acceleration/braking.
fun traceHue(point: Sample, metric: TraceMetric): Float? {
    val intensity = when (metric) {
        TraceMetric.SPEED -> point.speed?.div(120.0 / 3.6)
        TraceMetric.LEAN -> point.lean?.let { abs(it) / 45.0 }
        TraceMetric.ACCELERATION -> point.acceleration?.let { abs(it) / STANDARD_GRAVITY }
    } ?: return null
    if (!intensity.isFinite()) return null
    return (120.0 * (1.0 - intensity.coerceIn(0.0, 1.0))).toFloat()
}
