package it.mototrack

import org.junit.Assert.*
import org.junit.Test

class PlaybackTest {
    private fun point(time: Long, segment: Int = 0) = Sample(time, 45.0, 9.0, null, 10.0, 12.0, 1.0, 5f, segment)
    @Test fun selectsTelemetryAtSecondAndPrefersInertialSample() {
        val ride = Ride(10000, 15000, 5000, listOf(point(12000)), listOf(InertialSample(12050, -0.3, 0)))
        val view = ride.playback(2)
        assertEquals(12000L, view.point!!.time)
        assertEquals(-0.3, view.accelerationG!!, 0.00001)
        assertEquals("sensor", view.source)
    }
    @Test fun doesNotInventValuesAcrossPauseOrGpsGap() {
        val ride = Ride(10000, 30000, 5000, listOf(point(11000), point(29000, 1)))
        assertNull(ride.playback(10).point)
        assertNull(ride.playback(10).accelerationG)
    }
    @Test fun emptyAndInterruptedRidesAreSupported() {
        assertNull(Ride(10000, null, 0, emptyList()).playback(0).point)
        val ride = Ride(10000, null, 1000, emptyList(), listOf(InertialSample(13000, 0.2, 0)))
        assertEquals(13000L, ride.timelineEnd())
        assertEquals(0.2, ride.playback(3).accelerationG!!, 0.00001)
    }
    @Test fun staleSensorDoesNotOverrideGps() {
        val ride = Ride(10000, 15000, 5000, listOf(point(12000)), listOf(InertialSample(11000, 0.9, 0)))
        assertEquals(1.0 / STANDARD_GRAVITY, ride.playback(2).accelerationG!!, 0.00001)
        assertEquals("gps", ride.playback(2).source)
    }
}
