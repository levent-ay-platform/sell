package tn.malak.app.ui.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndustrialCatalogTest {
    @Test
    fun opensDocumentsOnlyFromTheAgencyPublicDocumentHost() {
        val official = "https://www.afi.nat.tn/wp-content/uploads/2023/12/CC-Ariana-Airoport-1997_compressed.pdf"
        assertEquals(official, industrialDocumentUrl(official, pdf = true))
        assertNull(industrialDocumentUrl("https://atlas.ayasofia.shop/api/map/afi/documents/file.pdf", pdf = true))
        assertNull(industrialDocumentUrl("https://www.afi.nat.tn.example/wp-content/uploads/file.pdf", pdf = true))
        assertNull(industrialDocumentUrl("https://www.afi.nat.tn/wp-content/uploads/file.pdf?redirect=1", pdf = true))
        assertNull(industrialDocumentUrl("http://www.afi.nat.tn/wp-content/uploads/file.pdf", pdf = true))
    }
    @Test
    fun radarFindsIndustrialLotsInsideCircleWithoutCallingThemSaleListings() {
        val center = DiscoveryPoint(36.63, 10.62)
        val zone = IndustrialZone("931208", "بني خلاد", "Beni Khalled", "Nabeul", center, 3)
        val nearby = IndustrialLot("near", "1", null, null, null, false, false, false,
            DiscoveryPoint(36.631, 10.621))
        val distant = IndustrialLot("far", "2", null, null, null, false, false, false,
            DiscoveryPoint(36.9, 10.9))
        val plan = IndustrialPlan(zone, "2026-11-19", "2026-11-19", listOf(nearby, distant), emptyList())
        val catalog = IndustrialCatalog("2026-11-19", listOf(zone), emptyList())

        val hits = findIndustrialRadarHits(DiscoveryRadar(center, 3), catalog) { plan }
        assertEquals(1, hits.size)
        assertEquals("931208", hits.single().zone.id)
        assertEquals(1, hits.single().lotsInside)
        assertEquals(false, hits.single().approximate)
        assertEquals(false, nearby.listed)
    }

    @Test
    fun radarDoesNotCountLotWithNoAnchorUnlessEntirePlanHasNoAnchors() {
        val point = DiscoveryPoint(36.63, 10.62)
        val zone = IndustrialZone("931208", "بني خلاد", "Beni Khalled", "Nabeul", point, 2)
        val unnamed = IndustrialLot("unknown", null, null, null, null, false, false, false, null)
        val plan = IndustrialPlan(zone, "", "", listOf(unnamed), emptyList())
        val results = findIndustrialRadarHits(DiscoveryRadar(point, 5),
            IndustrialCatalog("", listOf(zone), emptyList())) { plan }
        assertEquals(1, results.single().lotsInside)
        assertEquals(true, results.single().approximate)
    }

}
