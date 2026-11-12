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
            // Spatial parsing can explicitly detect contradictory surname/name
            // fields and deliberately return null. Never resurrect an unsafe
            // name from a generic OCR line when structured evidence failed.
            if((identity&&page.identityFields!=null)||
                (!identity&&page.certificateFields!=null)) {
                page.spatialNames
            } else {
                val names=if(identity)TunisianNameParser.identity(page.text)
                    else TunisianNameParser.certificate(page.text)
                names.map{PairNameEvidence(it,page.page,page.source,page.confidence)}
            }
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
    /** Bounded mean-adaptive threshold for tiny printed numeric fields.
     * Pure Android; only a temporary in-memory bitmap, at most one OCR worker.
     */
    private fun adaptiveDigitCrop(input:Bitmap):Bitmap {
        val w=input.width*5;val h=input.height*5
        val scaled=Bitmap.createScaledBitmap(input,w,h,true)
        try {
            val pixels=IntArray(w*h)
            scaled.getPixels(pixels,0,w,0,0,w,h)
            val gray=IntArray(w*h)
            val sums=LongArray((w+1)*(h+1))
            for(y in 0 until h) {
                var row=0L
                for(x in 0 until w) {
                    val p=pixels[y*w+x]
                    val lum=(299*Color.red(p)+587*Color.green(p)+
                        114*Color.blue(p))/1000
                    gray[y*w+x]=lum
                    row+=lum
                    sums[(y+1)*(w+1)+x+1]=sums[y*(w+1)+x+1]+row
                }
            }
            for(y in 0 until h) {
                val y0=(y-7).coerceAtLeast(0)
                val y1=(y+7).coerceAtMost(h-1)
                for(x in 0 until w) {
                    val x0=(x-7).coerceAtLeast(0)
                    val x1=(x+7).coerceAtMost(w-1)
                    val a=(y1+1)*(w+1)+x1+1
                    val b=y0*(w+1)+x1+1
                    val c=(y1+1)*(w+1)+x0
                    val d=y0*(w+1)+x0
                    val mean=(sums[a]-sums[b]-sums[c]+sums[d])/
                        ((x1-x0+1)*(y1-y0+1))
                    pixels[y*w+x]=if(gray[y*w+x]>mean-6)
                        Color.WHITE else Color.BLACK
                }
            }
            return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also{
                it.setPixels(pixels,0,w,0,0,w,h)
            }
        }finally{scaled.recycle()}
    }
    private fun readText(api:TessBaseAPI,bitmap:Bitmap,
        psm:Int=TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
        digitsOnly:Boolean=false):ScanPage {
        val previousMode=api.getPageSegMode()
        try {
            api.setPageSegMode(psm)
            if(digitsOnly)api.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST,
                "0123456789")
            api.clear()
            api.setImage(bitmap)
            return ScanPage(1,api.getUTF8Text().orEmpty().take(60000),
                api.meanConfidence().coerceIn(0,100))
        }finally {
            if(digitsOnly)api.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST,"")
            api.setPageSegMode(previousMode)
        }
    }
    private fun ocrSingle(api:TessBaseAPI,bitmap:Bitmap,index:Int,
                          identity:Boolean):ScanPage {
        fun readBox(l:Float,t:Float,r:Float,b:Float,gray:Boolean=true):ScanPage {
            val crop=region(bitmap,l,t,r,b,gray)
            return try{readText(api,crop)}finally{crop.recycle()}
        }
        fun readDigits(l:Float,t:Float,r:Float,b:Float,
                       adaptive:Boolean):ScanPage {
            val crop=region(bitmap,l,t,r,b)
            val prepared=try {
                if(adaptive)adaptiveDigitCrop(crop)
                else Bitmap.createScaledBitmap(crop,crop.width*4,crop.height*4,true)
            }finally{crop.recycle()}
            return try {
                readText(api,prepared,TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
                    digitsOnly=true)
            }finally{prepared.recycle()}
        }
        try {
            if(identity){
                // Labelled surname and given-name field first; if its layout
                // cannot be read, two narrow rows provide independent evidence.
                val nameBlock=readBox(.49f,.43f,.98f,.70f)
                // Always read the two printed fields independently. The old
                // bands partly covered the CIN number and missed the surname.
                val surnameRow=readBox(.55f,.46f,.98f,.58f)
                val givenRow=readBox(.55f,.56f,.98f,.665f)
                val surnameText=surnameRow.text
                val givenText=givenRow.text
                val surnameConfidence=surnameRow.confidence
                val givenConfidence=givenRow.confidence
                // Masked CIN is shown only when grayscale and original
                // colour OCR independently agree. A valid-looking 8-digit
                // string is otherwise NOT reliable enough to display.
                val numberBand=readBox(.39f,.31f,.69f,.405f)
                val numberInColour=readBox(.39f,.31f,.69f,.405f,false)
                val stableCin=TunisianDocumentFields.repeatableCin(
                    numberBand.text,numberInColour.text)
                val birthBand=readBox(.39f,.70f,.90f,.84f)
                var fields=TunisianDocumentFields.identity(
                    nameBlock.text,surnameText,givenText,
                    if(stableCin!=null)numberBand.text else "",birthBand.text)
                var text=nameBlock.text
                var confidence=nameBlock.confidence
                if(fields.name==null) {
                    // Single bounded fallback. No guessing from father-name row.
                    val full=readText(api,bitmap)
                    val fallback=TunisianDocumentFields.identity(
                        nameBlock.text+"\n"+full.text,
                        surnameText,givenText,
                        if(stableCin!=null)numberBand.text else "",birthBand.text)
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
            val ownerOnly=readBox(.69f,.576f,.88f,.62f)
            val topFields=readBox(.12f,.15f,.97f,.38f)
            val footer=readBox(.07f,.84f,.97f,.96f)
            val pageFooter=readBox(.085f,.867f,.205f,.902f)
            var all=topFields.text+"\n"+ownerZone.text+"\n"+
                footer.text+"\n"+pageFooter.text
            var fields=TunisianDocumentFields.certificate(
                all,ownerZone.text,topFields.text,pageFooter.text+"\n"+footer.text)
            val lineOwner=TunisianDocumentFields.certificateOwnerOnlyBand(
                ownerOnly.text)
            fun corroboratedOwner(name:String?,text:String):String? {
                if(name==null)return null
                val reported=TunisianNameParser.certificate(text)
                return name.takeIf { candidate->
                    reported.all {
                        TunisianNameParser.canonical(it)==
                            TunisianNameParser.canonical(candidate)
                    }
                }
            }
            if(lineOwner!=null) {
                val safe=corroboratedOwner(lineOwner,all)
                fields=fields.copy(owner=when {
                    safe==null -> null
                    fields.owner!=null&&TunisianNameParser.canonical(fields.owner)!=
                        TunisianNameParser.canonical(safe)->null
                    else -> safe
                })
            }
            if(index==1) {
                // Read the printed area twice: header + independent total line.
                // Never silently convert a digit misread as S into 5.
                val areaHead=readDigits(.398f,.279f,.44f,.31f,true)
                val areaTotal=readDigits(.363f,.693f,.44f,.73f,false)
                val confirmed=TunisianDocumentFields.confirmedAreaDigits(
                    areaHead.text,areaTotal.text)
                val labeled=fields.areaM2
                fields=fields.copy(areaM2=when {
                    confirmed!=null&&(labeled==null||labeled==confirmed)->confirmed
                    confirmed!=null&&labeled!=confirmed->null
                    else->labeled
                })
            }
            var confidence=if(lineOwner!=null)ownerOnly.confidence
                else ownerZone.confidence
            if(fields.owner==null){
                val full=readText(api,bitmap)
                all=all+"\n"+full.text
                val recovered=TunisianDocumentFields.certificate(
                    all,ownerZone.text,topFields.text+"\n"+full.text,
                    pageFooter.text+"\n"+footer.text+"\n"+full.text)
                val safer=if(lineOwner==null)null else corroboratedOwner(
                    lineOwner,all)
                fields=recovered.copy(owner=when {
                    safer==null -> recovered.owner
                    recovered.owner!=null&&TunisianNameParser.canonical(
                        recovered.owner)!=TunisianNameParser.canonical(safer)->null
                    else -> safer
                },areaM2=fields.areaM2?:recovered.areaM2)
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
                surname=card?.surname,givenName=card?.givenName,
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
