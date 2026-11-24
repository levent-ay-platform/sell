package tn.malak.app

import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tn.malak.app.ui.PrivateCaptureMemory
import tn.malak.app.ui.parcel.decodeParcelPage
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PrivatePlanRestoreTest {
    @Test fun restoredPrivatePdfIsSeekableAndRevocable()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val raw=ByteArrayOutputStream().use { out->
            PdfDocument().use { pdf->
                for(i in 1..2){
                    val page=pdf.startPage(PdfDocument.PageInfo.Builder(300+i*50,500,i).create())
                    page.canvas.drawColor(android.graphics.Color.WHITE)
                    pdf.finishPage(page)
                }
                pdf.writeTo(out)
            }
            out.toByteArray()
        }
        val uri=PrivateCaptureMemory.addDocument(raw,"application/pdf")
        try{
            assertEquals("application/pdf",context.contentResolver.getType(uri))
            val page2=decodeParcelPage(context.contentResolver,uri,"application/pdf",2)
            try{assertEquals(400,page2.frame.width);assertEquals(500,page2.frame.height)}
            finally{page2.bitmap.recycle()}
            val page1=decodeParcelPage(context.contentResolver,uri,"application/pdf",1)
            try{assertEquals(350,page1.frame.width)}
            finally{page1.bitmap.recycle()}
        }finally{PrivateCaptureMemory.discard(uri)}
        assertNull(PrivateCaptureMemory.length(uri))
        assertTrue(raw.all{it==0.toByte()})
        assertTrue(runCatching{context.contentResolver.openFileDescriptor(uri,"r")?.close()}.isFailure)
    }
}
