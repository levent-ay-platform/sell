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
        assertEquals("9000",pilotConfirmableArea("OWNERSHIP_CERTIFICATE",listOf(fact))?.value)
        assertNull(pilotConfirmableArea("SHARED_OWNERSHIP_CERTIFICATE",listOf(fact)))
        assertNull(pilotConfirmableArea("OWNERSHIP_CERTIFICATE",listOf(fact.copy(status="LOW_CONFIDENCE"))))
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
}
