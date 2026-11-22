package tn.malak.app.ui.explore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndustrialCatalogTest {
    @Test
    fun opensDocumentsOnlyFromTheAgencyPublicDocumentHost() {
        val official = "https://www.afi.nat.tn/wp-content/uploads/2023/12/CC-Ariana-Airoport-1997_compressed.pdf"
        assertEquals(official, industrialDocumentUrl(official, pdf = true))
        assertNull(industrialDocumentUrl("https://atlas.ayasofia.shop/api/map/afi/documents/file.pdf", pdf = true))
        assertNull(industrialDocumentUrl("https://www.afi.nat.tn.example/wp-content/uploads/file.pdf", pdf = true))
        assertNull(industrialDocumentUrl("https://www.afi.nat.tn/wp-content/uploads/file.pdf?redirect=1", pdf = true))
        assertNull(industrialDocumentUrl("http://www.afi.nat.tn/wp-content/uploads/file.pdf", pdf = true))
    }
}
