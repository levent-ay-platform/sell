package tn.malak.app.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp

/** Synthetic intensity traces only; never real CIN pixels or text. */
class TunisianNameRowsTest {
    private fun trace(vararg centers:Double):DoubleArray{
        return DoubleArray(760){y->
            centers.sumOf{center->
                .55*exp(-.5*(y-760*center)*(y-760*center)/(8.0*8.0))
            }
        }
    }
    @Test fun fixedFieldsWouldMissShiftedRowsButAdaptivePeaksFollowThem(){
        val a=TunisianNameRows.locate(trace(.520,.614,.715))
        assertNotNull(a)
        assertEquals(395,a!!.surnameCenter,4)
        assertEquals(467,a.givenCenter,4)
        val b=TunisianNameRows.locate(trace(.559,.655,.735))
        assertNotNull(b)
        assertEquals(425,b!!.surnameCenter,4)
        assertEquals(498,b.givenCenter,4)
    }
    @Test fun ignoreFatherRowAndRejectMissingPrintedGivenName(){
        val a=TunisianNameRows.locate(trace(.512,.597,.682))
        assertNotNull(a)
        assertEquals(389,a!!.surnameCenter,4)
        assertEquals(454,a.givenCenter,4)
        assertNull(TunisianNameRows.locate(trace(.52)))
        assertNull(TunisianNameRows.locate(DoubleArray(760)))
    }
    @Test fun rejectFieldsOutsideExpectedCinFrontBands(){
        assertNull(TunisianNameRows.locate(trace(.22,.32)))
        assertNull(TunisianNameRows.locate(trace(.80,.90)))
    }
}
