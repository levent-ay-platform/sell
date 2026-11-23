package tn.malak.app.ui.explore

import org.junit.Assert.*
import org.junit.Test

class GlobeDiscoveryTest {
    @Test fun globeBeaconTracksLongitudeAndDisappearsOnTheFarSide() {
        val target = DiscoveryPoint(0.0, 10.0)
        val centered = globeSurfacePoint(target, target)!!
        assertEquals(0.0, centered.first, .00001)
        assertEquals(0.0, centered.second, .00001)
        assertEquals(.5, globeSurfacePoint(DiscoveryPoint(0.0, -20.0), target)!!.first, .00001)
        assertNull(globeSurfacePoint(DiscoveryPoint(0.0, -170.0), target))
    }

    @Test fun governorateCirclesCountMappedShapesAndKeepUncoveredRegionsAtZero() {
        val point = DiscoveryPoint(36.8, 10.2)
        val places = listOf(AtlasPlace("TN-11", "تونس", "Tunis", point),
            AtlasPlace("TN-71", "قفصة", "Gafsa", point),
            AtlasPlace("delegation-1151", "تونس", "Tunis", point))
        val site = DiscoveryPoint(36.9, 10.1)
        val first = IndustrialZone("a", "", "", "تونس", site, 6)
        val counts = industrialGovernorates(places, listOf(first, first.copy(id = "b", shapeCount = 8)))
        assertEquals(site, counts.first().focus)
        assertEquals(point, counts.last().focus)
        assertEquals(mapOf("TN-11" to 14, "TN-71" to 0), counts.associate { it.place.id to it.lots })
    }
}
