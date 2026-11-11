package tn.malak.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Genuine OFFLINE OCR for the first-loop QA APK. Does not use the pilot token,
 * HTTP, Huawei, or any remote server. Document bytes and recognized names never
 * leave this Android process. No image/text cache is written. Only OCR language
 * models are installed permanently in app-private storage.
 *
 * All comparisons are PRELIMINARY TEXT checks; this does not validate a CIN,
 * a land certificate, current ownership, CPF registry, or authority to sell.
 */
internal object LocalDocumentOcr {
    private const val MAX_BYTES = 12L * 1024L * 1024L
    private const val MAX_IMAGE_EDGE = 2400
    private const val MAX_PAGES = 8
    // Keep document-specific labelled-name extraction independent of OCR
    // engine details. Never match a father name/date or invented OCR letters.
    private data class ScanPage(val page:Int,val text:String,val confidence:Int) {
        val source:String get() = if(text.any{it in '\u0600'..'\u06ff'}) "ocr-ar" else "ocr-fr"
    }
    private fun labelCandidates(pages:List<ScanPage>,identity:Boolean):List<PairNameEvidence> {
        return pages.flatMap { page->
            val names=if(identity)TunisianNameParser.identity(page.text)
                else TunisianNameParser.certificate(page.text)
            names.map{PairNameEvidence(it,page.page,page.source,page.confidence)}
        }
    }
    private fun normalized(value:String):String=TunisianNameParser.canonical(value)
    private fun oneName(candidates:List<PairNameEvidence>):PairNameEvidence? {
        return candidates.firstOrNull()?.takeIf { first->
            candidates.all { normalized(it.value)==normalized(first.value) }
        }
    }

    private fun ensureModels(context:Context):String {
        val root=File(context.noBackupFilesDir,"malak-ocr")
        val folder=File(root,"tessdata")
        if(!folder.exists() && !folder.mkdirs())throw IllegalStateException("OCR_MODEL_STORAGE")
        for(lang in listOf("ara","fra")) {
            val target=File(folder,lang+".traineddata")
            val asset="tessdata/"+lang+".traineddata"
            // Recheck every model on each run, never trust an empty or truncated copy.
            if(!target.exists() || target.length()<100000L) {
                val pending=File(folder,lang+".staging")
                try {
                    context.assets.open(asset).use { source ->
                        pending.outputStream().use { out ->
                            val buffer=ByteArray(32768)
                            var total=0L
                            while(true){
                                val n=source.read(buffer)
                                if(n<0)break
                                total+=n
                                if(total>20L*1024L*1024L)throw IllegalStateException("OCR_MODEL_TOO_LARGE")
                                out.write(buffer,0,n)
                            }
                            buffer.fill(0)
                        }
                    }
                    if(pending.length()<100000L || !pending.renameTo(target))
                        throw IllegalStateException("OCR_MODEL_INVALID")
                }finally{pending.delete()}
            }
        }
        return root.absolutePath
    }
    private fun decodeImage(context:Context,uri:Uri):Bitmap {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        val boundsInput=context.contentResolver.openInputStream(uri)
            ?:throw IllegalStateException("OCR_IMAGE_UNAVAILABLE")
        boundsInput.use { BitmapFactory.decodeStream(it,null,bounds) }
        if(bounds.outWidth !in 40..12000 || bounds.outHeight !in 40..12000 ||
            bounds.outWidth.toLong()*bounds.outHeight>100000000L)
            throw IllegalStateException("OCR_IMAGE_DIMENSIONS")
        val opts=BitmapFactory.Options().apply {
            inSampleSize=generateSequence(1){it*2}.first {
                bounds.outWidth/it<=MAX_IMAGE_EDGE &&
                bounds.outHeight/it<=MAX_IMAGE_EDGE
            }
            inPreferredConfig=Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use{
            BitmapFactory.decodeStream(it,null,opts)
        }?:throw IllegalStateException("OCR_IMAGE_UNAVAILABLE")
    }
    /** Preprocess only the name region of a CIN; document pixels stay in RAM.
     * This is much smaller than an entire photo and avoids the portrait,
     * watermark and unrelated birth/father data confusing Arabic OCR.
     */
    private fun nameRegion(bitmap:Bitmap,isIdentity:Boolean):Bitmap {
        val left=(bitmap.width*(if(isIdentity)0.50f else 0.40f)).toInt()
        val top=(bitmap.height*(if(isIdentity)0.32f else 0.40f)).toInt()
        val right=(bitmap.width*0.97f).toInt()
        val bottom=(bitmap.height*(if(isIdentity)0.72f else 0.78f)).toInt()
        val cropped=Bitmap.createBitmap(bitmap,left,top,
            (right-left).coerceAtLeast(1),(bottom-top).coerceAtLeast(1))
        val prepared=Bitmap.createBitmap(cropped.width,cropped.height,
            Bitmap.Config.ARGB_8888)
        try {
            val mono=ColorMatrix().apply{setSaturation(0f)}
            val paint=Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter=ColorMatrixColorFilter(mono)
            }
            Canvas(prepared).apply{
                drawColor(Color.WHITE)
                drawBitmap(cropped,0f,0f,paint)
            }
        }finally{cropped.recycle()}
        return prepared
    }
    private fun readText(api:TessBaseAPI,bitmap:Bitmap):ScanPage {
        api.clear()
        api.setImage(bitmap)
        return ScanPage(1,api.getUTF8Text().orEmpty().take(60000),
            api.meanConfidence().coerceIn(0,100))
    }
    private fun ocrSingle(api:TessBaseAPI,bitmap:Bitmap,index:Int,
                          identity:Boolean):ScanPage {
        try {
            // For title certificates, start with full text to preserve page
            // counts and joint-owner warnings. For CIN, scan only printed
            // owner-name area first. Retry full image only if labels were lost.
            if(identity){
                val crop=nameRegion(bitmap,true)
                val first=try{readText(api,crop)}finally{crop.recycle()}
                if(TunisianNameParser.identity(first.text).isNotEmpty())
                    return first.copy(page=index)
                val full=readText(api,bitmap)
                val merged=if(full.text.length+first.text.length<60000)
                    first.text+"\n"+full.text else full.text
                return ScanPage(index,merged,minOf(first.confidence,full.confidence))
            }
            val full=readText(api,bitmap)
            if(TunisianNameParser.certificate(full.text).isNotEmpty())
                return full.copy(page=index)
            val crop=nameRegion(bitmap,false)
            val focus=try{readText(api,crop)}finally{crop.recycle()}
            return ScanPage(index,full.text+"\n"+focus.text,
                minOf(full.confidence,focus.confidence))
        }finally{bitmap.recycle()}
    }
    private suspend fun scanInput(context:Context,api:TessBaseAPI,
        files:List<PairEvidenceFile>,identity:Boolean):List<ScanPage>{
        val output=mutableListOf<ScanPage>()
        for((position,file) in files.withIndex()) {
            currentCoroutineContext().ensureActive()
            if(file.expectedBytes !in 20..MAX_BYTES ||file.uri.scheme!="content")
                throw IllegalStateException("OCR_FILE_REJECTED")
            if(file.mime=="application/pdf") {
                if(files.size!=1)throw IllegalStateException("OCR_PDF_ORDER")
                // SAF grants access to this exact URI only; never copy it to a
                // public folder or try to discover other document locations.
                val fd=context.contentResolver.openFileDescriptor(file.uri,"r")
                    ?:throw IllegalStateException("OCR_PDF_UNAVAILABLE")
                PdfRenderer(fd).use { pdf ->
                    if(pdf.pageCount !in 1..MAX_PAGES)
                        throw IllegalStateException("OCR_PDF_PAGES")
                    for(i in 0 until pdf.pageCount) {
                        currentCoroutineContext().ensureActive()
                        pdf.openPage(i).use { page ->
                            val factor=(MAX_IMAGE_EDGE.toFloat()/maxOf(page.width,page.height)).coerceAtMost(2.0f)
                            val w=(page.width*factor).toInt().coerceAtLeast(60)
                            val h=(page.height*factor).toInt().coerceAtLeast(60)
                            val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                            Canvas(bmp).drawColor(Color.WHITE)
                            try {
                                page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                output.add(ocrSingle(api,bmp,i+1,identity))
                            }catch(t:Throwable){
                                if(!bmp.isRecycled)bmp.recycle()
                                throw t
                            }
                        }
                    }
                }
            } else {
                if(file.mime !in setOf("image/jpeg","image/png","image/tiff")||
                    files.size>MAX_PAGES)throw IllegalStateException("OCR_FILE_TYPE")
                output.add(ocrSingle(api,decodeImage(context,file.uri),position+1,identity))
            }
        }
        return output
    }

    suspend fun readAndCompare(context:Context,
        certificates:List<PairEvidenceFile>,identity:List<PairEvidenceFile>,
        onProgress:(PairProgress)->Unit={}
    ):CertificateIdentityReview=withContext(Dispatchers.IO) {
        if(certificates.isEmpty()||identity.isEmpty()||
            certificates.size>MAX_PAGES||identity.size>2||
            (certificates.size>1&&certificates.any{it.mime=="application/pdf"})||
            (identity.size==2&&identity.any{it.mime=="application/pdf"})||
            (certificates+identity).map{it.uri}.distinct().size!=certificates.size+identity.size)
            throw IllegalStateException("OCR_DOCUMENT_SELECTION")
        val api=TessBaseAPI()
        try {
            // French OCR includes Latin-script names; two models initialize
            // faster than running Arabic, French and English simultaneously.
            if(!api.init(ensureModels(context),"ara+fra"))
                throw IllegalStateException("OCR_ENGINE_UNAVAILABLE")
            withContext(Dispatchers.Main){onProgress(PairProgress.CERTIFICATE_UPLOAD)}
            val cert=scanInput(context,api,certificates,false)
            withContext(Dispatchers.Main){onProgress(PairProgress.IDENTITY_UPLOAD)}
            val id=scanInput(context,api,identity,true)
            withContext(Dispatchers.Main){onProgress(PairProgress.COMPARING)}
            val certCandidates=labelCandidates(cert,false)
            val identityCandidates=labelCandidates(id,true)
            val owner=oneName(certCandidates)
            val person=oneName(identityCandidates)
            // A front-facing CIN is enough for this first-stage *text*
            // comparison. The back is optional and not proof of identity.
            val enoughImages=id.isNotEmpty() && cert.isNotEmpty()
            // Whole-image OCR confidence includes watermarks and portraits.
            // Below 65% we cannot assert a textual mismatch; request review.
            val confident=(owner?.confidence?:0)>=65&&(person?.confidence?:0)>=65
            val missingCertificatePages=cert.mapNotNull{
                TunisianNameParser.declaredPages(it.text)}.any{it>cert.size}
            val ambiguity=(certCandidates.size>1 && owner==null)||
                (identityCandidates.size>1&&person==null)
            val same=owner!=null&&person!=null&&normalized(owner.value)==normalized(person.value)
            val assessment=when{
                owner==null||person==null||!enoughImages||!confident||
                    ambiguity||missingCertificatePages->
                    "INSUFFICIENT_OR_AMBIGUOUS"
                same->"PRELIMINARY_TEXT_AGREEMENT"
                else->"TEXT_DIFFERENCE_NEEDS_REVIEW"
            }
            val reasons=mutableListOf("OFFICIAL_CPF_AND_DOCUMENT_AUTHENTICITY_NOT_CHECKED")
            if(missingCertificatePages)reasons.add("CERTIFICATE_PAGES_MISSING")
            if(certCandidates.size>1&&owner==null)
                reasons.add("MULTIPLE_OWNERS_OR_SHARED_TITLE_NEEDS_REVIEW")
            if(owner==null||person==null||ambiguity)
                reasons.add("MISSING_OR_AMBIGUOUS_LABELED_NAME")
            if(!confident)reasons.add("LOW_OCR_CONFIDENCE")
            if(assessment=="TEXT_DIFFERENCE_NEEDS_REVIEW")
                reasons.add("NAME_TEXT_DIFFERS_OR_SCRIPT_MISMATCH")
            CertificateIdentityReview(
                assessment,owner,person,"NOT_COMPARABLE",reasons,
                if(identity.size==2)"TWO_FILES_UNCONFIRMED"
                else if(id.size>=2)"MULTI_PAGE_UNCONFIRMED" else "FRONT_ONLY",
                cert.size,id.size,
                ownershipVerified=false,identityVerified=false,mayPublish=false
            )
        }finally { api.recycle() }
    }
}
