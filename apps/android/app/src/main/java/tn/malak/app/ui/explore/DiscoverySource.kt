package tn.malak.app.ui.explore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tn.malak.app.ui.readPilotBytes
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

internal interface DiscoverySource {
    suspend fun suggest(text: String, language: String): List<DiscoveryPlace>
    suspend fun list(query: DiscoveryQuery, cursor: String? = null): DiscoveryPage
    suspend fun markers(query: DiscoveryQuery, zoom: Int): DiscoveryMarkers
    suspend fun detail(id: String): DiscoveryListing?
}

internal class HttpDiscoverySource(private val baseUrl: String, private val language: String) : DiscoverySource {
    override suspend fun suggest(text: String, language: String): List<DiscoveryPlace> =
        get("/api/v1/places/suggest", mapOf("q" to text, "lang" to language))
            ?.objects("places").orEmpty().mapNotNull { place ->
                discoveryPoint(place.decimal("latitude"), place.decimal("longitude"), "APPROXIMATE")
                    ?.let { DiscoveryPlace(place.getString("id"), place.getString("label"), it) }
            }

    override suspend fun list(query: DiscoveryQuery, cursor: String?): DiscoveryPage {
        val parameters = discoveryParameters(query.bounds, query.placeId, query.filters).toMutableMap()
        parameters["lang"] = language
        cursor?.let { parameters["cursor"] = it }
        val payload = requireNotNull(get("/api/v1/discovery/list", parameters))
        return DiscoveryPage(payload.objects("listings").map(::listing), payload.string("nextCursor"))
    }

    override suspend fun markers(query: DiscoveryQuery, zoom: Int): DiscoveryMarkers {
        val parameters = discoveryParameters(query.bounds, query.placeId, query.filters) +
            mapOf("zoom" to zoom.toString(), "lang" to language)
        val payload = requireNotNull(get("/api/v1/discovery/clusters", parameters))
        val clusters = payload.objects("clusters").mapNotNull { cluster ->
            val point = discoveryPoint(cluster.decimal("latitude"), cluster.decimal("longitude"), "APPROXIMATE")
            val count = cluster.optInt("count")
            if (point == null || count < 2) null else DiscoveryCluster(cluster.getString("id"), point, count)
        }
        return DiscoveryMarkers(payload.objects("listings").map(::listing), clusters)
    }

    override suspend fun detail(id: String): DiscoveryListing? {
        if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) return null
        return get("/api/v1/listings/$id", mapOf("lang" to language), acceptMissing = true)?.getJSONObject("listing")?.let(::listing)
    }

    private fun listing(row: JSONObject): DiscoveryListing {
        val accuracy = row.optString("locationAccuracy", "UNKNOWN")
        return DiscoveryListing(
            row.getString("id"), row.getString("title"), row.getString("region"), row.getString("kind"),
            row.getString("priceTnd"), row.string("areaM2"), row.optInt("rooms", -1).takeIf { it >= 0 },
            discoveryPoint(row.decimal("latitude"), row.decimal("longitude"), accuracy),
            accuracy, approvedPhotoUrl(row.string("photoUrl")), row.string("description")
        )
    }

    private fun approvedPhotoUrl(value: String?): String? {
        if (value == null) return null
        val target = if (value.startsWith("/api/v1/")) baseUrl + value else value
        return runCatching { requireHttps(target).toString() }.getOrNull()
    }

    private suspend fun get(
        path: String, params: Map<String, String> = emptyMap(), acceptMissing: Boolean = false
    ): JSONObject? = withContext(Dispatchers.IO) {
        val query = params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        val url = baseUrl + path + if (query.isEmpty()) "" else "?$query"
        val connection = discoveryConnection(url, "application/json")
        try {
            val status = connection.responseCode
            if (acceptMissing && status == 404) return@withContext null
            if (status != 200) throw IOException("Discovery HTTP $status")
            val bytes = connection.inputStream.use { readPilotBytes(it, 1024 * 1024) }
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }
}

internal suspend fun loadDiscoveryPhoto(url: String, targetPixels: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        val connection = discoveryConnection(url, "image/*")
        try {
            if (connection.responseCode != 200 ||
                connection.contentType?.startsWith("image/") != true
            ) return@withContext null
            val bytes = connection.inputStream.use { readPilotBytes(it, 6 * 1024 * 1024) }
            val sizes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sizes)
            val longestSide = maxOf(sizes.outWidth, sizes.outHeight)
            if (longestSide !in 1..10000) return@withContext null
            var scale = 1
            while (longestSide / (scale * 2) >= targetPixels) scale *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = scale }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            connection.disconnect()
        }
    }

private fun requireHttps(value: String): URI = URI(value).also {
    require(it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null)
}

private fun discoveryConnection(url: String, accept: String): HttpsURLConnection =
    (requireHttps(url).toURL().openConnection() as HttpsURLConnection).apply {
        connectTimeout = 7000
        readTimeout = 8000
        instanceFollowRedirects = false
        useCaches = false
        setRequestProperty("Accept", accept)
    }

private fun JSONObject.string(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun JSONObject.decimal(key: String): Double? = optDouble(key).takeIf(Double::isFinite)

private fun JSONObject.objects(key: String): List<JSONObject> {
    val array = getJSONArray(key)
    return List(array.length()) { array.getJSONObject(it) }
}
