package tn.malak.app.ui.parcel
import org.junit.Assert.*
import org.junit.Test
class LocalPlanOutlineTest {
    private fun rect(a:BooleanArray,x0:Int,y0:Int,x1:Int,y1:Int){
        for(x in x0..x1){a[y0*100+x]=true;a[y1*100+x]=true}
        for(y in y0..y1){a[y*100+x0]=true;a[y*100+x1]=true}
    }
    @Test fun closedInteriorMapsToDocumentCoordinates(){
        val pixels=BooleanArray(10000)
        rect(pixels,20,25,70,65)
        val frame=ParcelFrame(1000,2000)
        val result=LocalPlanOutline.candidates(100,100,pixels,frame)
        assertEquals(1,result.size)
        assertEquals(4,result[0].size)
        assertNotNull(frame.validatedRing(result[0]))
        assertTrue(result[0].all{it.x in 200.0..710.0 && it.y in 500.0..1320.0})
    }
    @Test fun openShapeAndPageFrameAreNotSuggested(){
        val pixels=BooleanArray(10000)
        rect(pixels,20,25,70,65);pixels[25*100+40]=false
        rect(pixels,2,2,97,97)
        assertTrue(LocalPlanOutline.candidates(100,100,pixels,ParcelFrame(100,100)).isEmpty())
    }
    @Test fun multipleShapesRemainSeparate(){
        val pixels=BooleanArray(10000)
        rect(pixels,10,10,40,40);rect(pixels,55,55,85,85)
        assertEquals(2,LocalPlanOutline.candidates(100,100,pixels,ParcelFrame(100,100)).size)
    }
}
