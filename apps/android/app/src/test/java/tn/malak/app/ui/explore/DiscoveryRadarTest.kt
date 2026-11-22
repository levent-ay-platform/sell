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
}
