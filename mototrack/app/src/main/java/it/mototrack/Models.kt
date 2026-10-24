package it.mototrack

import java.time.Instant
import kotlin.math.*

data class Sample(
    val time: Long, val lat: Double, val lon: Double, val altitude: Double?,
    val speed: Double?, val lean: Double?, val acceleration: Double?,
    val accuracy: Float, val segment: Int, val accelerationSource: String = "gps"
)
data class Ride(val id: Long, val ended: Long?, val activeMillis: Long, val points: List<Sample>, val inertial: List<InertialSample> = emptyList()) {
    val distanceMeters: Double get() = points.zipWithNext().sumOf { (a, b) ->
        if (a.segment == b.segment) distance(a.lat, a.lon, b.lat, b.lon) else 0.0
    }
    val maxSpeed: Double? get() = points.mapNotNull { it.speed }.maxOrNull()
    val maxLean: Double? get() = points.mapNotNull { it.lean?.let(::abs) }.maxOrNull()
    val maxAcceleration: Double? get() = (if (inertial.isNotEmpty()) inertial.map { it.g * STANDARD_GRAVITY } else points.mapNotNull { it.acceleration }).maxOrNull()?.coerceAtLeast(0.0)
    val maxBraking: Double? get() = (if (inertial.isNotEmpty()) inertial.map { it.g * STANDARD_GRAVITY } else points.mapNotNull { it.acceleration }).minOrNull()?.coerceAtMost(0.0)
}
fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 6371000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
fun relativeAngle(current: Double, zero: Double): Double = ((current - zero + 540) % 360) - 180
fun longitudinalAcceleration(previous: Sample?, speed: Double?, time: Long, segment: Int): Double? {
    if (previous?.speed == null || speed == null || previous.segment != segment) return null
    val dt = (time - previous.time) / 1000.0
    return if (dt in 0.5..5.0) (speed - previous.speed) / dt else null
}
// Fixed scale: 0 km/h green, 60 yellow, 120+ red. Unknown speed is gray.
fun speedHue(speed: Double): Float = (120 * (1 - (speed * 3.6 / 120).coerceIn(0.0, 1.0))).toFloat()
fun Ride.gpx(): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<gpx version=\"1.1\" creator=\"MotoTrack\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:mt=\"https://mototrack.example/xmlns/1\"><trk><name>Giro $id</name>")
    if (inertial.isNotEmpty()) {
        append("<extensions><mt:inertial>")
        inertial.forEach { append("<mt:sample time=\"${Instant.ofEpochMilli(it.time)}\" g=\"${it.g}\" segment=\"${it.segment}\"/>") }
        append("</mt:inertial></extensions>")
    }
    var current: Int? = null
    points.forEach { p ->
        if (p.segment != current) {
            if (current != null) append("</trkseg>")
            append("<trkseg>"); current = p.segment
        }
        append("<trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">")
        p.altitude?.let { append("<ele>$it</ele>") }
        append("<time>${Instant.ofEpochMilli(p.time)}</time><extensions>")
        p.speed?.let { append("<mt:speed>$it</mt:speed>") }
        p.lean?.let { append("<mt:lean>$it</mt:lean>") }
        p.acceleration?.let { append("<mt:acceleration>$it</mt:acceleration><mt:accelerationG>${it / STANDARD_GRAVITY}</mt:accelerationG><mt:accelerationSource>${p.accelerationSource}</mt:accelerationSource>") }
        append("<mt:accuracy>${p.accuracy}</mt:accuracy></extensions></trkpt>")
    }
    if (current != null) append("</trkseg>")
    append("</trk></gpx>")
}
