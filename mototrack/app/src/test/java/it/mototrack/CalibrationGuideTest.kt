package it.mototrack

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class CalibrationGuideTest {
    private val upright = doubleArrayOf(0.0, 0.0, 1.0)
    private fun tilt(degrees: Double): DoubleArray {
        val r = Math.toRadians(degrees)
        return doubleArrayOf(-sin(r), 0.0, cos(r))
    }
    private fun hold(g: CalibrationGuide, start: Long, angle: Double, bias: Double = 0.0, tremor: Boolean = false): Long {
        for (i in 0..50) {
            val time = start + i * 20_000_000L
            g.orientation(tilt(angle + if (tremor) sin(i.toDouble()) * 0.4 else 0.0), time)
            g.acceleration(doubleArrayOf(0.0, bias, 0.0), time)
        }
        return start + 1_000_000_000L
    }
    @Test fun fullGuideAcceptsHandTremorAndVerifiesZeroBeforeCommitting() {
        val guide = CalibrationGuide()
        var time = hold(guide, 1_000_000_000L, 0.0, tremor = true)
        assertTrue(guide.reading(time).canConfirm)
        assertNull(guide.confirm(time))
        assertEquals(CalibrationStep.RIGHT, guide.step)
        assertFalse(guide.reading(time).canConfirm)
        time = hold(guide, time + 20_000_000L, 12.0, tremor = true)
        assertEquals(12.0, guide.reading(time).degrees!!, 0.5)
        assertNull(guide.confirm(time))
        assertEquals(CalibrationStep.RETURN, guide.step)
        assertFalse(guide.reading(time).canConfirm)
        time = hold(guide, time + 20_000_000L, 0.0, bias = 0.12, tremor = true)
        val result = guide.confirm(time)!!
        assertEquals(CalibrationStep.DONE, guide.step)
        assertEquals(0.0, result.mount.lean(upright)!!, 0.5)
        assertEquals(0.12, result.bias[1], 0.00001)
    }
    private fun calibrate(): CalibrationResult {
        val guide = CalibrationGuide()
        var time = hold(guide, 1_000_000_000L, 0.0)
        guide.confirm(time)
        time = hold(guide, time + 20_000_000L, 15.0)
        guide.confirm(time)
        time = hold(guide, time + 20_000_000L, 0.0, bias = 0.1)
        return guide.confirm(time)!!
    }
    @Test fun recordedTelemetryUsesConfirmedAxesAndBiasAfterPauseResume() {
        val telemetry = CalibratedTelemetry()
        telemetry.apply(calibrate())
        assertEquals(0.0, telemetry.acceleration(doubleArrayOf(0.0, 0.1, 0.0), 10_000_000_000L)!!, 1e-8)
        telemetry.resetStream() // pause/resume resets smoothing, not calibration
        val g = telemetry.acceleration(doubleArrayOf(2.0, -0.4 * STANDARD_GRAVITY + 0.1, 1.0), 11_000_000_000L)!!
        val lean = telemetry.lean(tilt(-22.0))!!
        assertEquals(-0.4, g, 1e-8)
        assertEquals(-22.0, lean, 1e-8)
        val point = Sample(11000, 45.0, 9.0, null, 12.0, lean, g * STANDARD_GRAVITY, 4f, 1, "sensor")
        val ride = Ride(10000, 12000, 1000, listOf(point), listOf(InertialSample(11000, g, 1)))
        assertEquals(-22.0, ride.playback(1).point!!.lean!!, 1e-8)
        assertEquals(-0.4, ride.playback(1).accelerationG!!, 1e-8)
        assertTrue(ride.gpx().contains("<mt:accelerationSource>sensor</mt:accelerationSource>"))
        assertEquals(-22.0, ride.gpx().substringAfter("<mt:lean>").substringBefore("</mt:lean>").toDouble(), 1e-8)
    }
    @Test fun failedOrCancelledNewGuideDoesNotReplaceWorkingCalibration() {
        val telemetry = CalibratedTelemetry(); telemetry.apply(calibrate())
        val replacement = CalibrationGuide()
        val time = hold(replacement, 1_000_000_000L, 0.0)
        replacement.confirm(time)
        assertNull(replacement.confirm(time))
        // No apply until all three steps pass: original still measures signed roll.
        assertEquals(20.0, telemetry.lean(tilt(20.0))!!, 1e-8)
    }
    @Test fun staleMovingAndShortSamplesCannotConfirm() {
        val guide = CalibrationGuide()
        guide.orientation(upright, 1_000_000_000L)
        assertFalse(guide.reading(1_000_000_000L).canConfirm)
        val time = hold(guide, 2_000_000_000L, 0.0)
        assertTrue(guide.reading(time).canConfirm)
        assertFalse(guide.reading(time, moving = true).canConfirm)
        assertFalse(guide.reading(time + 1_000_000_000L).fresh)
        assertNull(guide.confirm(time + 1_000_000_000L))
    }
    @Test fun wrongRightAngleAndIncompleteReturnAreRejected() {
        val guide = CalibrationGuide()
        var time = hold(guide, 1_000_000_000L, 0.0); guide.confirm(time)
        time = hold(guide, time + 20_000_000L, 2.0)
        assertFalse(guide.reading(time).canConfirm)
        time = hold(guide, time + 20_000_000L, 15.0); guide.confirm(time)
        time = hold(guide, time + 20_000_000L, 8.0)
        assertFalse(guide.reading(time).canConfirm)
        assertNull(guide.confirm(time))
    }
}
