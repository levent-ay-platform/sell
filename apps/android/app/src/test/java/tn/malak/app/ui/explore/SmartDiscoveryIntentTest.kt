package tn.malak.app.ui.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartDiscoveryIntentTest {
    private val places = listOf(
        AtlasPlace("tn-kelibia", "قليبية", "Kélibia", DiscoveryPoint(36.88, 11.00)),
        AtlasPlace("tn-nabeul", "نابل", "Nabeul", DiscoveryPoint(36.45, 10.73))
    )

    @Test fun tunisianQueryExtractsPlaceBudgetAreaAndLandWithoutInventingAnything() {
        val intent = interpretSmartDiscovery(
            "نحب أرض في قليبية بين 200 و400 متر، ما تفوتش 80 ألف دينار، عندها وثائق", places
        )
        assertEquals("tn-kelibia", intent.place?.id)
        assertEquals("LAND", intent.kind)
        assertEquals("80000", intent.maxPriceTnd)
        assertEquals("200", intent.minAreaM2)
        assertEquals("400", intent.maxAreaM2)
        assertTrue(intent.asksForDocuments)
        assertFalse(intent.industrial)
        assertTrue(intent.actionable)
    }

    @Test fun frenchQueryHandlesAccentsAndRanges() {
        val intent = interpretSmartDiscovery(
            "Terrain à Kelibia entre 200 et 400 m2, budget 80 mille DT", places
        )
        assertEquals("tn-kelibia", intent.place?.id)
        assertEquals("LAND", intent.kind)
        assertEquals("80000", intent.maxPriceTnd)
        assertEquals("200", intent.minAreaM2)
        assertEquals("400", intent.maxAreaM2)
    }

    @Test fun industrialIntentIsSeparateFromPublishedPropertyListings() {
        val intent = interpretSmartDiscovery("zone industrielle à Nabeul AFI", places)
        assertTrue(intent.industrial)
        assertEquals("tn-nabeul", intent.place?.id)
        assertNull(intent.kind)
        assertNull(intent.maxPriceTnd)
        assertTrue(intent.labels("fr").any { it.contains("hors annonces") })
    }

    @Test fun approximateAreaUsesTransparentTwentyPercentRange() {
        val intent = interpretSmartDiscovery("نحب أرض حوالي 300 متر في قليبية", places)
        assertEquals("240", intent.minAreaM2)
        assertEquals("360", intent.maxAreaM2)
    }

    @Test fun supportsArabicIndicDigitsAndBudget() {
        val intent = interpretSmartDiscovery("أرض في نابل بميزانية ٨٠ ألف دينار", places)
        assertEquals("80000", intent.maxPriceTnd)
        assertEquals("tn-nabeul", intent.place?.id)
    }

    @Test fun preservesManualFiltersAndDoesNotGuessUnknownPlacesOrPrices() {
        val intent = interpretSmartDiscovery("في بلاصة ما نعرفهاش 2026", places)
        assertFalse(intent.actionable)
        assertNull(intent.place)
        assertNull(intent.maxPriceTnd)

        val manual = DiscoveryFilters(minPrice = "15000", maxPrice = "90000", minRooms = 2)
        val applied = interpretSmartDiscovery("أرض أقل من 80 ألف دينار", places).applyTo(manual)
        assertEquals("15000", applied.minPrice)
        assertEquals("80000", applied.maxPrice)
        assertNull(applied.minRooms)
    }

    @Test fun documentsRequestWithoutLocationOrFiltersDoesNotTriggerFakeSearch() {
        val intent = interpretSmartDiscovery("وين في شهادات ملكية؟", places)
        assertTrue(intent.asksForDocuments)
        assertFalse(intent.actionable)
    }
}
