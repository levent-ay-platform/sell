package tn.malak.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Lightweight Huawei OCR client. No native OCR or model in this APK. */
internal object LocalDocumentOcr {
    internal enum class FrontIssue { LOW_RESOLUTION, CONSENT_REQUIRED, SESSION_REQUIRED, SERVICE_UNAVAILABLE }
    internal data class FrontScan(
        val surname:String?,val givenName:String?,val fullName:String?,
        val cinLast4:String?,val birthDate:String?,val confidence:Int,
        val issue:FrontIssue?=null,
        val cinFull:String?=null
    ) {
        private val readableCore:Boolean get()=
            issue==null&&surname!=null&&givenName!=null&&fullName!=null&&
            confidence>=65&&
            birthDate?.matches(Regex("""[0-9]{4}-[0-9]{2}-[0-9]{2}"""))==true&&
            runCatching{
                val parsed=java.time.LocalDate.parse(birthDate)
                !parsed.isBefore(java.time.LocalDate.of(1900,1,1))&&
                    !parsed.isAfter(java.time.LocalDate.now())
            }.getOrDefault(false)&&
            TunisianNameParser.canonical("$surname $givenName")==
                TunisianNameParser.canonical(fullName)
        // Both outcomes are preliminary and require server-side human review.
        // A deliberately hidden CIN may be submitted for review, never approved.
        val readyForReview:Boolean get()=
            readableCore&&cinLast4?.matches(Regex("""[0-9]{4}"""))==true
        val readyForManualReview:Boolean get()=readableCore&&cinLast4==null
        /** Camera capture may be accepted only after all four core fields
         * are genuinely present and internally consistent. A masked test CIN,
         * partial four digits, failed OCR or missing birth date is REVIEW
         * ONLY, not a successfully accepted identity capture.
         */
        val readyForCameraAcceptance:Boolean get()=
            readableCore &&
            cinFull?.matches(Regex("""[0-9]{8}"""))==true &&
            cinFull?.takeLast(4)==cinLast4
    }
    private const val MAX_IMAGE=6L*1024L*1024L
    private val UUID=Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    private val TOKEN=Regex("^[A-Za-z0-9._~+/-]{20,4096}$")
    private val AR=Regex("""^[\u0621-\u064a]+(?: [\u0621-\u064a]+){0,3}$""")
    private fun no(issue:FrontIssue)=FrontScan(null,null,null,null,null,0,issue)
    private fun field(json:JSONObject,key:String):String? {
        if(json.isNull(key))return null
        val v=json.optString(key).trim().replace(Regex("""\s+""")," ")
        if(v.isEmpty())return null
        if(v.length !in 2..55||!AR.matches(v))
            throw IllegalStateException("HUAWEI_OCR_BAD_FIELD")
        return v
    }
    suspend fun inspectFront(context:Context,uri:Uri,length:Long,mime:String,
        session:OwnerEvidenceSession?):FrontScan {
        if(session==null)return no(FrontIssue.SESSION_REQUIRED)
        if(!UUID.matches(session.caseId))return no(FrontIssue.SERVICE_UNAVAILABLE)
        return inspectAnyFront(context,uri,length,mime,session.httpsBaseUrl,
            session.bearer,"/internal/v1/evidence/"+session.caseId+"/preview-cin",false)
    }
    suspend fun inspectPilotFront(context:Context,uri:Uri,length:Long,mime:String,
        session:PilotOcrSession?):FrontScan {
        if(session==null)return no(FrontIssue.SESSION_REQUIRED)
        if(session.expiresAtMs<=System.currentTimeMillis())
            return no(FrontIssue.SESSION_REQUIRED)
        return inspectAnyFront(context,uri,length,mime,session.httpsBaseUrl,
            session.bearer,"/internal/v1/pilot/preview-cin",true)
    }

    private suspend fun inspectAnyFront(context:Context,uri:Uri,length:Long,
        mime:String,httpsBaseUrl:String,bearer:String,endpoint:String,
        pilot:Boolean):FrontScan=withContext(Dispatchers.IO) {
        // Preview uses only an ephemeral image produced by the in-app CameraX
        // capture, never any gallery, shared provider or external document.
        if(uri.scheme!="content"||mime!="image/jpeg"||
            !PrivateCaptureMemory.isPrivate(uri)||
            PrivateCaptureMemory.length(uri)!=length||
            length !in 20..MAX_IMAGE||!TOKEN.matches(bearer))
            return@withContext no(FrontIssue.SERVICE_UNAVAILABLE)
        val dims=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        val src=context.contentResolver.openInputStream(uri)
            ?:return@withContext no(FrontIssue.SERVICE_UNAVAILABLE)
        src.use{BitmapFactory.decodeStream(it,null,dims)}
        if(dims.outWidth<175||dims.outHeight<175)
            return@withContext no(FrontIssue.LOW_RESOLUTION)
        val base=runCatching{URI(httpsBaseUrl.trim())}.getOrNull()
            ?:return@withContext no(FrontIssue.SERVICE_UNAVAILABLE)
        if(base.scheme!="https"||base.host.isNullOrBlank()||base.userInfo!=null||
            base.query!=null||base.fragment!=null||
            (base.path!=""&&base.path!="/")||base.port !in -1..65535)
            return@withContext no(FrontIssue.SERVICE_UNAVAILABLE)
        val address=base.toASCIIString().trimEnd('/')+endpoint
        val conn=(URL(address).openConnection() as? HttpsURLConnection)
            ?:return@withContext no(FrontIssue.SERVICE_UNAVAILABLE)
        try {
            conn.requestMethod="POST"
            conn.instanceFollowRedirects=false
            conn.connectTimeout=7000
            conn.readTimeout=35000
            conn.useCaches=false
            conn.doOutput=true
            conn.setRequestProperty("Authorization","Bearer "+bearer)
            if(pilot)conn.setRequestProperty("X-Pilot-Ocr-Consent","1")
            conn.setRequestProperty("Cache-Control","no-store")
            conn.setRequestProperty("Content-Type","application/octet-stream")
            conn.setRequestProperty("X-Claimed-Mime",mime)
            conn.setFixedLengthStreamingMode(length.toInt())
            val input=context.contentResolver.openInputStream(uri)
                ?:return@withContext no(FrontIssue.SERVICE_UNAVAILABLE)
            input.use{source->
                conn.outputStream.use{out->
                    val buf=ByteArray(32768)
                    try{
                        var sent=0L
                        while(true){
                            val n=source.read(buf)
                            if(n<0)break
                            sent+=n
                            if(sent>length||sent>MAX_IMAGE)
                                throw IllegalStateException("HUAWEI_IMAGE_LENGTH")
                            out.write(buf,0,n)
                        }
                        if(sent!=length)throw IllegalStateException("HUAWEI_IMAGE_LENGTH")
                    }finally{buf.fill(0)}
                }
            }
            if(conn.responseCode!=200)
                return@withContext no(FrontIssue.SERVICE_UNAVAILABLE)
            val bytes=conn.inputStream.use{input->
                val out=ByteArrayOutputStream()
                val buf=ByteArray(4096)
                try{
                    while(true){
                        val n=input.read(buf)
                        if(n<0)break
                        if(out.size()+n>4096)
                            throw IllegalStateException("HUAWEI_OCR_REPLY_TOO_LARGE")
                        out.write(buf,0,n)
                    }
                    out.toByteArray()
                }finally{buf.fill(0);out.reset()}
            }
            val json=try{JSONObject(String(bytes,Charsets.UTF_8))}
                finally{bytes.fill(0)}
            if(!json.optBoolean("ok")||json.optInt("schemaVersion")!=1||
                (pilot && json.optBoolean("pilotOcr")!=true)||
                (!pilot && json.has("pilotOcr"))||
                json.optBoolean("identityVerified",true)||
                json.optBoolean("ownershipVerified",true)||
                json.optBoolean("mayPublish",true)||
                json.optString("engine")!="arabic-v5")
                throw IllegalStateException("HUAWEI_OCR_UNSAFE_REPLY")
            val surname=field(json,"surname")
            val given=field(json,"givenName")
            val confidence=json.optInt("confidencePercent",-1)
            if(confidence !in 0..100)throw IllegalStateException("HUAWEI_OCR_BAD_CONFIDENCE")
            val last4=if(json.isNull("cinLast4"))null
                else json.optString("cinLast4").takeIf{it.matches(Regex("""\d{4}"""))}
            val full=if(!json.has("cinFull")||json.isNull("cinFull"))null
                else json.optString("cinFull").also{
                    if(!it.matches(Regex("""[0-9]{8}"""))||
                       it.takeLast(4)!=last4)
                        throw IllegalStateException("HUAWEI_OCR_BAD_CIN")
                }
            val birth=if(json.isNull("birthDate"))null
                else json.optString("birthDate").takeIf{
                    it.matches(Regex("""[0-9]{4}-[0-9]{2}-[0-9]{2}"""))&&
                    runCatching{
                        val parsed=java.time.LocalDate.parse(it)
                        !parsed.isBefore(java.time.LocalDate.of(1900,1,1))&&
                            !parsed.isAfter(java.time.LocalDate.now())
                    }.getOrDefault(false)
                }
            FrontScan(surname,given,
                if(surname!=null&&given!=null)"$surname $given" else null,
                last4,birth,confidence,cinFull=full)
        }catch (cancel:kotlinx.coroutines.CancellationException){throw cancel}
        catch (_:Exception){no(FrontIssue.SERVICE_UNAVAILABLE)}
        finally{conn.disconnect()}
    }
}
