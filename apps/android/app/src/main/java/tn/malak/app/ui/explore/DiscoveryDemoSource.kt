package tn.malak.app.ui.explore

import java.math.BigDecimal

/** Only selected for an explicitly flagged debug build without a discovery endpoint. */
internal class DiscoveryDemoSource(private val language: String) : DiscoverySource {
    private data class Place(
        val id: String,
        val ar: String,
        val fr: String,
        val location: DiscoveryPoint
    ) {
        fun label(language: String) = if (language == "ar") ar else fr
    }

    private val places = listOf(
        Place("tn-kelibia", "قليبية", "Kélibia", DiscoveryPoint(36.8507, 11.0940)),
        Place("tn-nabeul", "نابل", "Nabeul", DiscoveryPoint(36.4561, 10.7376)),
        Place("tn-menzel-temime", "منزل تميم", "Menzel Temime", DiscoveryPoint(36.7821, 10.9863))
    )

    private data class Sample(
        val listing: DiscoveryListing, val place: Place
    )

    private fun sample(
        id: Int, ar: String, fr: String, place: Place, kind: String, price: String, area: String,
        rooms: Int?, latitude: Double, longitude: Double, mapped: Boolean = true
    ) = Sample(
        DiscoveryListing(
            id = "demo-$id",
            title = if (language == "ar") ar else fr,
            region = place.label(language), kind = kind, priceTnd = price, areaM2 = area, rooms = rooms,
            point = if (mapped) DiscoveryPoint(latitude, longitude) else null,
            locationAccuracy = if (mapped) "APPROXIMATE" else "AREA_ONLY", photoUrl = null, description = null
        ),
        place
    )

    private val sampleListings = listOf(
        sample(1, "أرض سكنية قرب البحر", "Terrain résidentiel près de la mer",
            places[0], "LAND", "128000", "420", null, 36.865, 11.111),
        sample(2, "شقة بثلاث غرف", "Appartement trois pièces",
            places[0], "APARTMENT", "245000", "112", 3, 36.845, 11.076),
        sample(3, "دار مع حديقة", "Maison avec jardin",
            places[1], "HOUSE", "390000", "218", 4, 36.462, 10.764),
        sample(4, "أرض في منزل تميم", "Terrain à Menzel Temime",
            places[2], "LAND", "96000", "660", null, 36.779, 10.967),
        sample(5, "شقة في وسط نابل", "Appartement au centre de Nabeul",
            places[1], "APARTMENT", "197500", "96", 2, 36.45, 10.735, mapped = false)
    )

    override suspend fun suggest(text: String, language: String): List<DiscoveryPlace> {
        val key = foldPlaceName(text)
        return places.filter { foldPlaceName(it.ar).contains(key) || foldPlaceName(it.fr).contains(key) }
            .map { DiscoveryPlace(it.id, it.label(language), it.location) }
    }

    override suspend fun list(query: DiscoveryQuery, cursor: String?): DiscoveryPage {
        val filtered = sampleListings.filter { sample ->
            val listing = sample.listing
            val filters = query.filters
            val location = listing.point ?: sample.place.location
            location in query.bounds && (query.placeId == null || sample.place.id == query.placeId) &&
                (filters.kind == null || listing.kind == filters.kind) &&
                inRange(listing.priceTnd, filters.minPrice, filters.maxPrice) &&
                inRange(listing.areaM2, filters.minArea, filters.maxArea) &&
                (filters.minRooms == null || (listing.rooms ?: -1) >= filters.minRooms)
        }.map(Sample::listing)
        return DiscoveryPage(filtered)
    }

    override suspend fun markers(query: DiscoveryQuery, zoom: Int): DiscoveryMarkers {
        val mapped = list(query).listings.filter { it.point != null }
        val groups = mapped.groupBy(DiscoveryListing::region)
        if (zoom >= 12) return DiscoveryMarkers(mapped)
        val clusters = groups.filterValues { it.size > 1 }.map { (name, group) ->
            DiscoveryCluster("demo-$name", DiscoveryPoint(
                group.mapNotNull { it.point?.latitude }.average(),
                group.mapNotNull { it.point?.longitude }.average()
            ), group.size)
        }
        return DiscoveryMarkers(groups.values.filter { it.size == 1 }.flatten(), clusters)
    }

    override suspend fun detail(id: String): DiscoveryListing? =
        sampleListings.firstOrNull { it.listing.id == id }?.listing

    private fun inRange(value: String?, min: String?, max: String?): Boolean {
        if (min == null && max == null) return true
        val amount = value?.toBigDecimalOrNull() ?: return false
        return (min == null || amount >= BigDecimal(min)) && (max == null || amount <= BigDecimal(max))
    }
}
