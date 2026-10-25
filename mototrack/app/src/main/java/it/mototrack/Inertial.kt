package it.mototrack

import kotlin.math.*

const val STANDARD_GRAVITY = 9.80665
internal fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
internal fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
    a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
internal fun unit(v: DoubleArray): DoubleArray? {
    if (v.size != 3 || v.any { !it.isFinite() }) return null
    val norm = sqrt(dot(v, v))
    return if (norm < 1e-8) null else DoubleArray(3) { v[it] / norm }
}

// Two stationary poses define motorcycle axes in device coordinates, regardless
// of how the phone is mounted. The second pose MUST be a lean to the rider's right.
class MountCalibration {
    var up: DoubleArray? = null
        private set
    var forward: DoubleArray? = null
        private set
    private var right: DoubleArray? = null
    val ready get() = forward != null
    fun zero(gravityDirection: DoubleArray): Boolean {
        up = unit(gravityDirection); forward = null; right = null
        return up != null
    }
    fun confirmRight(gravityDirection: DoubleArray): Boolean {
        val upright = up ?: return false
        val tilted = unit(gravityDirection) ?: return false
        val degrees = Math.toDegrees(acos(dot(upright, tilted).coerceIn(-1.0, 1.0)))
        if (degrees !in 5.0..30.0) return false
        val f = unit(cross(tilted, upright)) ?: return false
        forward = f
        right = unit(cross(f, upright))
        return right != null
    }
    fun relevel(gravityDirection: DoubleArray): Boolean {
        val newUp = unit(gravityDirection) ?: return false
        val oldForward = forward ?: return false
        val projection = dot(oldForward, newUp)
        val f = unit(DoubleArray(3) { oldForward[it] - projection * newUp[it] }) ?: return false
        up = newUp; forward = f; right = unit(cross(f, newUp))
        return right != null
    }
    fun lean(gravityDirection: DoubleArray): Double? {
        val upright = up ?: return null
        val r = right ?: return null
        val g = unit(gravityDirection) ?: return null
        val lateral = dot(g, r); val vertical = dot(g, upright)
        if (hypot(lateral, vertical) < 0.1) return null
        // Positive to the right. Pitch is removed by projection onto the roll plane.
        return Math.toDegrees(atan2(-lateral, vertical))
    }
}

class InertialFilter {
    var axis: DoubleArray? = null
        private set
    private var previousTime = 0L
    private var filtered = 0.0
    fun calibrate(forward: DoubleArray): Boolean {
        axis = unit(forward); reset()
        return axis != null
    }
    fun clear() { axis = null; reset() }
    fun reset() { previousTime = 0; filtered = 0.0 }
    fun sample(linear: DoubleArray, nanos: Long): Double? {
        val direction = axis ?: return null
        if (linear.size != 3 || linear.any { !it.isFinite() } || nanos <= previousTime) return null
        val value = dot(linear, direction) / STANDARD_GRAVITY
        val dt = (nanos - previousTime) / 1e9
        filtered = if (previousTime == 0L || dt > 1) value
            else filtered + (1 - exp(-dt / 0.15)) * (value - filtered)
        previousTime = nanos
        return filtered
    }
}
data class InertialSample(val time: Long, val g: Double, val segment: Int)
