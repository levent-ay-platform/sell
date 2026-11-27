package tn.malak.app.ui.explore

import org.junit.Assert.*
import org.junit.Test

class DiscoveryRadarTest {
    @Test
    fun acceptsRealPointsInsideTheCircleAndRejectsTheBBoxCorners() {
        val radar = DiscoveryRadar(DiscoveryPoint(36.85, 11.09), 5)
        assertTrue(radar.contains(DiscoveryPoint(36.88, 11.10)))
        val corner = DiscoveryPoint(radar.bounds.north, radar.bounds.east)
        assertTrue(corner in radar.bounds)
        assertFalse(radar.contains(corner))
        assertEquals(radar, DiscoveryRadar.decode(radar.encode()))
        assertNull(DiscoveryRadar.decode("36.85,11.09,1000"))
    }
    @Test
    fun distanceIsZeroAtCenterAndRespectsSelectedRadius() {
        val center = DiscoveryPoint(36.63, 10.62)
        val radar = DiscoveryRadar(center, 2)
        assertEquals(0.0, radar.distanceKm(center), 0.000001)
        assertTrue(radar.contains(DiscoveryPoint(36.635, 10.622)))
        assertFalse(radar.contains(DiscoveryPoint(36.7, 10.62)))
    }

}
