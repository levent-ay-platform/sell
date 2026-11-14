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

    @Test fun coordinatesNeverGuessOrUseInvalidValues() {
        assertEquals(36.852 to 10.312, SmartListingAssist.coordinates("36,852","10.312"))
        assertEquals(-90.0 to -180.0, SmartListingAssist.coordinates("-90","-180"))
        assertNull(SmartListingAssist.coordinates("91","10"))
        assertNull(SmartListingAssist.coordinates("33","181"))
        assertNull(SmartListingAssist.coordinates("30.123456789","9"))
        assertNull(SmartListingAssist.coordinates("NaN","9"))
        assertNull(SmartListingAssist.coordinates("","9"))
    }
    @Test fun localDescriptionContainsOnlySellerSubmittedFacts() {
        val text=SmartListingAssist.descriptionSuggestion(
            "Kélibia","150","18900","Terrain à vendre à","m²","Prix demandé")
        assertEquals("Terrain à vendre à Kélibia · 150 m². Prix demandé 18900 TND.",text)
        assertNull(SmartListingAssist.descriptionSuggestion(
            "Kélibia","0","18900","Terrain à vendre à","m²","Prix demandé"))
    }

}
