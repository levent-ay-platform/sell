package tn.malak.app.ui.explore

import org.json.JSONArray
import org.json.JSONObject

internal enum class BoundaryKind {
    Official, Georeferenced, Estimate;

    companion object {
        fun read(method: String): BoundaryKind? = when (method) {
            "ATLAS_OFFICIAL_BOUNDARY" -> Official
            "MALAK_DOC_GEOREFERENCED" -> Georeferenced
            "OWNER_TRACED_ESTIMATE" -> Estimate
            else -> null
        }
    }
}

internal data class PublicEdge(val label: String, val lengthM: String)
internal data class PlanPoint(val x: Float, val y: Float)
internal data class LocalParcelProof(
    val title: String, val source: String, val plot: String, val area: String,
    val vertices: List<PlanPoint>, val document: PublicPropertyDocument
)
internal data class PublicParcelBoundary(
    val id: String,
    val kind: BoundaryKind,
    val geometry: String,
    val points: List<DiscoveryPoint>,
    val labelRing: List<DiscoveryPoint>,
    val titleReference: String?,
    val plotReference: String?,
    val edges: List<PublicEdge>
)
internal data class PublicPropertyDocument(
    val id: String, val kind: String, val mime: String, val location: String, val startPage: Int = 0
)
internal data class PublicParcel(
    val boundaries: List<PublicParcelBoundary> = emptyList(),
    val documents: List<PublicPropertyDocument> = emptyList(),
    val proof: LocalParcelProof? = null,
    val verifiedParcel: Boolean = false
) {
    val showVerifiedBadge: Boolean
        get() = verifiedParcel && boundaries.any { it.kind != BoundaryKind.Estimate }
}

internal fun documentUrl(origin: String, listingId: String, documentId: String, path: String): String? =
    path.takeIf { it == "/api/v1/listings/$listingId/documents/$documentId" }?.let { origin + it }

internal fun parcelCamera(points: List<DiscoveryPoint>): DiscoveryCamera? {
    if (points.isEmpty()) return null
    val west = points.minOf { it.longitude }
    val east = points.maxOf { it.longitude }
    val south = points.minOf { it.latitude }
    val north = points.maxOf { it.latitude }
    val span = maxOf(east - west, north - south, .00008)
    val zoom = (kotlin.math.ln(360 / span / 4) / kotlin.math.ln(2.0)).coerceIn(7.0, 18.0)
    return DiscoveryCamera(DiscoveryPoint((south + north) / 2, (west + east) / 2), zoom,
        DiscoveryBounds(west, south, east, north))
}

internal fun decodePublicParcel(body: JSONObject, base: String, listingId: String): PublicParcel {
    val boundaries = body.array("boundaries").toObjects().mapNotNull { row ->
        val kind = BoundaryKind.read(row.getString("method")) ?: return@mapNotNull null
        val geometry = row.optJSONObject("geometry") ?: return@mapNotNull null
        val coordinates = geometry.optJSONArray("coordinates") ?: return@mapNotNull null
        val polygons = when (geometry.optString("type")) {
            "Polygon" -> listOf(coordinates)
            "MultiPolygon" -> List(coordinates.length()) { coordinates.getJSONArray(it) }
            else -> return@mapNotNull null
        }
        val rings = polygons.map { polygon ->
            val vertices = polygon.optJSONArray(0) ?: return@mapNotNull null
            if (vertices.length() !in 4..5000) return@mapNotNull null
            List(vertices.length()) { index ->
                val vertex = vertices.getJSONArray(index)
                discoveryPoint(vertex.getDouble(1), vertex.getDouble(0), "APPROXIMATE") ?: return@mapNotNull null
            }
        }
        val points = rings.flatten()
        if (points.isEmpty() || points.size > 5000) return@mapNotNull null
        val edges = row.array("edges").toObjects().take(500).mapNotNull { edge ->
            val label = edge.value("label") ?: return@mapNotNull null
            val length = edge.value("lengthM") ?: return@mapNotNull null
            if (length.toBigDecimalOrNull()?.signum() != 1) null else PublicEdge(label.take(64), length.take(24))
        }
        PublicParcelBoundary(row.getString("id"), kind, geometry.toString(), points,
            rings.singleOrNull().orEmpty(), row.value("titleReference"), row.value("plotReference"), edges)
    }
    val documents = body.array("documents").toObjects().take(24).mapNotNull { row ->
        val id = row.getString("id")
        if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) return@mapNotNull null
        val mime = row.getString("mime")
        if (mime !in setOf("application/pdf", "image/png", "image/jpeg", "image/webp")) return@mapNotNull null
        val location = documentUrl(base, listingId, id, row.getString("url")) ?: return@mapNotNull null
        PublicPropertyDocument(id, row.getString("kind"), mime, location)
    }
    return PublicParcel(boundaries, documents, verifiedParcel = body.opt("verifiedParcel") == true)
}

private fun JSONObject.value(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: JSONArray()

private fun JSONArray.toObjects(): List<JSONObject> = List(length()) { getJSONObject(it) }
