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
}
