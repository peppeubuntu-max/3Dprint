package it.mototrack

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt
import javax.xml.parsers.DocumentBuilderFactory

class InertialTest {
    @Test fun uprightMountPreservesAccelerationAndBrakingSign() {
        val filter = InertialFilter()
        assertTrue(filter.calibrate(doubleArrayOf(0.0, 0.0, -1.0)))
        assertEquals(1.0, filter.sample(doubleArrayOf(0.0, 0.0, -STANDARD_GRAVITY), 1_000_000_000)!!, 1e-9)
        filter.reset()
        assertEquals(-0.5, filter.sample(doubleArrayOf(0.0, 0.0, STANDARD_GRAVITY / 2), 2_000_000_000)!!, 1e-9)
    }
    @Test fun tiltedMountRejectsVerticalAndLateralAcceleration() {
        val a = sqrt(0.5)
        val up = doubleArrayOf(0.0, a, a)
        val filter = InertialFilter()
        assertTrue(filter.calibrate(doubleArrayOf(0.0, a, -a)))
        assertEquals(0.0, filter.sample(DoubleArray(3) { up[it] * STANDARD_GRAVITY }, 1_000_000_000)!!, 1e-9)
        filter.reset()
        assertEquals(0.0, filter.sample(doubleArrayOf(STANDARD_GRAVITY, 0.0, 0.0), 2_000_000_000)!!, 1e-9)
        filter.reset()
        assertEquals(1.0, filter.sample(doubleArrayOf(0.0, a * STANDARD_GRAVITY, -a * STANDARD_GRAVITY), 3_000_000_000)!!, 1e-9)
    }
    @Test fun invalidMountAndUncalibratedSensorGiveNoMeasurement() {
        val filter = InertialFilter()
        assertNull(filter.sample(doubleArrayOf(0.0, 0.0, -1.0), 1))
        assertFalse(filter.calibrate(doubleArrayOf(0.0, 0.0, 0.0)))
    }
    @Test fun filterAttenuatesShortImpulseAndResetClearsHistory() {
        val filter = InertialFilter()
        filter.calibrate(doubleArrayOf(0.0, 0.0, -1.0))
        filter.sample(doubleArrayOf(0.0, 0.0, 0.0), 1_000_000_000)
        val g = filter.sample(doubleArrayOf(0.0, 0.0, -STANDARD_GRAVITY), 1_020_000_000)!!
        assertTrue(g > 0 && g < 0.2)
        filter.reset()
        assertEquals(0.0, filter.sample(doubleArrayOf(0.0, 0.0, 0.0), 2_000_000_000)!!, 1e-9)
    }
    @Test fun peaksAndGpxRetainSensorEventsBetweenGpsFixes() {
        val ride = Ride(1, 3, 2000, emptyList(), listOf(InertialSample(1000, 0.7, 0), InertialSample(1100, -0.4, 0)))
        assertEquals(0.7 * STANDARD_GRAVITY, ride.maxAcceleration!!, 1e-9)
        assertEquals(-0.4 * STANDARD_GRAVITY, ride.maxBraking!!, 1e-9)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(ride.gpx().byteInputStream())
        assertEquals(2, doc.getElementsByTagNameNS("https://mototrack.example/xmlns/1", "sample").length)
    }
}
