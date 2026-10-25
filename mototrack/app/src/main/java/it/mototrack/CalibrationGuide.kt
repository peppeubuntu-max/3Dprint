package it.mototrack

import kotlin.math.*

enum class CalibrationStep { UPRIGHT, RIGHT, RETURN, DONE }
data class CalibrationReading(val degrees: Double?, val progress: Float, val stable: Boolean, val fresh: Boolean, val canConfirm: Boolean)
data class CalibrationResult(val mount: MountCalibration, val bias: DoubleArray)

/** One-second averaged poses tolerate hand tremor; no separate gyro requirement. */
class CalibrationGuide {
    private data class TimedVector(val time: Long, val vector: DoubleArray)
    private val orientations = ArrayDeque<TimedVector>()
    private val linear = ArrayDeque<TimedVector>()
    private val candidate = MountCalibration()
    var step = CalibrationStep.UPRIGHT
        private set
    private var stageStarted = 0L
    fun orientation(v: DoubleArray, nanos: Long) {
        val normalized = unit(v) ?: return
        if (orientations.lastOrNull()?.time?.let { nanos <= it } == true) return
        orientations.addLast(TimedVector(nanos, normalized))
        while (orientations.size > 1 && nanos - orientations.first().time > 1_200_000_000L) orientations.removeFirst()
    }
    fun acceleration(v: DoubleArray, nanos: Long) {
        if (v.size != 3 || v.any { !it.isFinite() }) return
        if (linear.lastOrNull()?.time?.let { nanos <= it } == true) return
        linear.addLast(TimedVector(nanos, v.copyOf()))
        while (linear.size > 1 && nanos - linear.first().time > 1_200_000_000L) linear.removeFirst()
    }
    private fun pose(now: Long) = orientations.filter { now - it.time in 0..1_000_000_000L && it.time >= stageStarted }
    private fun average(values: List<TimedVector>) = DoubleArray(3) { i -> values.sumOf { it.vector[i] } / values.size }
    private fun angle(a: DoubleArray, b: DoubleArray) = Math.toDegrees(acos(dot(a, b).coerceIn(-1.0, 1.0)))
    fun reading(now: Long, moving: Boolean = false): CalibrationReading {
        val samples = pose(now)
        val last = samples.lastOrNull()
        val fresh = last != null && now - last.time <= 400_000_000L
        val current = last?.vector
        val degrees = current?.let {
            when (step) {
                CalibrationStep.UPRIGHT -> angle(it, doubleArrayOf(0.0, 0.0, 1.0))
                CalibrationStep.RIGHT -> candidate.up?.let { zero -> angle(it, zero) }
                else -> candidate.lean(it)
            }
        }
        if (!fresh || samples.size < 2) return CalibrationReading(degrees.takeIf { fresh }, 0f, false, fresh, false)
        val mean = unit(average(samples)) ?: return CalibrationReading(degrees, 0f, false, fresh, false)
        val spread = samples.maxOf { angle(mean, it.vector) }
        val recentLinear = linear.filter { now - it.time in 0..400_000_000L }
        val accelerating = recentLinear.any { sqrt(dot(it.vector, it.vector)) > 0.8 }
        val calm = !moving && !accelerating && spread <= 2.5
        val duration = last!!.time - samples.first().time
        val progress = if (calm) (duration / 800_000_000f).coerceIn(0f, 1f) else 0f
        val stable = progress >= 1f
        val inRange = when (step) {
            CalibrationStep.UPRIGHT -> true
            CalibrationStep.RIGHT -> degrees != null && validCalibrationTilt(degrees) && validCalibrationTilt(angle(mean, candidate.up!!))
            CalibrationStep.RETURN -> degrees != null && abs(degrees) <= 3.0 && angle(mean, candidate.up!!) <= 4.0
            CalibrationStep.DONE -> false
        }
        return CalibrationReading(degrees, progress, stable, fresh, stable && inRange)
    }
    fun confirm(now: Long, moving: Boolean = false): CalibrationResult? {
        if (!reading(now, moving).canConfirm) return null
        val avgPose = unit(average(pose(now))) ?: return null
        when (step) {
            CalibrationStep.UPRIGHT -> if (candidate.zero(avgPose)) step = CalibrationStep.RIGHT
            CalibrationStep.RIGHT -> if (candidate.confirmRight(avgPose)) step = CalibrationStep.RETURN
            CalibrationStep.RETURN -> {
                if (!candidate.relevel(avgPose)) return null
                val recent = linear.filter { now - it.time in 0..1_000_000_000L && it.time >= stageStarted }
                val bias = if (recent.size >= 2 && recent.last().time - recent.first().time >= 600_000_000L) average(recent) else doubleArrayOf(0.0, 0.0, 0.0)
                step = CalibrationStep.DONE
                return CalibrationResult(candidate, bias)
            }
            CalibrationStep.DONE -> Unit
        }
        stageStarted = now + 1
        return null
    }
}

/** Shared by live display and recorded telemetry; stream reset retains mounting calibration. */
class CalibratedTelemetry {
    private var mount: MountCalibration? = null
    private var bias = doubleArrayOf(0.0, 0.0, 0.0)
    private val filter = InertialFilter()
    val ready get() = mount?.ready == true
    fun apply(result: CalibrationResult) {
        mount = result.mount; bias = result.bias.copyOf()
        filter.calibrate(result.mount.forward!!)
    }
    fun lean(up: DoubleArray): Double? = mount?.lean(up)
    fun acceleration(linear: DoubleArray, time: Long): Double? =
        if (linear.size == 3) filter.sample(DoubleArray(3) { linear[it] - bias[it] }, time) else null
    fun resetStream() = filter.reset()
}
