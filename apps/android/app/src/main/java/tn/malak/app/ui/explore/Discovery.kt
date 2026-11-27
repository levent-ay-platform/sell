package tn.malak.app.ui.explore

import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

internal data class DiscoveryPoint(val latitude: Double, val longitude: Double)

internal data class DiscoveryBounds(
    val west: Double, val south: Double, val east: Double, val north: Double
) {
    fun encode(): String = "$west,$south,$east,$north"

    operator fun contains(point: DiscoveryPoint): Boolean =
        point.longitude in west..east && point.latitude in south..north

    companion object {
        fun decode(value: String): DiscoveryBounds? {
            val parts = value.split(',').map { it.toDoubleOrNull() ?: return null }
            if (parts.size != 4) return null
            val (west, south, east, north) = parts
            if (west !in -180.0..180.0 || east !in -180.0..180.0 ||
                south !in -85.051129..85.051129 || north !in -85.051129..85.051129 ||
                east <= west || north <= south || east - west > 16 || north - south > 16
            ) return null
            return DiscoveryBounds(west, south, east, north)
        }
    }
}

internal val InitialBounds = DiscoveryBounds(7.4, 30.0, 12.0, 37.7)
internal val InitialCenter = DiscoveryPoint(34.1, 9.7)
internal const val InitialZoom = 5.25

internal data class DiscoveryFilters(
    val kind: String? = null,
    val minPrice: String? = null,
    val maxPrice: String? = null,
    val minArea: String? = null,
    val maxArea: String? = null,
    val minRooms: Int? = null
) {
    companion object {
        fun parse(
            kind: String?, minPrice: String, maxPrice: String,
            minArea: String, maxArea: String, minRooms: String
        ): DiscoveryFilters? {
            val lowerPrice = filterDecimal(minPrice, 15) ?: return null
            val upperPrice = filterDecimal(maxPrice, 15) ?: return null
            val lowerArea = filterDecimal(minArea, 12) ?: return null
            val upperArea = filterDecimal(maxArea, 12) ?: return null
            val rooms = filterDecimal(minRooms, 2) ?: return null
            if (lowerPrice.isNotEmpty() && upperPrice.isNotEmpty() &&
                BigDecimal(lowerPrice) > BigDecimal(upperPrice)
            ) return null
            if (lowerArea.isNotEmpty() && upperArea.isNotEmpty() &&
                BigDecimal(lowerArea) > BigDecimal(upperArea)
            ) return null
            val roomCount = rooms.toIntOrNull()
            if (rooms.isNotEmpty() && (roomCount == null || roomCount !in 0..30)) return null
            return DiscoveryFilters(kind, lowerPrice.ifEmpty { null }, upperPrice.ifEmpty { null },
                lowerArea.ifEmpty { null }, upperArea.ifEmpty { null }, roomCount)
        }
    }
}

private val digitsWithFraction = Regex("""[0-9]+(?:\.[0-9]{1,3})?""")
private fun filterDecimal(value: String, wholeDigits: Int): String? {
    val cleaned = buildString {
        for (char in value.trim()) {
            val digit = char.digitToIntOrNull()
            when {
                digit != null -> append(digit)
                char == ',' || char == '٫' -> append('.')
                else -> append(char)
            }
        }
    }
    if (cleaned.isEmpty()) return cleaned
    if (!digitsWithFraction.matches(cleaned) || cleaned.substringBefore('.').length > wholeDigits) return null
    return cleaned
}

private val nonSpacingMarks = Regex("\\p{Mn}")
internal fun foldPlaceName(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace(nonSpacingMarks, "")
        .replace("ـ", "")
        .lowercase(Locale.ROOT)

internal fun discoveryPoint(latitude: Double?, longitude: Double?, accuracy: String): DiscoveryPoint? =
    if (accuracy in setOf("APPROXIMATE", "EXACT_CONSENTED") &&
        latitude != null && latitude in -85.051129..85.051129 &&
        longitude != null && longitude in -180.0..180.0
    ) DiscoveryPoint(latitude, longitude) else null

internal fun discoveryParameters(
    bounds: DiscoveryBounds, placeId: String?, filters: DiscoveryFilters
): Map<String, String> = buildMap {
    put("bbox", bounds.encode())
    placeId?.let { put("placeId", it) }
    filters.kind?.let { put("kind", it) }
    filters.minPrice?.let { put("minPrice", it) }
    filters.maxPrice?.let { put("maxPrice", it) }
    filters.minArea?.let { put("minArea", it) }
    filters.maxArea?.let { put("maxArea", it) }
    filters.minRooms?.let { put("minRooms", it.toString()) }
}

internal data class DiscoveryQuery(
    val bounds: DiscoveryBounds, val placeId: String?, val filters: DiscoveryFilters,
    val radar: DiscoveryRadar? = null
) {
    fun parameters() = discoveryParameters(bounds, placeId, filters) +
        (radar?.let { mapOf("radar" to it.encode()) } ?: emptyMap())
}

internal data class DiscoveryListing(
    val id: String,
    val title: String,
    val region: String,
    val kind: String,
    val priceTnd: String,
    val areaM2: String?,
    val rooms: Int?,
    val point: DiscoveryPoint?,
    val locationAccuracy: String,
    val photoUrl: String?,
    val description: String?
)

internal data class DiscoveryPlace(
    val id: String, val label: String, val point: DiscoveryPoint, val serverPlaceId: String? = id
)
internal data class DiscoveryCluster(val id: String, val point: DiscoveryPoint, val count: Int)
internal data class DiscoveryPage(val listings: List<DiscoveryListing>, val nextCursor: String? = null)
internal data class DiscoveryMarkers(
    val listings: List<DiscoveryListing>, val clusters: List<DiscoveryCluster> = emptyList()
)
