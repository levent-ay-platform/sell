package tn.malak.app.ui.parcel
import org.junit.Assert.*
import org.junit.Test
import tn.malak.app.ui.explore.DiscoveryPoint
class OwnerMapMeasurementTest{
    @Test fun measuresSmallPolygonAndRejectsCrossing(){
        val a=DiscoveryPoint(36.85,11.10)
        val b=DiscoveryPoint(36.85,11.1003)
        val c=DiscoveryPoint(36.8502,11.1003)
        val d=DiscoveryPoint(36.8502,11.10)
        val m=measureOwnerMap(listOf(a,b,c,d))!!
        assertTrue(m.areaM2 in 550.0..650.0)
        assertEquals(4,m.lengthsM.size)
        assertNull(measureOwnerMap(listOf(a,c,b,d)))
        assertNull(measureOwnerMap(listOf(a,b,b,d)))
        assertNull(measureOwnerMap(listOf(a,b,c,DiscoveryPoint(0.0,0.0))))
    }
}
