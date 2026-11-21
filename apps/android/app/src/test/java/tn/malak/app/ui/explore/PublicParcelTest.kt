package tn.malak.app.ui.explore

import org.junit.Assert.*
import org.junit.Test

class PublicParcelTest {
    @Test fun estimateNeverUsesOfficialStyle() {
        assertEquals(BoundaryKind.Official, BoundaryKind.read("ATLAS_OFFICIAL_BOUNDARY"))
        assertEquals(BoundaryKind.Estimate, BoundaryKind.read("OWNER_TRACED_ESTIMATE"))
        assertEquals(BoundaryKind.Georeferenced, BoundaryKind.read("MALAK_DOC_GEOREFERENCED"))
        assertNull(BoundaryKind.read("DOCUMENT_ONLY"))
        assertNull(BoundaryKind.read("NEW_SERVER_METHOD"))
    }

    @Test fun measurementsCannotCreateAGeographicViewport() {
        assertNull(parcelCamera(emptyList()))
        val ring = listOf(DiscoveryPoint(36.8, 11.0), DiscoveryPoint(36.801, 11.001))
        val camera = parcelCamera(ring)!!
        assertEquals(36.8005, camera.center.latitude, 0.0000001)
        assertEquals(11.0005, camera.center.longitude, 0.0000001)
        assertTrue(ring.all { it in camera.bounds })
    }

    @Test fun onlyTheExpectedListingDocumentRouteCanBeLoaded() {
        val id = "listing-id"
        val doc = "document-id"
        val route = "/api/v1/listings/$id/documents/$doc"
        assertEquals("https://example.test$route", documentUrl("https://example.test",id,doc,route))
        assertNull(documentUrl("https://example.test",id,doc,"https://elsewhere.test$route"))
        assertNull(documentUrl("https://example.test",id,doc,"/api/v1/listings/another/documents/$doc"))
        assertNull(documentUrl("https://example.test",id,doc,"$route?anything"))
    }
}
