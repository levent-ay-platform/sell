package tn.malak.app.ui
import org.junit.Assert.*
import org.junit.Test

class PilotJourneyPolicyTest {
    @Test fun onlyMissingRequiredFieldsNeedAttentionAndCommaAreaIsAccepted() {
        assertEquals(listOf("region","area","price"),pilotMissingListingFields("Terrain","","0",""))
        assertTrue(pilotMissingListingFields("Terrain Tunis","Tunis","185,25","50000").isEmpty())
        assertEquals(listOf("title"),pilotMissingListingFields("x","Tunis","100","50000"))
    }
    @Test fun completionRequiresConfirmedServerReceiptAndRejectsWrongStatus() {
        assertEquals("12345678",pilotSubmissionReference("12345678-1234-4abc-8abc-123456789abc","PENDING_REVIEW"))
        assertNull(pilotSubmissionReference("not-an-id","PENDING_REVIEW"))
        assertNull(pilotSubmissionReference("12345678-1234-4abc-8abc-123456789abc","DRAFT"))
    }
}
