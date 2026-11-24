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
    @Test fun expandedCertificateReadoutDoesNotConfuseParentTitleOrHistoricalLocality(){
        val fields=ownerExtractedValues("OWNERSHIP_CERTIFICATE",listOf(
            PilotPropertyFact("parentRegisterReference","999903",1,"ocr-ar","CANDIDATE"),
            PilotPropertyFact("registeredParcelCount","1",1,"ocr-ar","CANDIDATE"),
            PilotPropertyFact("historicalLocality","اسم قديم",1,"ocr-ar","CANDIDATE"),
            PilotPropertyFact("propertyDelegation","الحمامات",1,"ocr-ar","CANDIDATE")))
        assertNull(fields.first().fact)
        assertEquals("999903",fields.single{it.key=="parentRegisterReference"}.fact?.value)
        assertEquals("1",fields.single{it.key=="registeredParcelCount"}.fact?.value)
        assertEquals("اسم قديم",fields.single{it.key=="historicalLocality"}.fact?.value)
        assertFalse(fields.any{it.key=="propertyImada"})
    }
    @Test fun registeredAreaNeedsExplicitConfirmationAndSharedOrUncertainAreaIsNotOffered(){
        val fact=PilotPropertyFact("registeredAreaM2","9000",1,"ocr-ar","CANDIDATE")
        assertNull(listingAreaEvidence("OWNERSHIP_CERTIFICATE",listOf(fact)))
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
        val fact = PilotPropertyFact("soldAreaM2", "900.5", 3, "pdf-text", "CANDIDATE")
        assertEquals(fact, listingAreaEvidence("SALE_DEED", listOf(fact)))
        assertNull(listingAreaEvidence("OWNERSHIP_CERTIFICATE", listOf(fact)))
        assertTrue(sameListingArea("٩٠٠٫٥٠", fact))
        assertFalse(sameListingArea("275,5", fact))
        assertFalse(sameListingArea("", fact))
        assertFalse(sameListingArea("9005E-1", fact))
        assertNull(listingAreaEvidence("SALE_DEED", listOf(fact, fact.copy(status = "LOW_CONFIDENCE"))))
    }

    @Test fun ownerShareIsReviewOnlyAndRequiresUnambiguousPositiveParts(){
        val denominator=PilotPropertyFact("rightsDenominator","40000",1,"ocr-ar","CANDIDATE")
        assertEquals("25",ownerSharePercentage("10000",listOf(denominator)))
        assertEquals("12.5",ownerSharePercentage("5000.000",listOf(denominator)))
        assertNull(ownerSharePercentage(null,listOf(denominator)))
        assertNull(ownerSharePercentage("50000",listOf(denominator)))
        assertNull(ownerSharePercentage("0",listOf(denominator)))
        assertNull(ownerSharePercentage("10000",listOf(denominator.copy(status="LOW_CONFIDENCE"))))
        assertNull(ownerSharePercentage("10000",listOf(denominator,denominator.copy(value="30000"))))
        assertNull(ownerSharePercentage("10000",listOf(denominator.copy(key="registeredAreaM2"))))
    }
}
