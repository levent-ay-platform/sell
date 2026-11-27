package tn.malak.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Only the approved future +216 sign-in boundary can inject this session.
 * No test credentials, persisted tokens or public endpoints in the APK.
 */
internal data class AuthenticatedOwnerSession(val httpsBaseUrl:String,val bearer:String)
internal val LocalAuthenticatedOwnerSession=
    staticCompositionLocalOf<AuthenticatedOwnerSession?> { null }

internal data class NewOwnerCaseDraft(
    val idempotencyKey:String,val title:String,val region:String,
    val areaSquareMetres:String,val priceTnd:String,val capacity:String
)
private val ID=Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
private val TOKEN=Regex("^[A-Za-z0-9._~+/-]{20,4096}$")
private val KEY=Regex("^[A-Za-z0-9_-]{16,100}$")
private val AREA=Regex("^[1-9][0-9]{0,8}(?:\\.[0-9]{1,3})?$")
private val PRICE=Regex("^[1-9][0-9]{0,11}$")
private val CAPACITIES=setOf("owner","partner","heir","mandate")
private const val MAX_RESPONSE=8192

private fun privateBase(session:AuthenticatedOwnerSession):String {
    if(!TOKEN.matches(session.bearer))throw OwnerPairException(401)
    val uri=runCatching{URI(session.httpsBaseUrl.trim())}.getOrNull()
        ?:throw OwnerPairException(400)
    if(uri.scheme!="https"||uri.host.isNullOrBlank()||uri.userInfo!=null||
       uri.query!=null||uri.fragment!=null||(uri.path!=""&&uri.path!="/")||
       uri.port !in -1..65535)throw OwnerPairException(400)
    return uri.toASCIIString().trimEnd('/')
}
private fun validateDraft(draft:NewOwnerCaseDraft) {
    if(!KEY.matches(draft.idempotencyKey)||draft.title.trim().length !in 2..100||
       draft.region.trim().length !in 2..100||
       !AREA.matches(draft.areaSquareMetres)||!PRICE.matches(draft.priceTnd)||
       draft.capacity !in CAPACITIES)throw OwnerPairException(400)
}
/** Returns a real case only when server creates it; never manufacture a case UUID. */
internal suspend fun provisionOwnerEvidenceCase(
    auth:AuthenticatedOwnerSession,draft:NewOwnerCaseDraft
):OwnerEvidenceSession {
    validateDraft(draft)
    return submitOwnerCase(auth,JSONObject().put("idempotencyKey",draft.idempotencyKey)
        .put("title",draft.title.trim()).put("region",draft.region.trim())
        .put("areaSquareMetres",draft.areaSquareMetres)
        .put("priceTnd",draft.priceTnd).put("capacity",draft.capacity)
        .put("consentAccepted",true))
}
/** Consent to review scanned documents before claiming a title or entering prices. */
internal suspend fun provisionOwnerEvidenceIntakeCase(
    auth:AuthenticatedOwnerSession,idempotencyKey:String
):OwnerEvidenceSession {
    if(!KEY.matches(idempotencyKey))throw OwnerPairException(400)
    return submitOwnerCase(auth,JSONObject()
        .put("mode","EVIDENCE_ONLY").put("idempotencyKey",idempotencyKey)
        .put("consentAccepted",true))
}
private suspend fun submitOwnerCase(
    auth:AuthenticatedOwnerSession,request:JSONObject
):OwnerEvidenceSession=withContext(Dispatchers.IO) {
    val base=privateBase(auth)
    val payload=request.toString().toByteArray(Charsets.UTF_8)
    val connection=URL(base+"/internal/v1/owner/cases").openConnection() as? HttpsURLConnection
        ?:throw OwnerPairException(400)
    try {
        connection.requestMethod="POST"
        connection.instanceFollowRedirects=false
        connection.connectTimeout=10000
        connection.readTimeout=25000
        connection.useCaches=false
        connection.doOutput=true
        connection.setRequestProperty("Authorization","Bearer "+auth.bearer)
        connection.setRequestProperty("Content-Type","application/json")
        connection.setRequestProperty("Cache-Control","no-store")
        connection.setFixedLengthStreamingMode(payload.size)
        connection.outputStream.use{it.write(payload)}
        val status=connection.responseCode
        if(status!=200&&status!=201)throw OwnerPairException(status)
        val raw=connection.inputStream.use{input->
            val out=ByteArrayOutputStream()
            val buffer=ByteArray(4096)
            try {
                while(true){
                    val n=input.read(buffer)
                    if(n<0)break
                    if(out.size()+n>MAX_RESPONSE)throw OwnerPairException(502)
                    out.write(buffer,0,n)
                }
                out.toByteArray()
            }finally{buffer.fill(0);out.reset()}
        }
        val body=try{JSONObject(String(raw,Charsets.UTF_8))}
            catch(_:Exception){throw OwnerPairException(502)}
            finally{raw.fill(0)}
        val caseId=body.optString("caseId")
        if(!body.optBoolean("ok",false)||!ID.matches(caseId)||
           !body.optBoolean("consentRecorded",false)||
           body.optBoolean("ownershipVerified",true)||
           body.optBoolean("identityVerified",true)||
           body.optBoolean("mayPublish",true)||
           !body.has("reused"))throw OwnerPairException(502)
        OwnerEvidenceSession(base,caseId,auth.bearer)
    }finally{payload.fill(0);connection.disconnect()}
}
