package tn.malak.app.ui
import org.junit.Assert.*
import org.junit.Test
class DocumentPagePolicyTest {
    @Test fun replacingAndRemovingOnePageRetainItsNeighbours() {
        val pages=listOf("first","second","third")
        assertEquals(listOf("first","third"),removeDocumentPage(pages,1))
        assertEquals(listOf("replacement","second","third"),replaceDocumentPage(pages,0,"replacement"))
        assertEquals(listOf("first","third","second"),moveDocumentPage(pages,1,2))
        assertEquals(listOf("first","second","third"),pages)
    }
}
