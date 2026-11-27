package tn.malak.app.ui
import org.junit.Assert.assertEquals
import org.junit.Test
class OwnerIntakePolicyTest {
    @Test fun restoredAndNewIntakeUseOnePage(){
        assertEquals(0,OwnerIntakePolicy.displayStep(0))
        assertEquals(0,OwnerIntakePolicy.displayStep(1))
        assertEquals(2,OwnerIntakePolicy.next(0))
        assertEquals(2,OwnerIntakePolicy.next(1))
        assertEquals(0,OwnerIntakePolicy.previous(2))
        assertEquals(1,OwnerIntakePolicy.displayStep(2))
        assertEquals(3,OwnerIntakePolicy.next(2))
        assertEquals(2,OwnerIntakePolicy.previous(3))
    }
}
