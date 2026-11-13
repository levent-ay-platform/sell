package tn.malak.app.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/**
 * Real Android -> private owner-only OCR contract. No token is written to disk.
 * Configure only via real login/session authority in production; the QA form
 * deliberately permits disposable, synthetic-only sessions on a trusted HTTPS
 * private endpoint. Never use it against a real owner's CIN/CPF until approved.
 */
internal data class QaOcrSession(val httpsBaseUrl: String, val caseId: String, val bearer: String)
internal data class ReadOcrFact(
    val label: String, val value: String?, val state: String,
    val page: Int?, val source: String?, val confidence: Int?
)
internal data class ReadFamilyFact(
    val id:String,val value:String?,val status:String,val page:Int?,
    val confidencePercent:Int?
)
internal data class ReadOcrResult(
    val evidenceId: String, val status: String, val facts: List<ReadOcrFact>,
    val missingPages: List<Int>, val qualityPages: List<Int>,
    val areaComparison: String, val pagesReceived: Int,
    val familyKind:String="UNKNOWN",
    val familyFacts:List<ReadFamilyFact> = emptyList(),
    val documentSupportLevel:Int=0,
)
private const val MAX_DOC = 12L * 1024L * 1024L
private const val MAX_REPLY = 64 * 1024
private val ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

internal class PrivateOcrClientException(val statusCode: Int) : Exception("PRIVATE_OCR_FAILED")

/** Fixed-length upload without keeping the entire 12MiB document in Java heap. */
internal suspend fun readOcrForOwner(
    context: Context, uri: Uri, expectedBytes: Long, mime: String,
    pageNumber: Int?, idempotencyKey: String, declaredArea: String,
    session: QaOcrSession, evidenceKind:String="OTHER_LEGAL_PROOF"
): ReadOcrResult = withContext(Dispatchers.IO) {
    if(expectedBytes !in 20..MAX_DOC ||
        mime !in setOf("application/pdf", "image/jpeg", "image/png", "image/tiff") ||
        !ID.matches(session.caseId) ||
        !Regex("^[A-Za-z0-9_-]{16,100}$").matches(idempotencyKey) ||
        session.bearer.length !in 20..4096 ||
        !Regex("^[A-Za-z0-9._~+/-]+$").matches(session.bearer) ||
        (pageNumber != null && pageNumber !in 1..8) ||
        evidenceKind !in setOf("OWNERSHIP_CERTIFICATE","SHARED_OWNERSHIP_CERTIFICATE",
            "SALE_DEED","TRANSFER_CARD","POSSESSION_REPORT","FINANCIAL_RECEIPT",
            "PROPERTY_SERVICE_RECEIPT","OTHER_LEGAL_PROOF","TITLE_EXTRACT"))
        throw PrivateOcrClientException(400)
    val base = runCatching { URI(session.httpsBaseUrl.trim()) }.getOrNull()
        ?: throw PrivateOcrClientException(400)
    if(base.scheme != "https" || base.host.isNullOrBlank() ||
        base.port !in -1..65535 || base.userInfo != null || base.query != null ||
        base.fragment != null || (base.path != "" && base.path != "/"))
        throw PrivateOcrClientException(400)
    val url = URL("${base.toASCIIString().trimEnd('/')}/internal/v1/evidence/${session.caseId}/upload-ocr")
    val conn = url.openConnection() as? HttpsURLConnection
        ?: throw PrivateOcrClientException(400)
    try {
        conn.requestMethod = "POST"
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 10000
        conn.readTimeout = 65000
        conn.useCaches = false
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(expectedBytes.toInt())
        conn.setRequestProperty("Authorization", "Bearer ${session.bearer}")
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setRequestProperty("Cache-Control", "no-store")
        conn.setRequestProperty("X-Idempotency-Key", idempotencyKey)
        conn.setRequestProperty("X-Evidence-Kind", evidenceKind)
        conn.setRequestProperty("X-Page-Number", pageNumber?.toString() ?: "null")
        conn.setRequestProperty("X-Claimed-Mime", mime)
        if(Regex("^[0-9]{1,10}(?:\\.[0-9]{1,3})?$").matches(declaredArea))
            conn.setRequestProperty("X-Declared-Area-M2", declaredArea)
        val providerStream = context.contentResolver.openInputStream(uri)
            ?: throw PrivateOcrClientException(422)
        providerStream.use { input ->
            conn.outputStream.use { out ->
                val buf = ByteArray(32 * 1024)
                var sent = 0L
                try {
                    while(true) {
                        val n = input.read(buf)
                        if(n == -1) break
                        sent += n
                        if(sent > expectedBytes || sent > MAX_DOC) throw PrivateOcrClientException(413)
                        out.write(buf, 0, n)
                    }
                    if(sent != expectedBytes) throw PrivateOcrClientException(422)
                    out.flush()
                } finally { buf.fill(0) }
            }
        }
        val code = conn.responseCode
        if(code != 200 && code != 202) throw PrivateOcrClientException(code)
        val responseStream = conn.inputStream
        val body = responseStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            try {
                while(true) {
                    val n = input.read(buffer)
                    if(n == -1) break
                    if(out.size() + n > MAX_REPLY) throw PrivateOcrClientException(502)
                    out.write(buffer, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            } finally { buffer.fill(0); out.reset() }
        }
        val json = runCatching { JSONObject(body) }.getOrNull() ?: throw PrivateOcrClientException(502)
        if(!json.optBoolean("ok") || json.optBoolean("mayPublish", true) ||
            json.optBoolean("ownershipVerified", true)) throw PrivateOcrClientException(502)
        val evidenceId = json.optString("evidenceId")
        if(!ID.matches(evidenceId)) throw PrivateOcrClientException(502)
        val status = json.optString("ocrStatus", "UNAVAILABLE")
        if(status == "UNAVAILABLE" && code == 202)
            return@withContext ReadOcrResult(evidenceId,status,emptyList(),emptyList(),emptyList(),"NOT_COMPARABLE",0)
        if(status != "READY" || code != 200) throw PrivateOcrClientException(502)
        val reading = json.optJSONObject("reading") ?: throw PrivateOcrClientException(502)
        if(reading.optInt("schemaVersion") != 1 ||
            reading.optString("access") != "AUTHORIZED_OWNER_OR_REVIEWER_ONLY" ||
            reading.optBoolean("mayPublish", true) || reading.optBoolean("ownershipVerified", true) ||
            reading.optString("ownerIdentityMatch") != "NOT_STARTED" ||
            reading.optString("officialCpfOwnershipCheck") != "NOT_STARTED")
            throw PrivateOcrClientException(502)
        val rawFacts = reading.optJSONArray("facts") ?: throw PrivateOcrClientException(502)
        if(rawFacts.length() > 12) throw PrivateOcrClientException(502)
        val facts = (0 until rawFacts.length()).map { i ->
            val fact = rawFacts.optJSONObject(i) ?: throw PrivateOcrClientException(502)
            ReadOcrFact(
                label = fact.optString("labelAr").take(80),
                value = if(fact.isNull("value")) null else fact.optString("value").take(80),
                state = fact.optString("status").take(24),
                page = if(fact.isNull("page")) null else fact.optInt("page").takeIf { it in 1..8 },
                source = if(fact.isNull("source")) null else fact.optString("source").take(12),
                confidence = if(fact.isNull("confidencePercent")) null else fact.optInt("confidencePercent").takeIf { it in 0..100 },
            )
        }
        fun pages(key: String): List<Int> {
            val arr = reading.optJSONArray(key) ?: return emptyList()
            if(arr.length() > 8) throw PrivateOcrClientException(502)
            return (0 until arr.length()).map { arr.optInt(it) }.filter { it in 1..8 }
        }
        val kind=reading.optString("familyKind","UNKNOWN")
        if(kind !in setOf("OWNERSHIP_CERTIFICATE","SHARED_OWNERSHIP_CERTIFICATE",
                "SALE_DEED","TRANSFER_CARD","POSSESSION_REPORT","FINANCIAL_RECEIPT",
                "PROPERTY_SERVICE_RECEIPT","UNKNOWN"))
            throw PrivateOcrClientException(502)
        val rawFamily=reading.optJSONArray("familyFacts")
        if(rawFamily!=null&&rawFamily.length()>24)throw PrivateOcrClientException(502)
        val familyFacts=(0 until (rawFamily?.length()?:0)).map{i->
            val item=rawFamily?.optJSONObject(i)?:throw PrivateOcrClientException(502)
            ReadFamilyFact(
                id=item.optString("id").take(40),
                value=if(item.isNull("value"))null else item.optString("value").take(100),
                status=item.optString("status").take(24),
                page=if(item.isNull("page"))null else item.optInt("page").takeIf{it in 1..8},
                confidencePercent=if(item.isNull("confidencePercent"))null
                    else item.optInt("confidencePercent").takeIf{it in 0..100}
            )
        }
        ReadOcrResult(evidenceId,"READY",facts,pages("missingPages"),
            pages("qualityReviewPages"),reading.optString("declaredAreaComparison"),
            reading.optInt("pagesReceived").coerceIn(0,8),kind,familyFacts,
            reading.optInt("documentSupportLevel",0).coerceIn(0,5))
    } finally { conn.disconnect() }
}
