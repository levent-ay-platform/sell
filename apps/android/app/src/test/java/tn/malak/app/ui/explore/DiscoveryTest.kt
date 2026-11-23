package tn.malak.app.ui.explore

import org.junit.Assert.*
import org.junit.Test

class DiscoveryTest {
    @Test
    fun placeSuggestionsMatchFrenchAccentsAndArabicDiacritics() {
        assertEquals("kelibia", foldPlaceName("Kélibia"))
        assertEquals("قليبية", foldPlaceName("قَلِيبِيَة"))
        assertEquals("نابل", foldPlaceName("نـابل"))
    }

    @Test
    fun decimalFiltersDoNotRoundDinarsOrDropArabicInput() {
        val filters = DiscoveryFilters.parse("APARTMENT", "١٢٣٤٥٦٫٧٨٩", "٢٠٠٠٠٠", "", "١٢٠", "٢")
        assertNotNull(filters)
        assertEquals("123456.789", filters!!.minPrice)
        assertEquals("200000", filters.maxPrice)
        assertEquals("120", filters.maxArea)
        assertEquals(2, filters.minRooms)
        assertNull(DiscoveryFilters.parse(null, "5", "2", "", "", ""))
        assertNull(DiscoveryFilters.parse(null, "3.1234", "", "", "", ""))
        assertNull(DiscoveryFilters.parse(null, "", "", "", "", "2.1"))
    }

    @Test
    fun mapCannotShowAnAreaOnlyOrUnlocatedListing() {
        for (accuracy in listOf("UNKNOWN", "AREA_ONLY")) {
            assertNull(discoveryPoint(36.85, 11.10, accuracy))
        }
        assertNull(discoveryPoint(95.0, 11.1, "APPROXIMATE"))
        assertNull(discoveryPoint(36.85, null, "APPROXIMATE"))
        assertEquals(DiscoveryPoint(36.85, 11.10), discoveryPoint(36.85, 11.10, "APPROXIMATE"))
    }

    @Test
    fun listingAndMapQueriesCarryIdenticalFiltersAndArea() {
        val filters = DiscoveryFilters("HOUSE", minPrice = "100000", maxArea = "200")
        val area = DiscoveryBounds(10.55, 36.38, 11.19, 37.09)
        val common = discoveryParameters(area, "kelibia", filters)
        assertEquals("10.55,36.38,11.19,37.09", common["bbox"])
        assertEquals("HOUSE", common["kind"])
        assertEquals("kelibia", common["placeId"])
        assertEquals("100000", common["minPrice"])
        assertEquals("200", common["maxArea"])
        assertFalse(common.containsKey("minRooms"))
        assertNull(DiscoveryBounds.decode("10.55,36.38,9,37.09"))
        assertEquals(area, DiscoveryBounds.decode(common["bbox"]!!))
    }
    @Test
    fun publishedPilotFeedUsesTheSameFiltersForMapAndList() {
        val listing = DiscoveryListing(
            id = "12345678-1234-4234-8234-123456789012",
            title = "Terrain", region = "Nabeul", kind = "LAND",
            priceTnd = "80000", areaM2 = "300", rooms = null,
            point = DiscoveryPoint(36.88, 11.0), locationAccuracy = "APPROXIMATE",
            photoUrl = null, description = null
        )
        val bounds = DiscoveryBounds(10.5, 36.6, 11.5, 37.2)
        val query = DiscoveryQuery(bounds, null,
            DiscoveryFilters(kind = "LAND", maxPrice = "90000", minArea = "240", maxArea = "360"))
        assertTrue(listing.matchesPilotQuery(query))
        assertFalse(listing.matchesPilotQuery(query.copy(
            filters = query.filters.copy(maxPrice = "70000"))))
        assertFalse(listing.matchesPilotQuery(query.copy(
            bounds = DiscoveryBounds(8.0, 33.0, 9.0, 34.0))))
    }

    @Test
    fun areaOnlyPublicListingStaysOnListButNeverGetsAFabricatedRadarPin() {
        val areaOnly = DiscoveryListing(
            id = "12345678-1234-4234-8234-123456789012",
            title = "Terrain", region = "Nabeul", kind = "LAND",
            priceTnd = "75000", areaM2 = "300", rooms = null,
            point = null, locationAccuracy = "AREA_ONLY",
            photoUrl = null, description = null
        )
        val bounds = DiscoveryBounds(10.5, 36.6, 11.5, 37.2)
        assertTrue(areaOnly.matchesPilotQuery(DiscoveryQuery(bounds, null, DiscoveryFilters())))
        val radar = DiscoveryRadar(DiscoveryPoint(36.85, 11.0), 10)
        assertFalse(areaOnly.matchesPilotQuery(DiscoveryQuery(bounds, null, DiscoveryFilters(), radar)))
        assertNull(areaOnly.point)
    }

}
