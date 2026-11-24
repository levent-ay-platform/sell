package tn.malak.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tn.malak.app.ui.parcel.OwnerBoundaryDraftStore
import tn.malak.app.ui.explore.DiscoveryPoint
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OwnerBoundaryDraftStoreTest{
    @Test fun savedPolygonSurvivesReopenAndScopeIsIsolated(){
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val scope=UUID.randomUUID().toString()
        val first=OwnerBoundaryDraftStore(ctx,scope)
        val other=OwnerBoundaryDraftStore(ctx,scope+"-other")
        val points=listOf(DiscoveryPoint(36.85,11.10),DiscoveryPoint(36.85,11.1003),
            DiscoveryPoint(36.8502,11.1003),DiscoveryPoint(36.8502,11.10))
        try{
            first.save(points)
            assertEquals(points,OwnerBoundaryDraftStore(ctx,scope).read())
            assertTrue(other.read().isEmpty())
            first.clear()
            assertTrue(OwnerBoundaryDraftStore(ctx,scope).read().isEmpty())
        }finally{first.clear();other.clear()}
    }
}
