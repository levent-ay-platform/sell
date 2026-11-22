package tn.malak.app.ui.explore

import android.content.Context
import org.json.JSONArray

internal data class AtlasPlace(
    val id: String,
    val arabic: String,
    val french: String,
    val point: DiscoveryPoint
)

internal fun readAtlasPlaces(context: Context): List<AtlasPlace> {
    val rows = context.assets.open("atlas-admin/places.json").bufferedReader()
        .use { JSONArray(it.readText()) }
    return List(rows.length()) { index ->
        val row = rows.getJSONArray(index)
        AtlasPlace(
            row.getString(0), row.getString(1), row.getString(2),
            DiscoveryPoint(row.getDouble(3), row.getDouble(4))
        )
    }
}

internal fun List<AtlasPlace>.searchAtlasPlaces(input: String, language: String): List<DiscoveryPlace> {
    val query = foldPlaceName(input)
    if (query.length < 2) return emptyList()
    return mapNotNull { place ->
        val names = listOf(foldPlaceName(place.arabic), foldPlaceName(place.french))
        val rank = when {
            query in names -> 0
            names.any { it.startsWith(query) } -> 1
            names.any { query in it } -> 2
            else -> return@mapNotNull null
        }
        rank to place
    }.sortedBy { it.first }.map { (_, place) ->
        val label = if (language == "ar") place.arabic else place.french
        DiscoveryPlace(place.id, label, place.point, serverPlaceId = null)
    }.distinctBy { it.label }.take(8)
}
