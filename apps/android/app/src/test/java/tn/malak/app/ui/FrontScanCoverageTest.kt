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
        assertFalse(scan(birth=null).readyForReview)
        assertFalse(scan(birth="1991-02-31").readyForReview)
    }
}
