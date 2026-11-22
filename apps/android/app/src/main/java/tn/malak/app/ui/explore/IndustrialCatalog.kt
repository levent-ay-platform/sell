package tn.malak.app.ui.explore

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.net.URI
import kotlin.math.ln

internal const val IndustrialAgencyMap = "https://afi.e-industrie.gov.tn/"

internal data class IndustrialZone(
    val id: String, val arabic: String, val french: String, val governorate: String,
    val center: DiscoveryPoint?, val shapeCount: Int
) {
    fun label(language: String) = if (language == "ar") arabic.ifBlank { french } else french.ifBlank { arabic }
    fun matches(input: String) = listOf(arabic, french, governorate).any { foldPlaceName(input) in foldPlaceName(it) }
}

internal data class IndustrialLot(
    val id: String, val number: String?, val area: String?, val estimatedArea: String?, val price: String?,
    val priceIsForLot: Boolean, val listed: Boolean, val availabilityRecorded: Boolean, val anchor: DiscoveryPoint?
)

internal data class IndustrialDocument(val title: String, val page: String, val pdf: String?)

internal data class IndustrialPlan(
    val zone: IndustrialZone, val layoutDate: String, val offerDate: String,
    val lots: List<IndustrialLot>, val documents: List<IndustrialDocument>, val shapes: FeatureCollection
) {
    fun camera(fallback: DiscoveryPoint): Pair<DiscoveryPoint, Double> {
        val locations = lots.mapNotNull(IndustrialLot::anchor)
        if (locations.isEmpty()) return (zone.center ?: fallback) to 14.0
        val west = locations.minOf { it.longitude }
        val east = locations.maxOf { it.longitude }
        val north = locations.maxOf { it.latitude }
        val south = locations.minOf { it.latitude }
        val span = maxOf(east - west, north - south, .0005)
        val zoom = (ln(360.0 / (span * 2.5)) / ln(2.0)).coerceIn(11.0, 16.0)
        return DiscoveryPoint((south + north) / 2, (west + east) / 2) to zoom
    }
}

internal data class IndustrialCatalog(val capturedAt: String, val zones: List<IndustrialZone>) {
    fun mapFeatures(language: String): FeatureCollection = FeatureCollection.fromFeatures(zones.mapNotNull { zone ->
        val point = zone.center ?: return@mapNotNull null
        Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply {
            addStringProperty("id", zone.id)
            addStringProperty("name", zone.label(language))
        }
    })
}

internal fun loadIndustrialCatalog(context: Context): IndustrialCatalog {
    val catalog = context.readAfi("overview")
    val rows = catalog.getJSONArray("zones")
    return IndustrialCatalog(catalog.getString("captured_at"), List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        IndustrialZone(row.getString("id"), row.text("name_ar").orEmpty(), row.text("name_fr").orEmpty(),
            row.text("governorate").orEmpty(), row.point("center"), row.getInt("shapes"))
    })
}

internal fun loadIndustrialPlan(context: Context, zone: IndustrialZone): IndustrialPlan {
    require(Regex("[0-9]{1,12}").matches(zone.id))
    val plan = context.readAfi(zone.id)
    val rows = plan.getJSONArray("lots")
    val shapes = mutableListOf<Feature>()
    val lots = List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        val id = row.getString("id")
        val number = row.text("number")
        val anchor = row.point("anchor")
        row.optJSONObject("geometry")?.let { shape ->
            val fields = JSONObject().put("id", id).put("number", number.orEmpty())
            shapes += Feature.fromJson(JSONObject().put("type", "Feature")
                .put("geometry", shape).put("properties", fields).toString())
        }
        IndustrialLot(id, number, row.text("area_m2"), row.text("estimated_area_m2"), row.text("price_dtn_m2"),
            row.text("price_basis") == "lot", row.optBoolean("listed"), row.text("offer_status") == "available", anchor)
    }
    val documents = plan.getJSONArray("documents").let { docs ->
        (0 until docs.length()).mapNotNull { index ->
            val row = docs.getJSONObject(index)
            val page = industrialDocumentUrl(row.text("page")) ?: return@mapNotNull null
            IndustrialDocument(row.getString("title"), page, industrialDocumentUrl(row.text("pdf"), pdf = true))
        }
    }
    return IndustrialPlan(zone, plan.getString("layout_date"), plan.getString("offer_date"), lots,
        documents, FeatureCollection.fromFeatures(shapes))
}

internal fun industrialDocumentUrl(raw: String?, pdf: Boolean = false): String? {
    val uri = runCatching { URI(raw ?: return null) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host !in setOf("afi.nat.tn", "www.afi.nat.tn") ||
        uri.userInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null
    ) return null
    if (pdf && (!uri.path.startsWith("/wp-content/uploads/") || !uri.path.endsWith(".pdf", ignoreCase = true))) {
        return null
    }
    return uri.toASCIIString()
}

private fun Context.readAfi(file: String) =
    assets.open("afi/$file.json").bufferedReader().use { JSONObject(it.readText()) }

private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun JSONObject.point(key: String): DiscoveryPoint? {
    val row: JSONArray = optJSONArray(key) ?: return null
    return discoveryPoint(row.optDouble(1), row.optDouble(0), "APPROXIMATE")
}
