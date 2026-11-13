package tn.malak.app.ui

import org.junit.Assert.*
import org.junit.Test

class CameraGuideTransformTest {
    private val crop=PixelRect(100,40,700,1160)
    @Test fun rotationsUsePhysicalAxesNeverRtlMirror(){
        assertEquals(crop,
            CameraGuideTransform.rotatedViewport(900,1400,0,crop))
        assertEquals(PixelRect(240,100,1360,700),
            CameraGuideTransform.rotatedViewport(900,1400,90,crop))
        assertEquals(PixelRect(200,240,800,1360),
            CameraGuideTransform.rotatedViewport(900,1400,180,crop))
        assertEquals(PixelRect(40,200,1160,800),
            CameraGuideTransform.rotatedViewport(900,1400,270,crop))
    }
    @Test fun guideIsContainedInTheActualCroppedPreview(){
        val view=CameraGuideTransform.rotatedViewport(
            900,1400,90,crop)!!
        val guide=CameraGuideTransform.guideRect(view)!!
        assertTrue(guide.left>=view.left&&guide.right<=view.right)
        assertTrue(guide.top>=view.top&&guide.bottom<=view.bottom)
        assertEquals(.84f,guide.width.toFloat()/view.width,.01f)
        assertEquals(1.585f,guide.width.toFloat()/guide.height,.03f)
    }
    @Test fun invalidRotationOrCropFailsClosed(){
        assertNull(CameraGuideTransform.rotatedViewport(
            900,1400,45,crop))
        assertNull(CameraGuideTransform.rotatedViewport(
            900,1400,90,PixelRect(-1,0,800,1000)))
        assertNull(CameraGuideTransform.guideRect(PixelRect(0,0,30,25)))
    }
}
