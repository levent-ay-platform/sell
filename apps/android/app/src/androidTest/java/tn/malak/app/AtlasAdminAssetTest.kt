package tn.malak.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tn.malak.app.ui.explore.*

@RunWith(AndroidJUnit4::class)
class AtlasAdminAssetTest{
    @Test fun packagedReferenceResolvesCarthage(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val index=loadAtlasAdminIndex(context)
        assertEquals(24,index.governorateCount)
        assertEquals(279,index.delegationCount)
        val result=index.resolve(DiscoveryPoint(36.85928,10.328155))
        assertEquals("TN-11",result?.governorate?.id)
        assertEquals("delegation-1151",result?.delegation?.id)
    }
}
