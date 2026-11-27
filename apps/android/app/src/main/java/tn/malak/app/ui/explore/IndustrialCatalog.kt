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
    val lots: List<IndustrialLot>, val documents: List<IndustrialDocument>
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

internal data class IndustrialGovernorate(val place: AtlasPlace, val lots: Int, val focus: DiscoveryPoint)

internal fun industrialGovernorates(places: List<AtlasPlace>, zones: List<IndustrialZone>): List<IndustrialGovernorate> {
    val zonesByGovernorate = zones.groupBy { it.governorate }
    return places.filter { it.id.startsWith("TN-") }.map { place ->
        val local = zonesByGovernorate[place.arabic].orEmpty()
        val focus = local.filter { it.shapeCount > 0 }.mapNotNull { it.center }.minByOrNull {
            kotlin.math.hypot(it.latitude - place.point.latitude, it.longitude - place.point.longitude)
        } ?: place.point
        IndustrialGovernorate(place, local.sumOf { it.shapeCount }, focus)
    }
}

internal data class IndustrialCatalog(val capturedAt: String, val zones: List<IndustrialZone>,
    val governorates: List<IndustrialGovernorate>) {
    fun mapFeatures(language: String): FeatureCollection = FeatureCollection.fromFeatures(zones.mapNotNull { zone ->
        val point = zone.center ?: return@mapNotNull null
        Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply {
            addStringProperty("id", zone.id)
            addStringProperty("name", zone.label(language))
        }
    })
}

// Radar results are local AFI subdivision records, never inferred sale advertisements.
internal data class IndustrialRadarHit(val zone: IndustrialZone, val lotsInside: Int, val approximate: Boolean)

internal fun findIndustrialRadarHits(
    radar: DiscoveryRadar, catalog: IndustrialCatalog, loadPlan: (IndustrialZone) -> IndustrialPlan
): List<IndustrialRadarHit> = catalog.zones.mapNotNull { zone ->
    // A 10 km margin includes lots towards the edge of a zone without loading
    // polygon-heavy plans on the opposite side of Tunisia on each radar search.
    if (zone.center != null && radar.distanceKm(zone.center) > radar.radiusKm + 10) {
        return@mapNotNull null
    }
    val plan = runCatching { loadPlan(zone) }.getOrNull() ?: return@mapNotNull null
    val anchored = plan.lots.mapNotNull(IndustrialLot::anchor)
    val exactCount = anchored.count(radar::contains)
    when {
        exactCount > 0 -> IndustrialRadarHit(zone, exactCount, approximate = false)
        anchored.isEmpty() && zone.center?.let(radar::contains) == true ->
            IndustrialRadarHit(zone, plan.lots.size, approximate = true)
        else -> null
    }
}.sortedWith(compareByDescending<IndustrialRadarHit> { it.lotsInside }.thenBy { it.zone.id })

internal fun loadIndustrialCatalog(context: Context): IndustrialCatalog {
    val catalog = context.readAfi("overview")
    val rows = catalog.getJSONArray("zones")
    val zones = List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        IndustrialZone(row.getString("id"), row.text("name_ar").orEmpty(), row.text("name_fr").orEmpty(),
            row.text("governorate").orEmpty(), row.point("center"), row.getInt("shapes"))
    }
    return IndustrialCatalog(catalog.getString("captured_at"), zones, industrialGovernorates(readAtlasPlaces(context), zones))
}

private fun lotShape(zoneId: String, row: JSONObject): Feature? {
    val shape = row.optJSONObject("geometry") ?: return null
    val fields = JSONObject().put("id", row.getString("id")).put("zoneId", zoneId)
        .put("number", row.text("number").orEmpty())
    return Feature.fromJson(JSONObject().put("type", "Feature")
        .put("geometry", shape).put("properties", fields).toString())
}

internal fun loadIndustrialShapes(context: Context, catalog: IndustrialCatalog): FeatureCollection {
    val result = mutableListOf<Feature>()
    for (zone in catalog.zones.filter { it.shapeCount > 0 }) {
        val rows = context.readAfi(zone.id).getJSONArray("lots")
        for (i in 0 until rows.length()) lotShape(zone.id, rows.getJSONObject(i))?.let(result::add)
    }
    return FeatureCollection.fromFeatures(result)
}

internal fun loadIndustrialPlan(context: Context, zone: IndustrialZone): IndustrialPlan {
    require(Regex("[0-9]{1,12}").matches(zone.id))
    val plan = context.readAfi(zone.id)
    val rows = plan.getJSONArray("lots")
    val lots = List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        val id = row.getString("id")
        val number = row.text("number")
        val anchor = row.point("anchor")
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
        documents)
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
