package it.mototrack

import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class ModelsTest {
    private fun point(t: Long = 1000, lon: Double = 9.0, speed: Double? = 10.0, segment: Int = 0) =
        Sample(t, 45.0, lon, null, speed, null, null, 5f, segment)

    @Test fun pauseDoesNotBridgeDistance() {
        val ride = Ride(1, 3, 2000, listOf(point(), point(2000, 10.0, segment = 1)))
        assertEquals(0.0, ride.distanceMeters, 0.001)
    }
    @Test fun distanceUsesMeters() {
        assertEquals(111195.0, distance(0.0, 0.0, 0.0, 1.0), 1.0)
    }
    @Test fun accelerationUsesSecondsAndPreservesBrakingSign() {
        assertEquals(-2.0, longitudinalAcceleration(point(), 6.0, 3000, 0)!!, 0.001)
        assertNull(longitudinalAcceleration(point(), 6.0, 3000, 1))
        assertNull(longitudinalAcceleration(point(), 6.0, 9000, 0))
        assertNull(longitudinalAcceleration(point(speed = null), 6.0, 3000, 0))
    }
    @Test fun calibrationWrapsAt180Degrees() {
        assertEquals(2.0, relativeAngle(-179.0, 179.0), 0.001)
        assertEquals(-2.0, relativeAngle(179.0, -179.0), 0.001)
    }
    @Test fun colorScaleIsFixedAndClamped() {
        assertEquals(120f, speedHue(0.0), 0.01f)
        assertEquals(60f, speedHue(60.0 / 3.6), 0.01f)
        assertEquals(0f, speedHue(200.0 / 3.6), 0.01f)
    }
    @Test fun gpxIsParseableAndPreservesSegmentsAndTelemetry() {
        val ride = Ride(1, 3, 2000, listOf(point(), point(2000, segment = 1)))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val xml = factory.newDocumentBuilder().parse(ride.gpx().byteInputStream())
        assertEquals(2, xml.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkseg").length)
        assertEquals(2, xml.getElementsByTagNameNS("https://mototrack.example/xmlns/1", "speed").length)
        assertEquals(0, xml.getElementsByTagNameNS("https://mototrack.example/xmlns/1", "lean").length)
    }
}
