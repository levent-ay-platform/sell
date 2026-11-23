package tn.malak.app.ui.explore

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A nearby *planning* zone, not a seller listing, parcel boundary, or road distance. */
internal data class AtlasLensZone(
    val zone: IndustrialZone,
    val centerDistanceKm: Double
)

/**
 * Only zones with documented center coordinates are eligible for a distance estimate.
 * The count on a zone is its ENTIRE planning layout, not a count of lots inside radius.
 * Unknown centers remain in Atlas/AFI; excluding them here never deletes the source.
 */
internal fun atlasLensNearbyZones(
    point: DiscoveryPoint,
    catalog: IndustrialCatalog,
    radiusKm: Int
): List<AtlasLensZone> {
    require(radiusKm in 1..100)
    if (!point.latitude.isFinite() || !point.longitude.isFinite() ||
        point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0
    ) return emptyList()

    return catalog.zones.mapNotNull { zone ->
        val center = zone.center ?: return@mapNotNull null
        if (!center.latitude.isFinite() || !center.longitude.isFinite() ||
            center.latitude !in -90.0..90.0 || center.longitude !in -180.0..180.0
        ) return@mapNotNull null

        val firstLat = Math.toRadians(point.latitude)
        val secondLat = Math.toRadians(center.latitude)
        val dLat = (secondLat - firstLat) / 2
        val dLon = Math.toRadians(center.longitude - point.longitude) / 2
        val haversine = sin(dLat).pow(2) + cos(firstLat) * cos(secondLat) * sin(dLon).pow(2)
        val km = 6371.0088 * 2 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
        AtlasLensZone(zone, km).takeIf { km <= radiusKm }
    }.sortedWith(compareBy<AtlasLensZone> { it.centerDistanceKm }.thenBy { it.zone.id })
}

/** Display aggregate counts with explicit semantics: planned lots in matched zones. */
internal fun atlasLensPlannedLots(nearby: List<AtlasLensZone>): Int =
    nearby.sumOf { it.zone.shapeCount.coerceAtLeast(0) }
