package tn.malak.app.ui.explore

import kotlin.math.*

private const val EarthRadiusKm = 6371.0088

internal data class DiscoveryRadar(val center: DiscoveryPoint, val radiusKm: Int) {
    init { require(radiusKm in 1..20) }

    private val angularRadius get() = radiusKm / EarthRadiusKm
    val bounds: DiscoveryBounds
        get() {
            val latitudeSpan = Math.toDegrees(angularRadius)
            val longitudeSpan = Math.toDegrees(asin(sin(angularRadius) / cos(Math.toRadians(center.latitude))))
            return DiscoveryBounds(center.longitude - longitudeSpan, center.latitude - latitudeSpan,
                center.longitude + longitudeSpan, center.latitude + latitudeSpan)
        }

    fun distanceKm(point: DiscoveryPoint): Double {
        val lat1 = Math.toRadians(center.latitude)
        val lat2 = Math.toRadians(point.latitude)
        val halfLon = Math.toRadians(point.longitude - center.longitude) / 2
        val angle = 2 * asin(sqrt(
            (sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) * sin(halfLon).pow(2)).coerceIn(0.0, 1.0)
        ))
        return EarthRadiusKm * angle
    }

    fun contains(point: DiscoveryPoint): Boolean = distanceKm(point) <= radiusKm

    fun ring(): List<DiscoveryPoint> {
        val lat = Math.toRadians(center.latitude)
        val lon = Math.toRadians(center.longitude)
        return (0 until 72).map { step ->
            val bearing = -step * PI / 36
            val y = asin(sin(lat) * cos(angularRadius) + cos(lat) * sin(angularRadius) * cos(bearing))
            val x = lon + atan2(sin(bearing) * sin(angularRadius) * cos(lat),
                cos(angularRadius) - sin(lat) * sin(y))
            DiscoveryPoint(Math.toDegrees(y), Math.toDegrees(x))
        }.let { it + it.first() }
    }

    fun encode() = "${center.latitude},${center.longitude},$radiusKm"

    companion object {
        fun decode(raw: String): DiscoveryRadar? {
            val fields = raw.split(',')
            if (fields.size != 3) return null
            val lat = fields[0].toDoubleOrNull()?.takeIf { it in 29.0..39.0 } ?: return null
            val lon = fields[1].toDoubleOrNull()?.takeIf { it in 7.0..13.0 } ?: return null
            val radius = fields[2].toIntOrNull()?.takeIf { it in 1..20 } ?: return null
            return DiscoveryRadar(DiscoveryPoint(lat, lon), radius)
        }
    }
}
