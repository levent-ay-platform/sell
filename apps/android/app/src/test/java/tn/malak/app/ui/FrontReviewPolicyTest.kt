package tn.malak.app.ui

import org.junit.Assert.*
import org.junit.Test

class FrontReviewPolicyTest {
    @Test fun pilotCannotDisplayFakeUnreadableFields(){
        assertFalse(FrontReviewPolicy.showFields(false,false,false,true,false))
        assertFalse(FrontReviewPolicy.showFields(false,true,false,true,true))
    }
    @Test fun consentAndRealReplyRequired(){
        assertFalse(FrontReviewPolicy.showFields(true,false,false,true,true))
        assertFalse(FrontReviewPolicy.showFields(true,true,true,true,true))
        assertFalse(FrontReviewPolicy.showFields(true,true,false,false,true))
        assertFalse(FrontReviewPolicy.showFields(true,true,false,true,false))
        assertTrue(FrontReviewPolicy.showFields(true,true,false,true,true))
    }
}
