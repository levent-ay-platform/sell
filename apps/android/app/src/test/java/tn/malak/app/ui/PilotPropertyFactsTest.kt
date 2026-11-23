package tn.malak.app.ui
import org.junit.Assert.*
import org.junit.Test
class PilotPropertyFactsTest{
    @Test fun unavailableCertificateValuesStayExplicitAndDeedAreaIsNotRelabelledRegistered(){
        val ref=PilotPropertyFact("registerReference","555941",1,"ocr-ar","LOW_CONFIDENCE")
        val fields=ownerExtractedValues("OWNERSHIP_CERTIFICATE",listOf(ref))
        assertEquals(listOf("registerReference","registeredAreaM2","registeredPlotNumber"),fields.map{it.key})
        assertEquals("555941",fields[0].fact?.value)
        assertNull(fields[1].fact);assertNull(fields[2].fact)
        assertEquals("soldAreaM2",ownerExtractedValues("SALE_DEED",emptyList())[1].key)
    }
    @Test fun registeredAreaNeedsExplicitConfirmationAndSharedOrUncertainAreaIsNotOffered(){
        val fact=PilotPropertyFact("registeredAreaM2","9000",1,"ocr-ar","CANDIDATE")
        assertEquals("9000",listingAreaEvidence("OWNERSHIP_CERTIFICATE",listOf(fact))?.value)
        assertNull(listingAreaEvidence("SHARED_OWNERSHIP_CERTIFICATE",listOf(fact)))
        assertNull(listingAreaEvidence("OWNERSHIP_CERTIFICATE",listOf(fact.copy(status="LOW_CONFIDENCE"))))
        assertFalse(pilotAutofill("OWNERSHIP_CERTIFICATE",listOf(fact)).containsKey("area"))
    }
    @Test fun historicalDeedPriceCannotSetCurrentAskingPrice(){
        val facts=listOf(PilotPropertyFact("salePriceTnd","72000",1,"ocr-ar","CANDIDATE"))
        assertFalse(pilotAutofill("SALE_DEED",facts).containsKey("price"))
    }
    @Test fun uncertaintyKeepsProvenanceButCannotAutofill(){
        val low=PilotPropertyFact("soldAreaM2","185.25",2,"ocr-ar","LOW_CONFIDENCE")
        assertEquals(2,low.page)
        assertTrue(pilotAutofill("SALE_DEED",listOf(low)).isEmpty())
        assertEquals("185.25",pilotAutofill("SALE_DEED",listOf(low.copy(status="CANDIDATE")))["area"])
    }
    @Test fun registeredWholeAreaAndAbstractSharesNeverBecomeSaleArea(){
        val facts=listOf(PilotPropertyFact("registeredAreaM2","9000",1,"ocr-ar","CANDIDATE"),PilotPropertyFact("rightsDenominator","100",1,"ocr-ar","CANDIDATE"))
        assertFalse(pilotAutofill("OWNERSHIP_CERTIFICATE",facts).containsKey("area"))
        assertFalse(pilotAutofill("SALE_DEED",facts).containsKey("area"))
    }
    @Test fun conflictingOrUnattributedSoldAreaNeverFillsTheDraft() {
        val sold = PilotPropertyFact("soldAreaM2", "275", 2, "ocr-ar", "CANDIDATE")
        assertTrue(pilotAutofill("SALE_DEED", listOf(sold, sold.copy(value = "900"))).isEmpty())
        assertTrue(pilotAutofill("SALE_DEED", listOf(sold.copy(page = 0))).isEmpty())
    }
    @Test fun comparisonPreservesOwnerFormattingAndUsesTheDocumentPage() {
        val fact = PilotPropertyFact("registeredAreaM2", "900.5", 3, "pdf-text", "CANDIDATE")
        assertEquals(fact, listingAreaEvidence("OWNERSHIP_CERTIFICATE", listOf(fact)))
        assertNull(listingAreaEvidence("SALE_DEED", listOf(fact)))
        assertTrue(sameListingArea("٩٠٠٫٥٠", fact))
        assertFalse(sameListingArea("275,5", fact))
        assertFalse(sameListingArea("", fact))
        assertNull(listingAreaEvidence("OWNERSHIP_CERTIFICATE", listOf(fact, fact.copy(status = "LOW_CONFIDENCE"))))
    }
}
