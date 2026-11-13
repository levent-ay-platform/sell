package tn.malak.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartListingAssistTest {
    @Test fun titlesUseOnlyEnteredFacts() {
        assertEquals("أرض للبيع في قليبية · 150 م²",
            SmartListingAssist.titleSuggestion("  قليبية  ", "150.000", "أرض للبيع في", "م²"))
        assertEquals("Terrain à vendre à Kélibia · 150.5 m²",
            SmartListingAssist.titleSuggestion("Kélibia", "150,500", "Terrain à vendre à", "m²"))
        assertNull(SmartListingAssist.titleSuggestion("", "150", "أرض للبيع في", "م²"))
        assertNull(SmartListingAssist.titleSuggestion("Kelibia", "0", "Land in", "m²"))
        assertNull(SmartListingAssist.titleSuggestion("Kelibia", "bad", "Land in", "m²"))
    }

    @Test fun unitPriceIsCorrectAndNeverGuessesMarketPrice() {
        assertEquals(126L, SmartListingAssist.pricePerSquareMeter("150", "18900"))
        assertEquals(127L, SmartListingAssist.pricePerSquareMeter("150", "18975"))
        assertEquals(100L, SmartListingAssist.pricePerSquareMeter("10,5", "1050"))
        assertNull(SmartListingAssist.pricePerSquareMeter("0", "18900"))
        assertNull(SmartListingAssist.pricePerSquareMeter("150", "0"))
        assertNull(SmartListingAssist.pricePerSquareMeter("150", "18,900"))
    }
}
