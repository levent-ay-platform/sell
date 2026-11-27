package tn.malak.app.ui.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasLensTest {
    private val center = DiscoveryPoint(36.8, 11.0)
    private val near = IndustrialZone("2", "منطقة قريبة", "Zone proche", "نابل",
        DiscoveryPoint(36.81, 11.0), shapeCount = 120)
    private val far = IndustrialZone("1", "منطقة أبعد", "Zone éloignée", "نابل",
        DiscoveryPoint(37.1, 11.0), shapeCount = 800)
    private val unknown = IndustrialZone("3", "غير معروف", "Sans localisation", "نابل",
        center = null, shapeCount = 9000)
    private val catalog = IndustrialCatalog("2026-11-01", listOf(far, unknown, near), emptyList())

    @Test fun nearbyOnlyUsesGroundedZoneCenters() {
        val result = atlasLensNearbyZones(center, catalog, 10)
        assertEquals(listOf("2"), result.map { it.zone.id })
        assertTrue(result.single().centerDistanceKm in 1.0..2.0)
        assertEquals(120, atlasLensPlannedLots(result))
        assertFalse(result.any { it.zone.id == unknown.id })
    }

    @Test fun sortingIsDistanceBasedAndCountsFullZonePlansNotLotsInsideRadius() {
        val result = atlasLensNearbyZones(center, catalog, 50)
        assertEquals(listOf("2", "1"), result.map { it.zone.id })
        assertEquals(920, atlasLensPlannedLots(result))
        assertEquals("2026-11-01", catalog.capturedAt)
    }

    @Test fun noUnknownCoordinatesOrUnreasonableRadiusBecomeInventedEvidence() {
        assertTrue(atlasLensNearbyZones(DiscoveryPoint(Double.NaN, 11.0), catalog, 10).isEmpty())
        assertTrue(atlasLensNearbyZones(DiscoveryPoint(0.0, 0.0), catalog, 100).isEmpty())
        assertEquals("Zone proche", near.label("fr"))
        assertEquals("منطقة قريبة", near.label("ar"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedRadius() {
        atlasLensNearbyZones(center, catalog, 1001)
    }
}
