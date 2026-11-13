package tn.malak.app.ui

import org.junit.Assert.*
import org.junit.Test

class CardCaptureCropTest {
    @Test fun offCentreCardIsNotCut(){
        val rect=CardBounds(.17f,.30f,.80f,.65f)
        val crop=CardCaptureCrop.plan(1600,2400,rect)
        assertTrue(crop.left<=.17f*1600-25)
        assertTrue(crop.left+crop.width>=.80f*1600+25)
        assertTrue(crop.top<=.30f*2400-55)
        assertTrue(crop.top+crop.height>=.65f*2400+55)
        assertTrue(crop.left>=0&&crop.top>=0)
        assertTrue(crop.left+crop.width<=1600)
        assertTrue(crop.top+crop.height<=2400)
    }
    @Test fun malformedOrMissingBoundsUseSafeCentreCrop(){
        val fallback=CardCaptureCrop.plan(1080,1920,null)
        assertEquals(fallback,CardCaptureCrop.plan(1080,1920,
            CardBounds(Float.NaN,0f,1f,1f)))
        assertEquals(fallback,CardCaptureCrop.plan(1080,1920,
            CardBounds(.8f,.3f,.2f,.7f)))
    }
    @Test fun nonMatchingCardNearCornerIsRejected(){
        val fallback=CardCaptureCrop.plan(1080,1920,null)
        val invalid=CardCaptureCrop.plan(1080,1920,
            CardBounds(.02f,.01f,.85f,.80f))
        assertEquals(fallback,invalid)
    }
    @Test fun landscapeCameraFrameIsBounded(){
        val crop=CardCaptureCrop.plan(2400,1600,
            CardBounds(.12f,.16f,.87f,.84f))
        assertTrue(crop.width<=2400&&crop.height<=1600)
        assertTrue(crop.left+crop.width<=2400)
        assertTrue(crop.top+crop.height<=1600)
    }
}
