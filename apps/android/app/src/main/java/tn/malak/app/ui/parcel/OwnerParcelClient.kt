package tn.malak.app.ui.parcel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tn.malak.app.ui.OwnerEvidenceSession
import tn.malak.app.ui.explore.DiscoveryPoint
import tn.malak.app.ui.readPilotBytes
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal data class ParcelScan(val frame: ParcelFrame, val outline: List<ParcelPoint>, val page: Int)
internal data class ParcelOcrLength(
    val from: String, val to: String, val metres: String, val page: Int, val reviewNeeded: Boolean
)
internal data class ParcelPlanFact(val key: String, val value: String, val page: Int)
internal data class ParcelEditEdge(val ordinal: Int, val lengthM: String, val label: String? = null)
internal data class OwnerPlanDraft(
    val parcelId: String?,
    val vertices: List<ParcelPoint>,
    val titleReference: String,
    val plotReference: String,
    val edges: List<ParcelEditEdge>,
    val statedAreaM2: String? = null
)

private val ParcelUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

internal fun parseParcelScans(reading: JSONObject): List<ParcelScan> {
    val scans = reading.optJSONArray("parcelPlanScans") ?: return emptyList()
    if (scans.length() > 8) return emptyList()
    return (0 until scans.length()).mapNotNull { index ->
        val scan = scans.optJSONObject(index) ?: return@mapNotNull null
        if (scan.optString("status") != "CANDIDATE") return@mapNotNull null
        val page = scan.optInt("page").takeIf { it in 1..8 } ?: return@mapNotNull null
        val width = scan.optInt("width").takeIf { it in 1..100000 } ?: return@mapNotNull null
        val height = scan.optInt("height").takeIf { it in 1..100000 } ?: return@mapNotNull null
        val outline = scan.optJSONObject("outline") ?: return@mapNotNull null
        if (outline.optString("unit") != "PIXEL" || outline.optInt("page") != page) return@mapNotNull null
        val frame = ParcelFrame(width, height)
        val points = decodeParcelRing(outline.optJSONArray("ring")) ?: return@mapNotNull null
        val validated = frame.validatedRing(points) ?: return@mapNotNull null
        ParcelScan(frame, openParcelRing(validated), page)
    }
}

internal fun parseParcelOcrLengths(reading: JSONObject): List<ParcelOcrLength> {
    val measurements = reading.optJSONArray("parcelEdgeMeasurements") ?: return emptyList()
    if (measurements.length() > 128) return emptyList()
    return (0 until measurements.length()).mapNotNull { index ->
        val edge = measurements.optJSONObject(index) ?: return@mapNotNull null
        if (edge.optString("status") !in setOf("CANDIDATE", "LOW_CONFIDENCE", "CONFLICT")) return@mapNotNull null
        val from = edge.optString("from").takeIf { it.length in 1..4 } ?: return@mapNotNull null
        val to = edge.optString("to").takeIf { it.length in 1..4 } ?: return@mapNotNull null
        val page = edge.optJSONObject("evidence")?.optInt("page")?.takeIf { it in 1..8 } ?: return@mapNotNull null
        val metres = parseParcelLength(edge.optString("lengthM")) ?: return@mapNotNull null
        ParcelOcrLength(from, to, metres, page, edge.optString("status") != "CANDIDATE")
    }
}

internal fun parseParcelPlanFacts(reading: JSONObject): List<ParcelPlanFact> {
    val facts = reading.optJSONArray("parcelPlanFacts") ?: return emptyList()
    if (facts.length() > 24) return emptyList()
    return (0 until facts.length()).flatMap { index ->
        val fact = facts.optJSONObject(index) ?: return@flatMap emptyList()
        val key = fact.optString("key")
        if (key !in setOf("titleReference", "plotReference", "printedAreaM2") ||
            fact.optString("status") != "CANDIDATE") return@flatMap emptyList()
        val value = fact.optionalString("value").takeIf { it.isNotBlank() } ?: return@flatMap emptyList()
        val evidence = fact.optJSONArray("evidence") ?: return@flatMap emptyList()
        if (evidence.length() > 128) return@flatMap emptyList()
        (0 until evidence.length()).mapNotNull { evidenceIndex ->
            val page = evidence.optJSONObject(evidenceIndex)?.optInt("page") ?: return@mapNotNull null
            if (page in 1..8) ParcelPlanFact(key, value, page) else null
        }.distinct()
    }
}

internal fun List<ParcelPlanFact>.selectedFact(key: String, page: Int): String? =
    filter { it.key == key && it.page == page }.map(ParcelPlanFact::value).distinct().singleOrNull()

internal suspend fun loadOwnerParcelReview(
    session: OwnerEvidenceSession, sourceEvidenceId: String, page: Int, localUnit: String, frame: ParcelFrame
): OwnerPlanDraft? {
    val json = ownerParcelCall(session)
    val geometries = json.optJSONArray("geometries") ?: return null
    for (index in 0 until geometries.length()) {
        val geometry = geometries.optJSONObject(index) ?: continue
        if (geometry.optString("sourceEvidenceId") != sourceEvidenceId ||
            geometry.optString("method") != "DOCUMENT_ONLY" || geometry.optInt("localPage") != page ||
            geometry.optString("localUnit") != localUnit) continue
        val polygon = geometry.optJSONObject("localOutline") ?: continue
        if (polygon.optString("type") != "Polygon") continue
        val points = decodeParcelRing(polygon.optJSONArray("coordinates")?.optJSONArray(0)) ?: continue
        val vertices = frame.validatedRing(points)?.let(::openParcelRing) ?: continue
        val parcelId = geometry.optString("parcelId").takeIf(ParcelUuid::matches) ?: continue
        val jsonEdges = geometry.optJSONArray("edges") ?: JSONArray()
        val edges = (0 until jsonEdges.length()).mapNotNull { edgeIndex ->
            val edge = jsonEdges.optJSONObject(edgeIndex) ?: return@mapNotNull null
            val ordinal = edge.optInt("ordinal", -1).takeIf { it in 1..vertices.size }
                ?: edge.optString("label").toIntOrNull()?.takeIf { it in 1..vertices.size }
                ?: return@mapNotNull null
            val length = parseParcelLength(edge.optString("lengthM")) ?: return@mapNotNull null
            ParcelEditEdge(ordinal, length, edge.optString("label").take(24).takeIf { it.isNotBlank() })
        }
        return OwnerPlanDraft(parcelId, vertices, geometry.optionalString("titleReference"),
            geometry.optionalString("plotReference"), edges,
            geometry.optString("statedAreaM2").let(::parseParcelLength))
    }
    return null
}

internal suspend fun saveOwnerParcelReview(
    session: OwnerEvidenceSession, sourceEvidenceId: String, page: Int, localUnit: String,
    draft: OwnerPlanDraft
): String {
    require(ParcelUuid.matches(sourceEvidenceId) && page in 1..8 && localUnit in setOf("PIXEL", "PAGE_POINT"))
    val payload = JSONObject()
        .put("method", "DOCUMENT_ONLY").put("sourceEvidenceId", sourceEvidenceId)
        .put("localPage", page).put("localUnit", localUnit)
        .put("vertices", JSONArray(draft.vertices.map { JSONArray(listOf(it.x, it.y)) }))
        .put("edges", JSONArray(draft.edges.map { edge ->
            JSONObject().put("ordinal", edge.ordinal).put("lengthM", edge.lengthM)
                .put("sourcePage", page).apply { edge.label?.let { put("label", it) } }
        }))
    draft.statedAreaM2?.let { payload.put("statedAreaM2", it) }
    draft.parcelId?.let { payload.put("parcelId", it) }
    if (draft.titleReference.isNotBlank()) payload.put("titleReference", draft.titleReference.trim())
    if (draft.plotReference.isNotBlank()) payload.put("plotReference", draft.plotReference.trim())
    val id = ownerParcelCall(session, payload).optString("id")
    check(ParcelUuid.matches(id))
    return id
}

/** Owner trace is not a georeferenced document or verified boundary. */
internal suspend fun saveOwnerMapBoundary(session:OwnerEvidenceSession,points:List<DiscoveryPoint>):String {
    require(measureOwnerMap(points)!=null)
    val payload=JSONObject().put("method","OWNER_TRACED_ESTIMATE")
        .put("vertices",JSONArray(points.map{JSONArray(listOf(it.longitude,it.latitude))}))
    val id=ownerParcelCall(session,payload).optString("id")
    check(ParcelUuid.matches(id))
    return id
}

private suspend fun ownerParcelCall(
    session: OwnerEvidenceSession, body: JSONObject? = null
): JSONObject = withContext(Dispatchers.IO) {
    require(ParcelUuid.matches(session.caseId))
    val base = URI(session.httpsBaseUrl.trim())
    require(base.scheme == "https" && !base.host.isNullOrBlank() && base.userInfo == null &&
        base.rawQuery == null && base.rawFragment == null && base.path.orEmpty() in setOf("", "/"))
    val url = base.resolve("/api/v1/owner/cases/${session.caseId}/parcel-geometries").toURL()
    val connection = url.openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = 10000
        connection.readTimeout = 18000
        connection.setRequestProperty("Authorization", "Bearer ${session.bearer}")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-store")
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            try { connection.outputStream.use { it.write(bytes) } } finally { bytes.fill(0) }
        }
        check(connection.responseCode in if (body == null) 200..200 else 200..201)
        val bytes = connection.inputStream.use { readPilotBytes(it, 256 * 1024) }
        try { JSONObject(bytes.toString(Charsets.UTF_8)) } finally { bytes.fill(0) }
    } finally {
        connection.disconnect()
    }
}

private fun JSONObject.optionalString(key: String): String = if (isNull(key)) "" else optString(key).take(100)

private fun decodeParcelRing(ring: JSONArray?): List<ParcelPoint>? {
    if (ring == null || ring.length() !in 3..129) return null
    return (0 until ring.length()).map { index ->
        val point = ring.optJSONArray(index) ?: return null
        if (point.length() != 2) return null
        val coordinate = ParcelPoint(point.optDouble(0), point.optDouble(1))
        if (!coordinate.x.isFinite() || !coordinate.y.isFinite()) return null
        coordinate
    }
}
