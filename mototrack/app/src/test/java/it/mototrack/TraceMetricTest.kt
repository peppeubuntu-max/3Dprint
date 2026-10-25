package it.mototrack

import org.junit.Assert.*
import org.junit.Test

class TraceMetricTest {
    private val point = Sample(0L, 45.0, 9.0, null, 0.0, 0.0, 0.0, 1f, 0)

    @Test fun fixedScalesAndSaturation() {
        TraceMetric.entries.forEach { assertEquals(120f, traceHue(point, it)!!, 0.001f) }
        assertEquals(60f, traceHue(point.copy(speed = 60.0 / 3.6), TraceMetric.SPEED)!!, 0.001f)
        assertEquals(0f, traceHue(point.copy(speed = 150.0 / 3.6), TraceMetric.SPEED)!!, 0.001f)
        assertEquals(0f, traceHue(point.copy(lean = 60.0), TraceMetric.LEAN)!!, 0.001f)
        assertEquals(0f, traceHue(point.copy(acceleration = STANDARD_GRAVITY), TraceMetric.ACCELERATION)!!, 0.001f)
    }

    @Test fun bothLeanDirectionsAndBrakingUseMagnitude() {
        assertEquals(traceHue(point.copy(lean = 30.0), TraceMetric.LEAN), traceHue(point.copy(lean = -30.0), TraceMetric.LEAN))
        assertEquals(traceHue(point.copy(acceleration = 4.0), TraceMetric.ACCELERATION), traceHue(point.copy(acceleration = -4.0), TraceMetric.ACCELERATION))
    }

    @Test fun missingAndInvalidValuesAreNotZero() {
        assertNull(traceHue(point.copy(lean = null), TraceMetric.LEAN))
        assertNull(traceHue(point.copy(acceleration = null), TraceMetric.ACCELERATION))
        assertNull(traceHue(point.copy(speed = Double.NaN), TraceMetric.SPEED))
    }
}
