package it.mototrack

import kotlin.math.abs

// Timeline is wall-clock time from ride start; pauses and GPS gaps remain empty.
data class Playback(val time: Long, val point: Sample?, val accelerationG: Double?, val source: String?)
fun Ride.timelineEnd(): Long = maxOf(id, ended ?: id, points.lastOrNull()?.time ?: id, inertial.lastOrNull()?.time ?: id)
private fun <T> nearest(values: List<T>, time: Long, tolerance: Long, timestamp: (T) -> Long): T? {
    var low = 0; var high = values.size
    while (low < high) {
        val mid = (low + high) / 2
        if (timestamp(values[mid]) < time) low = mid + 1 else high = mid
    }
    return listOfNotNull(values.getOrNull(low - 1), values.getOrNull(low))
        .minByOrNull { abs(timestamp(it) - time) }?.takeIf { abs(timestamp(it) - time) <= tolerance }
}
fun Ride.playback(second: Long): Playback {
    val time = (id + second.coerceAtLeast(0) * 1000).coerceAtMost(timelineEnd())
    val point = nearest(points, time, 1000) { it.time }
    val imu = nearest(inertial, time, 200) { it.time }
    return Playback(time, point, imu?.g ?: point?.acceleration?.div(STANDARD_GRAVITY),
        if (imu != null) "sensor" else point?.accelerationSource)
}
