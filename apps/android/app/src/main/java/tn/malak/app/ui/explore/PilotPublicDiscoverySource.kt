package tn.malak.app.ui.explore

import org.json.JSONObject
import tn.malak.app.ui.OwnerPairException
import tn.malak.app.ui.PilotListingClient

/** The Pilot gateway exposes its published records as a small public feed. */
internal class PilotPublicDiscoverySource(private val origin: String) : DiscoverySource {
    private suspend fun listings(): List<DiscoveryListing> {
        val rows = PilotListingClient.call(null, "/api/public/listings").getJSONArray("listings")
        return (0 until rows.length()).mapNotNull { i -> decodePilotListing(rows.getJSONObject(i), origin) }
    }

    override suspend fun list(query: DiscoveryQuery, cursor: String?) =
        DiscoveryPage(listings().filter { it.matchesPilotQuery(query) })

    override suspend fun markers(query: DiscoveryQuery, zoom: Int) =
        DiscoveryMarkers(listings().filter { it.point != null && it.matchesPilotQuery(query) })

    override suspend fun detail(id: String) = listings().firstOrNull { it.id == id }

    override suspend fun parcel(id: String): PublicParcel {
        if (!PilotRecordId.matches(id)) return PublicParcel()
        return try {
            decodePublicParcel(PilotListingClient.call(null, "/api/v1/listings/$id/parcel"), origin, id)
        } catch (error: OwnerPairException) {
            if (error.statusCode == 404) PublicParcel() else throw error
        }
    }

    override suspend fun suggest(text: String, language: String): List<DiscoveryPlace> {
        val search = foldPlaceName(text)
        return listings().mapNotNull { listing ->
            val point = listing.point ?: return@mapNotNull null
            val key = foldPlaceName(listing.region)
            if (key.contains(search)) DiscoveryPlace(key, listing.region, point) else null
        }.distinctBy(DiscoveryPlace::id).take(12)
    }
}

internal fun decodePilotListing(row: JSONObject, origin: String): DiscoveryListing? {
    val id = row.optString("id")
    if (!PilotRecordId.matches(id)) return null
    val photo = row.optJSONArray("photos")?.optString(0)?.takeIf(PilotRecordId::matches)
    val point = discoveryPoint(row.optString("latitude").toDoubleOrNull(),
        row.optString("longitude").toDoubleOrNull(), "APPROXIMATE")
    return DiscoveryListing(id, row.getString("title"), row.getString("region"),
        row.optString("kind"), row.getString("price"), row.optString("area"), null, point,
        if (point != null) "APPROXIMATE" else "AREA_ONLY",
        photo?.let { "$origin/api/public/photos/$it" }, row.optString("description").takeIf(String::isNotBlank))
}

private val PilotRecordId = Regex("[0-9a-f-]{36}")

internal fun DiscoveryListing.matchesPilotQuery(query: DiscoveryQuery): Boolean {
    val f = query.filters
    return (point == null || point in query.bounds) &&
        (query.radar == null || point != null && query.radar.contains(point)) &&
        (query.placeId == null || query.placeId == foldPlaceName(region)) &&
        (f.kind == null || f.kind == kind) &&
        (f.minRooms == null || (rooms ?: -1) >= f.minRooms) &&
        fitsRange(priceTnd, f.minPrice, f.maxPrice) && fitsRange(areaM2, f.minArea, f.maxArea)
}

private fun fitsRange(value: String?, min: String?, max: String?): Boolean {
    if (min == null && max == null) return true
    val number = value?.toBigDecimalOrNull() ?: return false
    return (min == null || number >= min.toBigDecimal()) && (max == null || number <= max.toBigDecimal())
}
