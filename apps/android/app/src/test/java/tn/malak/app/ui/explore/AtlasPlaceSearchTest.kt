package tn.malak.app.ui.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AtlasPlaceSearchTest {
    private val kelibia = AtlasPlace("delegation-1557", "قليبية", "Kélibia", DiscoveryPoint(36.88, 11.00))
    private val index = listOf(
        AtlasPlace("other", "مدينة قليبية", "Grand Kélibia", DiscoveryPoint(36.89, 11.02)),
        kelibia
    )

    @Test
    fun bilingualSearchRanksExactMatchesAndLeavesServerFilteringToTheViewport() {
        val match = index.searchAtlasPlaces("قَلِيبِيَة", "fr").first()
        assertEquals("Kélibia", match.label)
        assertEquals(kelibia.point, match.point)
        assertNull(match.serverPlaceId)
        assertEquals("قليبية", index.searchAtlasPlaces("Kelibia", "ar").first().label)
    }
}
