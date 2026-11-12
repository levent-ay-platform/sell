package tn.malak.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/**
 * Inject only after genuine server-side identity, consent and case provisioning.
 * Never show bearer tokens or endpoint settings on customer screens.
 * In-memory only; production has NO default session until authentication exists.
 */
internal data class OwnerEvidenceSession(val httpsBaseUrl:String, val caseId:String, val bearer:String)
internal val LocalOwnerEvidenceSession=staticCompositionLocalOf<OwnerEvidenceSession?>{null}

internal data class PairEvidenceFile(val uri:Uri,val expectedBytes:Long,val mime:String,
    val idempotencyKey:String=UUID.randomUUID().toString())
internal enum class PairProgress { CASE_PROVISION,CERTIFICATE_UPLOAD,IDENTITY_UPLOAD,COMPARING }
internal data class PairNameEvidence(val value:String,val page:Int,val source:String,val confidence:Int?)
/** PII is shown only in the user's local screen and never persisted or logged. */
internal data class LocalOcrFields(
    val titleNumber:String?,val areaM2:String?,val expectedCertificatePages:Int?,
    val surname:String?,val givenName:String?,val cinLast4:String?,
    val birthDate:String?
)
internal data class CertificateIdentityReview(
    val assessment:String,val certificateName:PairNameEvidence?,val identityName:PairNameEvidence?,
    val cinStatus:String,val reasons:List<String>,val identityCoverage:String,
    val certificatePages:Int,val identityPages:Int,
    val ownershipVerified:Boolean=false,val identityVerified:Boolean=false,
    val mayPublish:Boolean=false,val localFields:LocalOcrFields?=null)
internal class OwnerPairException(val statusCode:Int):Exception("PRIVATE_PAIR_UNAVAILABLE")

private const val DOC_LIMIT=12L*1024L*1024L
private const val RESPONSE_LIMIT=64*1024
private val ID=Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
private val KEY=Regex("^[A-Za-z0-9_-]{16,100}$")
private val TOKEN=Regex("^[A-Za-z0-9._~+/-]{20,4096}$")
private val MIME=setOf("application/pdf","image/jpeg","image/png","image/tiff")
private val ASSESSMENTS=setOf("PRELIMINARY_TEXT_AGREEMENT","TEXT_DIFFERENCE_NEEDS_REVIEW","INSUFFICIENT_OR_AMBIGUOUS")
private val CIN=setOf("SAME_NUMBER_UNVERIFIED","NUMBER_DIFFERS_NEEDS_REVIEW","NOT_COMPARABLE","AMBIGUOUS_OR_LOW_CONFIDENCE")
private val COVERAGE=setOf("MISSING_REVERSE","MULTI_PAGE_UNCONFIRMED","TWO_FILES_UNCONFIRMED")

private fun validateSession(session:OwnerEvidenceSession):String {
    if(!ID.matches(session.caseId)||!TOKEN.matches(session.bearer))throw OwnerPairException(401)
    val uri=runCatching{URI(session.httpsBaseUrl.trim())}.getOrNull()?:throw OwnerPairException(400)
    if(uri.scheme!="https"||uri.host.isNullOrBlank()||uri.userInfo!=null||
        uri.query!=null||uri.fragment!=null||(uri.path!=""&&uri.path!="/")||
        uri.port !in -1..65535)throw OwnerPairException(400)
    return uri.toASCIIString().trimEnd('/')
}
private fun validateFiles(cert:List<PairEvidenceFile>,identity:List<PairEvidenceFile>) {
    if(cert.isEmpty()||cert.size>8||identity.isEmpty()||identity.size>2)throw OwnerPairException(400)
    val all=cert+identity
    if(all.any{it.expectedBytes !in 20..DOC_LIMIT||it.mime !in MIME||
        it.uri.scheme!="content"||!KEY.matches(it.idempotencyKey)}||
        all.map{it.uri}.distinct().size!=all.size||
        all.map{it.idempotencyKey}.distinct().size!=all.size||
        (cert.size>1&&cert.any{it.mime=="application/pdf"})||
        (identity.size==1&&identity[0].mime!="application/pdf")||
        (identity.size==2&&identity.any{it.mime=="application/pdf"}))
        throw OwnerPairException(400)
}
private fun conn(base:String,session:OwnerEvidenceSession,path:String):HttpsURLConnection {
    val url=URL(base+"/internal/v1/evidence/"+session.caseId+"/"+path)
    val c=url.openConnection() as? HttpsURLConnection?:throw OwnerPairException(400)
    c.requestMethod="POST"
    c.instanceFollowRedirects=false
    c.connectTimeout=10000
    c.readTimeout=if(path=="compare-pair")180000 else 75000
    c.useCaches=false
    c.doOutput=true
    c.setRequestProperty("Authorization","Bearer "+session.bearer)
    c.setRequestProperty("Cache-Control","no-store")
    return c
}
private fun readJson(c:HttpsURLConnection,expected:Int):JSONObject {
    val status=c.responseCode
    if(status!=expected)throw OwnerPairException(status)
    val bytes=c.inputStream.use{input->
        val out=ByteArrayOutputStream()
        val buffer=ByteArray(4096)
        try {
            while(true){
                val n=input.read(buffer)
                if(n==-1)break
                if(out.size()+n>RESPONSE_LIMIT)throw OwnerPairException(502)
                out.write(buffer,0,n)
            }
            out.toByteArray()
        }finally{buffer.fill(0);out.reset()}
    }
    return try{JSONObject(String(bytes,Charsets.UTF_8))}
    catch(_:Exception){throw OwnerPairException(502)}
    finally{bytes.fill(0)}
}
private fun safeEnvelope(json:JSONObject) {
    if(!json.optBoolean("ok",false)||json.optBoolean("ownershipVerified",true)||
        json.optBoolean("mayPublish",true))throw OwnerPairException(502)
}
private fun upload(context:Context,session:OwnerEvidenceSession,base:String,
                   file:PairEvidenceFile,kind:String,page:Int?):String {
    val c=conn(base,session,"upload")
    try{
        c.setRequestProperty("Content-Type","application/octet-stream")
        c.setRequestProperty("X-Idempotency-Key",file.idempotencyKey)
        c.setRequestProperty("X-Evidence-Kind",kind)
        c.setRequestProperty("X-Page-Number",page?.toString()?:"null")
        c.setRequestProperty("X-Claimed-Mime",file.mime)
        c.setFixedLengthStreamingMode(file.expectedBytes.toInt())
        val input=context.contentResolver.openInputStream(file.uri)?:throw OwnerPairException(422)
        input.use{src->
            c.outputStream.use{dest->
                val buffer=ByteArray(32768)
                var sent=0L
                try{
                    while(true){
                        val n=src.read(buffer)
                        if(n==-1)break
                        sent+=n
                        if(sent>file.expectedBytes||sent>DOC_LIMIT)throw OwnerPairException(413)
                        dest.write(buffer,0,n)
                    }
                    if(sent!=file.expectedBytes)throw OwnerPairException(422)
                    dest.flush()
                }finally{buffer.fill(0)}
            }
        }
        val body=readJson(c,200);safeEnvelope(body)
        val evidenceId=body.optString("evidenceId")
        if(!ID.matches(evidenceId))throw OwnerPairException(502)
        return evidenceId
    }finally{c.disconnect()}
}
private fun nameOf(comparison:JSONObject,key:String):PairNameEvidence? {
    if(comparison.isNull(key))return null
    val obj=comparison.optJSONObject(key)?:throw OwnerPairException(502)
    val value=obj.optString("value")
    val page=obj.optInt("page")
    val source=obj.optString("source")
    val raw=if(obj.isNull("confidence"))null else obj.optDouble("confidence",Double.NaN)
    if(value.length !in 7..100||page !in 1..8||source !in setOf("pdf-text","ocr-ar","ocr-fr")||
       (raw!=null&&(!raw.isFinite()||raw !in 0.0..1.0)))throw OwnerPairException(502)
    return PairNameEvidence(value,page,source,raw?.let{(it*100).toInt()})
}
private fun parseReview(body:JSONObject):CertificateIdentityReview {
    safeEnvelope(body)
    if(body.optBoolean("identityVerified",true))throw OwnerPairException(502)
    val report=body.optJSONObject("report")?:throw OwnerPairException(502)
    if(report.optString("access")!="AUTHORIZED_CASE_OWNER_ONLY"||
        report.optBoolean("ownershipVerified",true)||report.optBoolean("identityVerified",true)||
        report.optBoolean("mayPublish",true)||!report.optBoolean("requiresHumanReview",false))
        throw OwnerPairException(502)
    val comparison=report.optJSONObject("comparison")?:throw OwnerPairException(502)
    if(comparison.optBoolean("ownershipVerified",true)||
       comparison.optBoolean("identityVerified",true)||comparison.optBoolean("mayPublish",true)||
       !comparison.optBoolean("requiresHumanReview",false))throw OwnerPairException(502)
    val assessment=comparison.optString("assessment")
    val cin=report.optJSONObject("cinComparison")?:throw OwnerPairException(502)
    val cinStatus=cin.optString("status")
    val coverage=report.optString("identitySides")
    val certPages=report.optInt("certificatePagesReceived")
    val idPages=report.optInt("identityPagesReceived")
    if(assessment !in ASSESSMENTS||cinStatus !in CIN||coverage !in COVERAGE||
       cin.optBoolean("mayVerifyIdentity",true)||cin.has("number")||cin.has("value")||
       certPages !in 1..8||idPages !in 1..8||
       (coverage=="MISSING_REVERSE"&&assessment=="PRELIMINARY_TEXT_AGREEMENT")||
       (cinStatus=="NUMBER_DIFFERS_NEEDS_REVIEW"&&assessment!="TEXT_DIFFERENCE_NEEDS_REVIEW"))
        throw OwnerPairException(502)
    val rawReasons=comparison.optJSONArray("reasons")?:throw OwnerPairException(502)
    if(rawReasons.length()>16)throw OwnerPairException(502)
    val reasons=(0 until rawReasons.length()).map{
        val v=rawReasons.optString(it)
        if(!Regex("^[A-Z0-9_]{3,80}$").matches(v))throw OwnerPairException(502)
        v
    }
    return CertificateIdentityReview(assessment,nameOf(comparison,"certificateName"),
        nameOf(comparison,"identityName"),cinStatus,reasons,coverage,certPages,idPages)
}
/**
 * Only explicit user action uploads files. Server-owned case/consent required.
 * No automatic retry, no credential/doc/report persistence, no QR/legal approval.
 */
internal suspend fun compareOwnerCertificateAndIdentity(
    context:Context,session:OwnerEvidenceSession,
    certificates:List<PairEvidenceFile>,identities:List<PairEvidenceFile>,
    onProgress:(PairProgress)->Unit={}
):CertificateIdentityReview=withContext(Dispatchers.IO){
    val base=validateSession(session)
    validateFiles(certificates,identities)
    val certIds=mutableListOf<String>()
    val identityIds=mutableListOf<String>()
    for((i,file) in certificates.withIndex()){
        withContext(Dispatchers.Main){onProgress(PairProgress.CERTIFICATE_UPLOAD)}
        certIds.add(upload(context,session,base,file,"OWNERSHIP_CERTIFICATE",
            if(file.mime=="application/pdf")null else i+1))
    }
    for((i,file) in identities.withIndex()){
        withContext(Dispatchers.Main){onProgress(PairProgress.IDENTITY_UPLOAD)}
        identityIds.add(upload(context,session,base,file,"IDENTITY",
            if(file.mime=="application/pdf")null else i+1))
    }
    withContext(Dispatchers.Main){onProgress(PairProgress.COMPARING)}
    val payload=JSONObject().put("certificateEvidenceIds",JSONArray(certIds))
        .put("identityEvidenceIds",JSONArray(identityIds))
        .toString().toByteArray(Charsets.UTF_8)
    val c=conn(base,session,"compare-pair")
    try{
        c.setRequestProperty("Content-Type","application/json")
        c.setFixedLengthStreamingMode(payload.size)
        c.outputStream.use{it.write(payload)}
        parseReview(readJson(c,200))
    }finally{payload.fill(0);c.disconnect()}
}
