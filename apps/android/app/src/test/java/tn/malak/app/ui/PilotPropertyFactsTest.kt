package tn.malak.app.ui
import org.junit.Assert.*
import org.junit.Test
class PilotPropertyFactsTest{
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
