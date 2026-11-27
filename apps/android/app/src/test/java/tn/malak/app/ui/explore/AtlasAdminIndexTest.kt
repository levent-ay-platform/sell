package tn.malak.app.ui.explore

import org.junit.Assert.*
import org.junit.Test

class AtlasAdminIndexTest {
    private fun square(w:Double,s:Double,e:Double,n:Double)=listOf(
        DiscoveryPoint(s,w),DiscoveryPoint(s,e),DiscoveryPoint(n,e),DiscoveryPoint(n,w),DiscoveryPoint(s,w))
    @Test fun polygonHolesAndBoundaryAreNotInside(){
        val p=AdminPolygon(listOf(square(10.0,35.0,11.0,36.0),square(10.2,35.2,10.4,35.4)))
        assertEquals(1,p.locate(DiscoveryPoint(35.7,10.7)))
        assertEquals(0,p.locate(DiscoveryPoint(35.3,10.3)))
        assertEquals(2,p.locate(DiscoveryPoint(35.7,10.0)))
        assertEquals(0,p.locate(DiscoveryPoint(36.1,10.7)))
    }
    @Test fun searchCameraUsesAdministrativeBoundsAndNeverParcelZoom(){
        val g=AdminFeature(AdminName("G","نابل","Nabeul"),null,listOf(AdminPolygon(listOf(square(10.0,35.0,11.0,36.0)))))
        val d=AdminFeature(AdminName("D","الحمامات","Hammamet"),"G",listOf(AdminPolygon(listOf(square(10.1,35.2,10.4,35.6)))))
        val index=AtlasAdminIndex(listOf(g),listOf(d))
        val context=index.resolveNames("نابل","الحمامات",null)!!
        val camera=index.searchCamera(context)!!
        assertEquals(10.25,camera.center.longitude,0.0001)
        assertEquals(35.4,camera.center.latitude,0.0001)
        assertEquals(10.1,camera.bounds.west,0.0001)
        assertTrue(camera.zoom<=13.0)
        assertNull(index.resolveNames("نابل","unknown",null))
    }
    @Test fun overlappingDelegationsDoNotInventOne(){
        val g=AdminFeature(AdminName("G","ولاية","G"),null,listOf(AdminPolygon(listOf(square(10.0,35.0,11.0,36.0)))))
        val d1=AdminFeature(AdminName("D1","أ","A"),"G",listOf(AdminPolygon(listOf(square(10.0,35.0,10.7,36.0)))))
        val d2=AdminFeature(AdminName("D2","ب","B"),"G",listOf(AdminPolygon(listOf(square(10.5,35.0,11.0,36.0)))))
        val index=AtlasAdminIndex(listOf(g),listOf(d1,d2))
        assertEquals("D1",index.resolve(DiscoveryPoint(35.5,10.2))?.delegation?.id)
        assertNull(index.resolve(DiscoveryPoint(35.5,10.6))?.delegation)
        assertNull(index.resolveSamples(listOf(DiscoveryPoint(35.5,10.2),DiscoveryPoint(35.5,10.9)))?.delegation)
        assertNull(index.resolve(DiscoveryPoint(34.0,9.0)))
    }
}
