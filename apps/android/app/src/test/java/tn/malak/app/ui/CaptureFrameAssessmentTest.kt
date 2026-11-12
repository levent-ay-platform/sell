package tn.malak.app.ui

import org.junit.Assert.*
import org.junit.Test

class CaptureFrameAssessmentTest {
    private val cols=160
    private val rows=112
    private fun plain(value:Int)=IntArray(cols*rows){value}
    private fun card(clearFields:Boolean=true,left:Int=16,right:Int=144,
                     top:Int=17,bottom:Int=96):IntArray {
        val p=plain(40)
        for(y in top..bottom)for(x in left..right)p[y*cols+x]=215
        if(clearFields) {
            for(zone in CaptureFrameAssessment.zones){
                val x0=(left+(right-left)*zone.x0).toInt()+2
                val x1=(left+(right-left)*zone.x1).toInt()-2
                val y0=(top+(bottom-top)*zone.y0).toInt()+2
                val y1=(top+(bottom-top)*zone.y1).toInt()-2
                for(y in y0..y1 step 3)for(x in x0..x1)
                    p[y*cols+x]=if((x/4)%2==0)40 else 215
            }
        }
        return p
    }
    private fun result(p:IntArray,last:IntArray?=null)=
        CaptureFrameAssessment.inspect(p,cols,rows,last)
    @Test fun darkAndGlareFailClosed(){
        assertEquals(CaptureHint.DARK,result(plain(9)).hint)
        assertEquals(CaptureHint.GLARE,result(plain(255)).hint)
    }
    @Test fun blankOrUnframedTextureNeverPass(){
        assertNotEquals(CaptureHint.GOOD,result(plain(155)).hint)
        val texture=IntArray(cols*rows){i->(85+i*17%115)}
        assertNotEquals(CaptureHint.GOOD,result(texture).hint)
    }
    @Test fun trueSyntheticRectangleAndFourSharpFieldsCanCapture(){
        val image=card()
        val quality=result(image,image.copyOf())
        assertEquals(CaptureHint.GOOD,quality.hint)
        assertNotNull(quality.bounds)
        assertEquals(4,quality.zones.size)
        assertTrue(quality.zones.all{it})
        assertEquals(1,quality.bounds!!.let{ if(it.left<it.right&&it.top<it.bottom) 1 else 0 })
    }
    @Test fun rectangleWithoutTextStillRequiresFocus(){
        val sample=card(clearFields=false)
        assertEquals(CaptureHint.FOCUS,result(sample).hint)
    }
    @Test fun movingImageIsBlockedBeforeCapture(){
        val sample=card()
        assertEquals(CaptureHint.MOVING,result(sample,plain(9)).hint)
    }
    @Test fun malformedInputNeverPasses(){
        assertEquals(CaptureHint.WAIT,
            CaptureFrameAssessment.evaluate(intArrayOf(1),cols,rows,null))
    }
}
