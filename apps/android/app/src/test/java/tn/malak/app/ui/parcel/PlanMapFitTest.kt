package tn.malak.app.ui.parcel

import org.junit.Assert.*
import org.junit.Test
import tn.malak.app.ui.explore.DiscoveryPoint

class PlanMapFitTest{
    @Test fun requiresIndependentCheckAndRejectsWarp(){
        val raw=listOf(ParcelPoint(0.0,0.0),ParcelPoint(30.0,0.0),ParcelPoint(30.0,20.0),ParcelPoint(0.0,20.0))
        val indices=planControlIndices(raw)
        assertEquals(4,indices.size)
        val all=raw.map{DiscoveryPoint(36.85-it.y/111195.0,11.10+it.x/88900.0)}
        val controls=indices.map{all[it]}
        val fit=fitPlanToMap(raw,indices,controls)!!
        assertTrue(fit.checkErrorM<.1)
        assertTrue(measureOwnerMap(fit.points)!!.areaM2 in 590.0..610.0)
        assertNull(fitPlanToMap(raw,indices,controls.dropLast(1)))
        assertNull(fitPlanToMap(raw,indices,controls.dropLast(1)+DiscoveryPoint(36.855,11.10)))
        assertTrue(planControlIndices(raw.take(3)).isEmpty())
        val warped=indices.map{DiscoveryPoint(36.85-raw[it].y/111195.0,11.10+raw[it].x/30000.0)}
        assertNull(fitPlanToMap(raw,indices,warped))
    }
}
