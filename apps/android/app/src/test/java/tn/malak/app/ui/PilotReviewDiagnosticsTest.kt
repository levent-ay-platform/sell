package tn.malak.app.ui

import org.junit.Assert.*
import org.junit.Test

class PilotReviewDiagnosticsTest {
    @Test fun actualReasonsAndMissingPagesSurviveParsing() {
        val result=pilotReviewDiagnostics(listOf("DOCUMENT_TYPE_NOT_CONFIRMED",
            "CERTIFICATE_PAGES_MISSING"),listOf(2),1)
        assertEquals(listOf("DOCUMENT_TYPE_NOT_CONFIRMED","CERTIFICATE_PAGES_MISSING"),result.reasons)
        assertEquals(2,result.expectedPageCount)
    }
    @Test fun olderServerRemainsCompatibleWithoutInventingMissingPages() {
        val result=pilotReviewDiagnostics(null,null,1)
        assertEquals(listOf("OFFICIAL_REGISTRY_NOT_CHECKED"),result.reasons)
        assertNull(result.expectedPageCount)
    }
    @Test fun malformedUntrustedReasonsAndPageNumbersAreRejected() {
        for(input in listOf(listOf("<script>"),List(33){"UNSAFE_REASON"})) {
            assertThrows(IllegalArgumentException::class.java){pilotReviewDiagnostics(input,emptyList(),1)}
        }
        for(input in listOf(listOf(0),listOf(9),listOf(2,2))) {
            assertThrows(IllegalArgumentException::class.java){pilotReviewDiagnostics(emptyList(),input,1)}
        }
    }
}
