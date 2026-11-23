package tn.malak.app.ui.explore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tn.malak.app.ui.readPilotBytes
import java.io.IOException

/**
 * Published-only public feed; never carries owner bearer, OTP, private coordinates or write routes.
 * This adapter is for the currently deployed Pilot public API, not the newer /api/v1/discovery API.
 */
internal class PilotPublicDiscoverySource(private val origin: String) : DiscoverySource {
    private suspend fun publicGet(path: String, missingAllowed: Boolean = false): JSONObject? =
        withContext(Dispatchers.IO) {
            val connection = discoveryConnection(origin + path, "application/json").apply {
                // Identify the QA app explicitly instead of using the generic Dalvik URLConnection UA.
                setRequestProperty("User-Agent", "Amlak-Android/0.5.1 (public-feed-readonly-qa)")
            }
            try {
                val status = connection.responseCode
                if (missingAllowed && status == 404) return@withContext null
                if (status != 200) throw IOException("Public discovery HTTP $status")
                val body = connection.inputStream.use { readPilotBytes(it, 1024 * 1024) }
                JSONObject(body.toString(Charsets.UTF_8)).also {
                    if (!it.optBoolean("ok", false)) throw IOException("Invalid public discovery response")
                }
            } finally {
                connection.disconnect()
            }
        }

    // Map, list and place suggestions may request the same public feed at once.
    // Share an *in-progress* network response, never a stale completed response.
    private val sharedRead = PublicFeedSingleFlight {
        val rows = requireNotNull(publicGet("/api/public/listings")).getJSONArray("listings")
        (0 until rows.length()).mapNotNull { i ->
            decodePilotListing(rows.getJSONObject(i), origin)
        }
    }

    private suspend fun listings(): List<DiscoveryListing> = sharedRead.get()

    override suspend fun list(query: DiscoveryQuery, cursor: String?) =
        DiscoveryPage(listings().filter { it.matchesPilotQuery(query) })

    override suspend fun markers(query: DiscoveryQuery, zoom: Int) =
        DiscoveryMarkers(listings().filter { it.point != null && it.matchesPilotQuery(query) })

    override suspend fun detail(id: String) = listings().firstOrNull { it.id == id }

    override suspend fun parcel(id: String): PublicParcel {
        if (!PilotRecordId.matches(id)) return PublicParcel()
        return publicGet("/api/v1/listings/$id/parcel", missingAllowed = true)
            ?.let { decodePublicParcel(it, origin, id) } ?: PublicParcel()
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
    val point = publicPilotListingPoint(row.optString("latitude"), row.optString("longitude"))
    val title = row.optString("title").trim().takeIf(String::isNotEmpty) ?: return null
    val region = row.optString("region").trim()
    val price = row.optString("price").trim().takeIf { it.toBigDecimalOrNull()?.signum() == 1 }.orEmpty()
    val area = row.optString("area").trim().takeIf { it.toBigDecimalOrNull()?.signum() == 1 }
    return DiscoveryListing(id, title, region,
        publicListingKind(row.optString("kind")), price, area, null, point,
        if (point != null) "APPROXIMATE" else "AREA_ONLY",
        photo?.let { "$origin/api/public/photos/$it" },
        row.optString("description").trim().takeIf(String::isNotBlank))
}

/**
 * Legacy public-feed coordinates are not guaranteed to carry location-accuracy
 * metadata. Accept only a plausible Tunisian public point; 0,0 and coordinates
 * outside the country are *not* pins. This is a coarse data-quality guard, not
 * cadastral/ownership validation or an assertion of exact public accuracy.
 */
internal fun publicPilotListingPoint(latitude: String?, longitude: String?): DiscoveryPoint? =
    discoveryPoint(latitude?.toDoubleOrNull(), longitude?.toDoubleOrNull(), "APPROXIMATE")
        ?.takeIf { it.latitude in 29.0..39.0 && it.longitude in 7.0..13.0 }

/** The current public Pilot feed does NOT include a kind field. Unknown is not LAND. */
internal fun publicListingKind(value: String): String =
    value.trim().uppercase(java.util.Locale.ROOT).takeIf {
        it in setOf("LAND", "APARTMENT", "HOUSE", "VILLA", "FACTORY")
    } ?: "UNKNOWN"

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
