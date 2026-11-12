package tn.malak.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureFrameAssessmentTest {
    private val cols=60
    private val rows=38
    private fun assess(a:IntArray,b:IntArray?=null)=
        CaptureFrameAssessment.evaluate(a,cols,rows,b)
    @Test fun darknessAndOverexposure(){
        assertEquals(CaptureHint.DARK,assess(IntArray(cols*rows){10}))
        assertEquals(CaptureHint.GLARE,assess(IntArray(cols*rows){255}))
    }
    @Test fun blankFrameCannotPass(){
        assertEquals(CaptureHint.TOO_FAR,assess(IntArray(cols*rows){155}))
    }
    @Test fun stableTexturedFrameCanProceedToFieldOcr(){
        val pixels=IntArray(cols*rows){i->80+(i%cols*13+i/cols*17)%136}
        assertEquals(CaptureHint.GOOD,assess(pixels,pixels.copyOf()))
        assertEquals(CaptureHint.MOVING,assess(pixels,IntArray(cols*rows){0}))
    }
    @Test fun malformedInputNeverPasses(){
        assertEquals(CaptureHint.WAIT,CaptureFrameAssessment.evaluate(intArrayOf(1),cols,rows,null))
    }
}
