package it.mototrack

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class MountCalibrationTest {
    // Arbitrary rigid mounting rotation, applied equally to all device readings.
    private fun mounted(v: DoubleArray, x: Double, y: Double, z: Double): DoubleArray {
        val a = doubleArrayOf(v[0], cos(x)*v[1]-sin(x)*v[2], sin(x)*v[1]+cos(x)*v[2])
        val b = doubleArrayOf(cos(y)*a[0]+sin(y)*a[2], a[1], -sin(y)*a[0]+cos(y)*a[2])
        return doubleArrayOf(cos(z)*b[0]-sin(z)*b[1], sin(z)*b[0]+cos(z)*b[1], b[2])
    }
    @Test fun rollAndAccelerationAreIndependentOfPhoneMounting() {
        for (x in listOf(0.0, PI/2, PI, -PI/2, 0.63)) {
            for (y in listOf(0.0, PI/2, PI, -0.42)) {
                for (z in listOf(0.0, PI/2, PI, 1.13)) {
                    fun phone(v: DoubleArray) = mounted(v, x, y, z)
                    val mount = MountCalibration()
                    assertTrue(mount.zero(phone(doubleArrayOf(0.0, 0.0, 1.0))))
                    val tilt = Math.toRadians(15.0)
                    assertTrue(mount.confirmRight(phone(doubleArrayOf(-sin(tilt), 0.0, cos(tilt)))))
                    for (roll in listOf(-55.0, -20.0, 0.0, 20.0, 55.0)) {
                        val r = Math.toRadians(roll); val pitch = Math.toRadians(12.0)
                        val gravity = phone(doubleArrayOf(-sin(r)*cos(pitch), sin(pitch), cos(r)*cos(pitch)))
                        assertEquals(roll, mount.lean(gravity)!!, 1e-8)
                    }
                    val filter = InertialFilter()
                    assertTrue(filter.calibrate(mount.forward!!))
                    // Lateral and vertical components must not change forward G.
                    assertEquals(0.4, filter.sample(phone(doubleArrayOf(2.0, 0.4*STANDARD_GRAVITY, 3.0)), 1_000_000_000)!!, 1e-8)
                    filter.reset()
                    assertEquals(-0.6, filter.sample(phone(doubleArrayOf(-2.0, -0.6*STANDARD_GRAVITY, 1.0)), 2_000_000_000)!!, 1e-8)
                }
            }
        }
    }
    @Test fun incompleteOrDegenerateCalibrationDoesNotProduceReadings() {
        val mount = MountCalibration()
        assertFalse(mount.confirmRight(doubleArrayOf(0.0, 0.0, 1.0)))
        assertNull(mount.lean(doubleArrayOf(0.0, 0.0, 1.0)))
        assertTrue(mount.zero(doubleArrayOf(0.0, 0.0, 1.0)))
        assertFalse(mount.confirmRight(doubleArrayOf(0.0, 0.0, 1.0)))
        assertFalse(mount.confirmRight(doubleArrayOf(-1.0, 0.0, 0.0)))
        assertFalse(mount.ready)
    }
    @Test fun rezeroClearsTheOldAxesUntilSecondPose() {
        val mount = MountCalibration()
        mount.zero(doubleArrayOf(0.0, 0.0, 1.0))
        mount.confirmRight(doubleArrayOf(-sin(0.2), 0.0, cos(0.2)))
        assertTrue(mount.ready)
        mount.zero(doubleArrayOf(1.0, 0.0, 0.0))
        assertFalse(mount.ready)
        assertNull(mount.lean(doubleArrayOf(1.0, 0.0, 0.0)))
    }
}
