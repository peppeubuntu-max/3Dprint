package it.mototrack

import kotlin.math.exp
import kotlin.math.sqrt

const val STANDARD_GRAVITY = 9.80665

// Device axes: +Z exits the screen toward the rider, so forward is -Z.
// Calibration removes the mounting pitch using the stationary up vector.
fun forwardAxis(up: DoubleArray): DoubleArray? {
    val length = sqrt(up.sumOf { it * it })
    if (length < 0.001) return null
    val u = up.map { it / length }
    val axis = doubleArrayOf(u[0] * u[2], u[1] * u[2], -1 + u[2] * u[2])
    val norm = sqrt(axis.sumOf { it * it })
    if (norm < 0.5) return null
    return DoubleArray(3) { axis[it] / norm }
}
class InertialFilter {
    var axis: DoubleArray? = null
        private set
    private var previousTime = 0L
    private var filtered = 0.0
    fun calibrate(up: DoubleArray): Boolean {
        axis = forwardAxis(up)
        reset()
        return axis != null
    }
    fun reset() { previousTime = 0; filtered = 0.0 }
    fun sample(linear: DoubleArray, nanos: Long): Double? {
        val direction = axis ?: return null
        if (linear.any { !it.isFinite() } || nanos <= previousTime) return null
        val value = linear.indices.sumOf { linear[it] * direction[it] } / STANDARD_GRAVITY
        val dt = (nanos - previousTime) / 1e9
        filtered = if (previousTime == 0L || dt > 1) value
            else filtered + (1 - exp(-dt / 0.15)) * (value - filtered)
        previousTime = nanos
        return filtered
    }
}
data class InertialSample(val time: Long, val g: Double, val segment: Int)
