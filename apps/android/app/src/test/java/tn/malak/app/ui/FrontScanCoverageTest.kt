package tn.malak.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontScanCoverageTest {
    private fun scan(last4:String?="4567",birth:String?="1991-02-18")=
        LocalDocumentOcr.FrontScan("محمد","علي","محمد علي",last4,birth,72)
    @Test fun completeFrontCanBeReviewedWithoutBack(){
        assertTrue(scan().readyForReview)
    }
    @Test fun obscuredNumberOrDateCannotPass(){
        assertFalse(scan(last4=null).readyForReview)
        assertTrue(scan(last4=null).readyForManualReview)
        assertFalse(scan().readyForManualReview)
        assertFalse(scan(birth=null).readyForReview)
        assertFalse(scan(last4=null,birth=null).readyForManualReview)
        assertFalse(scan(birth="1991-02-31").readyForReview)
        assertFalse(scan(birth="1899-12-31").readyForReview)
        assertFalse(scan(birth="2099-01-01").readyForReview)
    }
    @Test fun unavailableOrLowConfidenceCannotBeManuallyAccepted(){
        val poor=LocalDocumentOcr.FrontScan("محمد","علي","محمد علي",null,"1991-02-18",60)
        assertFalse(poor.readyForManualReview)
        val unavailable=poor.copy(confidence=90,
            issue=LocalDocumentOcr.FrontIssue.SERVICE_UNAVAILABLE)
        assertFalse(unavailable.readyForManualReview)
        assertFalse(scan(last4=null,birth="2099-01-01").readyForManualReview)
    }
}
