package tn.malak.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.text.Normalizer
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
    private val unsafeName = Regex("""[\p{N}\p{Cc}\p{Cf}]""")
    private val permittedName = Regex("""^[\p{L}\p{M}\p{Zs}'’\-]{7,100}$""")
    private val ownerLine = Regex(
        """(?iu)^\s*(?:nom\s+du\s+propri[ée]taire|propri[ée]taire|titulaire|المالك|اسم\s+المالك|صاحب\s+الرسم)\s*(?:[:：\-]|\s+)\s*(.{7,100})\s*$"""
    )
    private val fullIdentity = Regex(
        """(?iu)^\s*(?:nom\s+et\s+pr[ée]nom|nom\s+et\s+pr[ée]noms|الاسم\s+واللقب|الاسم\s+و\s+اللقب)\s*(?:[:：\-]|\s+)\s*(.{7,100})\s*$"""
    )
    private val lastName = Regex("""(?iu)^\s*nom\s*[:：\-]\s*(.{2,70})\s*$""")
    private val firstName = Regex("""(?iu)^\s*pr[ée]nom\s*[:：\-]\s*(.{2,70})\s*$""")
    private val spaces = Regex("""\s+""")
    private val foldedMarks = Regex("""\p{M}+""")

    private data class ScanPage(val page:Int,val text:String,val confidence:Int) {
        val source:String get() = if (text.any { it in '\u0600'..'\u06ff' }) "ocr-ar" else "ocr-fr"
    }
    private fun recognizedName(raw:String):String? {
        val clean=raw.trim().replace(spaces," ")
        if(clean.length !in 7..100 || unsafeName.containsMatchIn(clean) ||
            !permittedName.matches(clean) || clean.split(' ').size<2)return null
        return clean
    }
    private fun labelCandidates(pages:List<ScanPage>,identity:Boolean):List<PairNameEvidence> {
        val found=mutableListOf<PairNameEvidence>()
        for(page in pages) {
            val lines=page.text.lineSequence().map{it.trim()}.filter{it.isNotEmpty()}.toList()
            for(line in lines) {
                val raw=if(identity)fullIdentity.matchEntire(line)?.groupValues?.get(1)
                    else ownerLine.matchEntire(line)?.groupValues?.get(1)
                val name=raw?.let(::recognizedName)
                if(name!=null)found.add(PairNameEvidence(name,page.page,page.source,page.confidence))
            }
            if(identity) {
                val sur=lines.firstNotNullOfOrNull { lastName.matchEntire(it)?.groupValues?.get(1) }
                val given=lines.firstNotNullOfOrNull { firstName.matchEntire(it)?.groupValues?.get(1) }
                if(sur!=null && given!=null) {
                    recognizedName(given.trim()+" "+sur.trim())?.let {
                        found.add(PairNameEvidence(it,page.page,page.source,page.confidence))
                    }
                }
            }
        }
        return found
    }
    private fun normalized(value:String):String {
        val compact=Normalizer.normalize(value,Normalizer.Form.NFD).replace(foldedMarks,"")
        return compact.lowercase(java.util.Locale.ROOT).replace(spaces," ").trim()
    }
    private fun oneName(candidates:List<PairNameEvidence>):PairNameEvidence? =
        candidates.firstOrNull()?.takeIf { first ->
            candidates.all { normalized(it.value)==normalized(first.value) }
        }

    private fun ensureModels(context:Context):String {
        val root=File(context.noBackupFilesDir,"malak-ocr")
        val folder=File(root,"tessdata")
        if(!folder.exists() && !folder.mkdirs())throw IllegalStateException("OCR_MODEL_STORAGE")
        for(lang in listOf("ara","fra","eng")) {
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
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds) }
            ?:throw IllegalStateException("OCR_IMAGE_UNAVAILABLE")
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
    private fun ocrSingle(api:TessBaseAPI,bitmap:Bitmap,index:Int):ScanPage {
        try {
            api.setImage(bitmap)
            val text=api.getUTF8Text().orEmpty().take(60000)
            return ScanPage(index,text,api.meanConfidence().coerceIn(0,100))
        }finally { bitmap.recycle() }
    }
    private suspend fun scanInput(context:Context,api:TessBaseAPI,
        files:List<PairEvidenceFile>):List<ScanPage>{
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
                                output.add(ocrSingle(api,bmp,i+1))
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
                output.add(ocrSingle(api,decodeImage(context,file.uri),position+1))
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
            (identity.size==1&&identity.single().mime!="application/pdf")||
            (certificates+identity).map{it.uri}.distinct().size!=certificates.size+identity.size)
            throw IllegalStateException("OCR_DOCUMENT_SELECTION")
        val api=TessBaseAPI()
        try {
            if(!api.init(ensureModels(context),"ara+fra+eng"))
                throw IllegalStateException("OCR_ENGINE_UNAVAILABLE")
            onProgress(PairProgress.CERTIFICATE_UPLOAD)
            val cert=scanInput(context,api,certificates)
            onProgress(PairProgress.IDENTITY_UPLOAD)
            val id=scanInput(context,api,identity)
            onProgress(PairProgress.COMPARING)
            val certCandidates=labelCandidates(cert,false)
            val identityCandidates=labelCandidates(id,true)
            val owner=oneName(certCandidates)
            val person=oneName(identityCandidates)
            val enoughImages=id.size>=2 && cert.isNotEmpty()
            val confident=(owner?.confidence?:0)>=70&&(person?.confidence?:0)>=70
            val ambiguity=(certCandidates.size>1 && owner==null)||
                (identityCandidates.size>1&&person==null)
            val same=owner!=null&&person!=null&&normalized(owner.value)==normalized(person.value)
            val assessment=when{
                owner==null||person==null||!enoughImages||!confident||ambiguity->
                    "INSUFFICIENT_OR_AMBIGUOUS"
                same->"PRELIMINARY_TEXT_AGREEMENT"
                else->"TEXT_DIFFERENCE_NEEDS_REVIEW"
            }
            val reasons=mutableListOf("OFFICIAL_CPF_AND_DOCUMENT_AUTHENTICITY_NOT_CHECKED")
            if(!enoughImages)reasons.add("IDENTITY_REVERSE_MISSING")
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
                else if(id.size>=2)"MULTI_PAGE_UNCONFIRMED" else "MISSING_REVERSE",
                cert.size,id.size,
                ownershipVerified=false,identityVerified=false,mayPublish=false
            )
        }finally { api.recycle() }
    }
}
