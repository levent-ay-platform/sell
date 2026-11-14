package tn.malak.app.ui

import org.junit.Assert.*
import org.junit.Test

/** Real camera acceptance requirements, using fabricated names and digits only. */
class FrontCameraAcceptanceTest {
    private fun complete()=LocalDocumentOcr.FrontScan(
        surname="الشمري",givenName="مروان",fullName="الشمري مروان",
        cinLast4="4567",birthDate="1990-01-31",confidence=72,
        cinFull="01234567"
    )

    @Test fun allFourExtractedFieldsPermitPreliminaryCapture() {
        assertTrue(complete().readyForCameraAcceptance)
    }

    @Test fun missingCinMayProceedForAdminReviewButContradictionCannot() {
        assertTrue(complete().copy(cinFull=null).readyForCameraAcceptance)
        assertTrue(complete().copy(cinLast4=null,cinFull=null).readyForCameraAcceptance)
        assertFalse(complete().copy(cinLast4="9999").readyForCameraAcceptance)
    }

    @Test fun missingBirthdayMayProceedButImpossibleDateCannot() {
        assertTrue(complete().copy(birthDate=null).readyForCameraAcceptance)
        assertFalse(complete().copy(birthDate="1990-02-31").readyForCameraAcceptance)
    }

    @Test fun completedIncompleteReadMayProceedButServiceFailureRequiresRetry() {
        assertTrue(complete().copy(givenName=null).readyForCameraAcceptance)
        assertTrue(complete().copy(surname=null).readyForCameraAcceptance)
        assertTrue(complete().copy(confidence=48).readyForCameraAcceptance)
        assertFalse(complete().copy(issue=LocalDocumentOcr.FrontIssue.SERVICE_UNAVAILABLE)
            .readyForCameraAcceptance)
    }

    @Test fun maskedTestCardMayBeSubmittedForAdminReview() {
        val masked=complete().copy(cinFull=null)
        assertTrue(masked.readyForReview)
        assertTrue(masked.readyForCameraAcceptance)
    }
}
