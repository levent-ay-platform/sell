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
    private data class ScanPage(
        val page:Int,val text:String,val confidence:Int,
        val spatialNames:List<PairNameEvidence> = emptyList(),
        val certificateFields:TunisianDocumentFields.Certificate?=null,
        val identityFields:TunisianDocumentFields.Identity?=null
    ) {
        val source:String get() = if(text.any{it in '\u0600'..'\u06ff'}) "ocr-ar" else "ocr-fr"
    }
    private fun labelCandidates(pages:List<ScanPage>,identity:Boolean):List<PairNameEvidence> {
        return pages.flatMap { page->
            val names=if(identity)TunisianNameParser.identity(page.text)
                else TunisianNameParser.certificate(page.text)
            // A result from a physically isolated printed field is permitted
            // alongside explicit label candidates. Contradictions stay visible
            // to oneName() and force an ambiguous result.
            (names.map{PairNameEvidence(it,page.page,page.source,page.confidence)}+
                page.spatialNames).distinctBy{TunisianNameParser.canonical(it.value)}
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
    /** Region templates for an upright Tunisian CIN or CPF page. Read-only
     * in-memory crops, never disk caching. The full-image fallback covers
     * unrecognized layouts; it cannot override conflicting field readings.
     */
    private fun region(bitmap:Bitmap,left:Float,top:Float,right:Float,
                       bottom:Float,grayscale:Boolean=true):Bitmap {
        val x=(bitmap.width*left).toInt().coerceIn(0,bitmap.width-2)
        val y=(bitmap.height*top).toInt().coerceIn(0,bitmap.height-2)
        val w=(bitmap.width*right).toInt().minus(x).coerceIn(2,bitmap.width-x)
        val h=(bitmap.height*bottom).toInt().minus(y).coerceIn(2,bitmap.height-y)
        val crop=Bitmap.createBitmap(bitmap,x,y,w,h)
        if(!grayscale)return crop
        val output=Bitmap.createBitmap(crop.width,crop.height,Bitmap.Config.ARGB_8888)
        try{
            val gray=ColorMatrix().apply{setSaturation(0f)}
            val paint=Paint(Paint.FILTER_BITMAP_FLAG).apply{
                colorFilter=ColorMatrixColorFilter(gray)
            }
            Canvas(output).apply{
                drawColor(Color.WHITE)
                drawBitmap(crop,0f,0f,paint)
            }
        }finally{crop.recycle()}
        return output
    }
    private fun readText(api:TessBaseAPI,bitmap:Bitmap):ScanPage {
        api.clear()
        api.setImage(bitmap)
        return ScanPage(1,api.getUTF8Text().orEmpty().take(60000),
            api.meanConfidence().coerceIn(0,100))
    }
    private fun ocrSingle(api:TessBaseAPI,bitmap:Bitmap,index:Int,
                          identity:Boolean):ScanPage {
        fun readBox(l:Float,t:Float,r:Float,b:Float,gray:Boolean=true):ScanPage {
            val crop=region(bitmap,l,t,r,b,gray)
            return try{readText(api,crop)}finally{crop.recycle()}
        }
        try {
            if(identity){
                // Labelled surname and given-name field first; if its layout
                // cannot be read, two narrow rows provide independent evidence.
                val nameBlock=readBox(.49f,.35f,.98f,.68f)
                var surnameText=""
                var givenText=""
                var surnameConfidence=0
                var givenConfidence=0
                if(TunisianNameParser.identity(nameBlock.text).isEmpty()){
                    val s=readBox(.55f,.40f,.98f,.55f)
                    val g=readBox(.55f,.52f,.98f,.66f)
                    surnameText=s.text;givenText=g.text
                    surnameConfidence=s.confidence;givenConfidence=g.confidence
                }
                val numberBand=readBox(.28f,.24f,.77f,.39f)
                val birthBand=readBox(.37f,.67f,.98f,.83f)
                var fields=TunisianDocumentFields.identity(
                    nameBlock.text,surnameText,givenText,
                    numberBand.text,birthBand.text)
                var text=nameBlock.text
                var confidence=nameBlock.confidence
                if(fields.name==null) {
                    // Single bounded fallback. No guessing from father-name row.
                    val full=readText(api,bitmap)
                    val fallback=TunisianDocumentFields.identity(
                        nameBlock.text+"\n"+full.text,
                        surnameText,givenText,numberBand.text,birthBand.text)
                    fields=fallback
                    text=full.text+"\n"+nameBlock.text
                    confidence=minOf(full.confidence,nameBlock.confidence)
                }else if(surnameText.isNotEmpty()&&givenText.isNotEmpty()){
                    confidence=minOf(surnameConfidence,givenConfidence)
                }
                val names=fields.name?.let{listOf(PairNameEvidence(
                    it,index,"ocr-ar",confidence))}.orEmpty()
                return ScanPage(index,text,confidence,names,identityFields=fields)
            }
            // Certificate header, registered-owner area and page footer are
            // much smaller than a whole A4 scan. Query them independently.
            val ownerZone=readBox(.39f,.43f,.98f,.77f)
            val topFields=readBox(.12f,.15f,.97f,.38f)
            val footer=readBox(.07f,.84f,.97f,.96f)
            var all=topFields.text+"\n"+ownerZone.text+"\n"+footer.text
            var fields=TunisianDocumentFields.certificate(
                all,ownerZone.text,topFields.text,footer.text)
            var confidence=ownerZone.confidence
            if(fields.owner==null){
                val full=readText(api,bitmap)
                all=all+"\n"+full.text
                fields=TunisianDocumentFields.certificate(
                    all,ownerZone.text,topFields.text+"\n"+full.text,
                    footer.text+"\n"+full.text)
                confidence=minOf(confidence,full.confidence)
            }
            val names=fields.owner?.let{listOf(PairNameEvidence(
                it,index,"ocr-ar",confidence))}.orEmpty()
            return ScanPage(index,all,confidence,names,certificateFields=fields)
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
            val minimumConfidence=minOf(owner?.confidence?:0,person?.confidence?:0)
            val missingCertificatePages=cert.mapNotNull{ page->
                page.certificateFields?.expectedPages
                    ?:TunisianNameParser.declaredPages(page.text)
            }.any{it>cert.size}
            val ambiguity=(certCandidates.size>1 && owner==null)||
                (identityCandidates.size>1&&person==null)
            val assessment=TunisianNameParser.assess(
                if(!enoughImages||ambiguity)null else owner?.value,
                person?.value,minimumConfidence,missingCertificatePages)
            val reasons=mutableListOf("OFFICIAL_CPF_AND_DOCUMENT_AUTHENTICITY_NOT_CHECKED")
            if(missingCertificatePages)reasons.add("CERTIFICATE_PAGES_MISSING")
            if(certCandidates.size>1&&owner==null)
                reasons.add("MULTIPLE_OWNERS_OR_SHARED_TITLE_NEEDS_REVIEW")
            if(owner==null||person==null||ambiguity)
                reasons.add("MISSING_OR_AMBIGUOUS_LABELED_NAME")
            if(minimumConfidence<92 && assessment!="PRELIMINARY_TEXT_AGREEMENT")
                reasons.add("LOW_OCR_CONFIDENCE")
            if(assessment=="TEXT_DIFFERENCE_NEEDS_REVIEW")
                reasons.add("NAME_TEXT_DIFFERS_OR_SCRIPT_MISMATCH")
            val land=cert.mapNotNull{it.certificateFields}.firstOrNull()
            val card=id.mapNotNull{it.identityFields}.firstOrNull()
            val localFields=LocalOcrFields(
                titleNumber=land?.titleNumber,areaM2=land?.areaM2,
                expectedCertificatePages=land?.expectedPages,
                cinLast4=card?.cinNumber?.takeLast(4),
                birthDate=card?.dateOfBirth)
            CertificateIdentityReview(
                assessment,owner,person,"NOT_COMPARABLE",reasons,
                if(identity.size==2)"TWO_FILES_UNCONFIRMED"
                else if(id.size>=2)"MULTI_PAGE_UNCONFIRMED" else "FRONT_ONLY",
                cert.size,id.size,
                ownershipVerified=false,identityVerified=false,mayPublish=false,
                localFields=localFields
            )
        }finally { api.recycle() }
    }
}
