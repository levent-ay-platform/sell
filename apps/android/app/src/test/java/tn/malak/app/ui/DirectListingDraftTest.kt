package tn.malak.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectListingDraftTest {
    private val complete = DirectListingDraft(
        "LAND", "Terrain de test", "Kélibia", "١٢٨٠٠٠", "420", "", "36.870", "11.100"
    )

    @Test
    fun stageRequiresOnlyItsOwnInputsUntilFinalReview() {
        val draft = complete.copy(price = "", title = "")
        assertTrue(draft.canContinue(DirectListingStage.Kind))
        assertTrue(draft.canContinue(DirectListingStage.Location))
        assertFalse(draft.canContinue(DirectListingStage.Details))
        assertFalse(draft.canContinue(DirectListingStage.Preview))
        assertTrue(complete.canContinue(DirectListingStage.Preview))
    }

    @Test
    fun restoresAtFirstIncompleteStageAndAcceptsLocalizedNumbers() {
        assertEquals(DirectListingStage.Kind, complete.copy(kind = "OTHER").resume(DirectListingStage.Preview))
        assertEquals(DirectListingStage.Location, complete.copy(latitude = "").resume(DirectListingStage.Preview))
        assertEquals(DirectListingStage.Details, complete.copy(area = "0").resume(DirectListingStage.Preview))
        assertEquals(DirectListingStage.Preview, complete.resume(DirectListingStage.Preview))
    }
}
