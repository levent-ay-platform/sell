package tn.malak.app.ui.explore

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

internal data class DiscoveryCamera(
    val center: DiscoveryPoint, val zoom: Double, val bounds: DiscoveryBounds
)

internal data class CameraJump(val point: DiscoveryPoint, val zoom: Double, val serial: Int)

private const val SOURCE = "malak-discovery-source"
private const val MARKERS = "malak-discovery-markers"

@Composable
internal fun DiscoveryMap(
    styleUrl: String,
    initial: DiscoveryCamera,
    jump: CameraJump?,
    markers: DiscoveryMarkers,
    selectedId: String?,
    priceLabel: (DiscoveryListing) -> String,
    modifier: Modifier = Modifier,
    onCameraIdle: (DiscoveryCamera, Boolean) -> Unit,
    onListing: (String) -> Unit,
    onCluster: (DiscoveryPoint) -> Unit,
    onFailure: () -> Unit,
    onReady: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val cameraCallback by rememberUpdatedState(onCameraIdle)
    val listingCallback by rememberUpdatedState(onListing)
    val clusterCallback by rememberUpdatedState(onCluster)
    val failureCallback by rememberUpdatedState(onFailure)
    val readyCallback by rememberUpdatedState(onReady)
    val mapView = remember(context) {
        MapLibre.getInstance(context)
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true))
    }
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var style by remember(mapView) { mutableStateOf<Style?>(null) }
    var gesture = false

    DisposableEffect(mapView, lifecycle) {
        var active = true
        mapView.onCreate(null)
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(lifecycleObserver)
        val failed = MapView.OnDidFailLoadingMapListener { if (active) failureCallback() }
        mapView.addOnDidFailLoadingMapListener(failed)
        mapView.getMapAsync { nativeMap ->
            if (!active) return@getMapAsync
            nativeMap.setMinZoomPreference(7.0)
            nativeMap.setMaxZoomPreference(18.0)
            nativeMap.uiSettings.isRotateGesturesEnabled = false
            nativeMap.uiSettings.isTiltGesturesEnabled = false
            nativeMap.cameraPosition = CameraPosition.Builder()
                .target(initial.center.latLng()).zoom(initial.zoom).build()
            nativeMap.addOnCameraMoveStartedListener { reason ->
                gesture = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
            }
            nativeMap.addOnCameraIdleListener {
                val position = nativeMap.cameraPosition
                val center = position.target ?: return@addOnCameraIdleListener
                val region = nativeMap.projection.visibleRegion.latLngBounds
                val bounds = DiscoveryBounds.decode(
                    "${region.longitudeWest},${region.latitudeSouth},${region.longitudeEast},${region.latitudeNorth}"
                ) ?: return@addOnCameraIdleListener
                cameraCallback(
                    DiscoveryCamera(DiscoveryPoint(center.latitude, center.longitude), position.zoom, bounds),
                    gesture
                )
                gesture = false
            }
            nativeMap.addOnMapClickListener { location ->
                val tap = nativeMap.projection.toScreenLocation(location)
                val hit = RectF(tap.x - 20, tap.y - 20, tap.x + 20, tap.y + 20)
                val feature = nativeMap.queryRenderedFeatures(hit, MARKERS).firstOrNull()
                    ?: return@addOnMapClickListener false
                val id = if (feature.hasProperty("listingId")) feature.getStringProperty("listingId") else null
                val point = feature.geometry() as? Point
                when {
                    !id.isNullOrEmpty() -> listingCallback(id)
                    point != null -> clusterCallback(DiscoveryPoint(point.latitude(), point.longitude()))
                }
                true
            }
            map = nativeMap
            nativeMap.setStyle(Style.Builder().fromUri(styleUrl)) { loaded ->
                if (!active) return@setStyle
                loaded.addImage("malak-pin-background", markerBackground())
                loaded.addSource(GeoJsonSource(SOURCE, FeatureCollection.fromFeatures(emptyList())))
                loaded.addLayer(SymbolLayer(MARKERS, SOURCE).withProperties(
                    PropertyFactory.iconImage("malak-pin-background"),
                    PropertyFactory.iconTextFit(Property.ICON_TEXT_FIT_BOTH),
                    PropertyFactory.iconTextFitPadding(arrayOf(4f, 8f, 4f, 8f)),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(13f),
                    PropertyFactory.textColor(android.graphics.Color.WHITE),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.symbolSortKey(Expression.get("drawOrder"))
                ))
                style = loaded
                readyCallback()
            }
        }
        onDispose {
            active = false
            mapView.removeOnDidFailLoadingMapListener(failed)
            lifecycle.removeObserver(lifecycleObserver)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(map, jump) {
        val target = jump ?: return@LaunchedEffect
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(target.point.latLng(), target.zoom), 280)
    }

    LaunchedEffect(style, markers, selectedId) {
        val features = buildList {
            for (listing in markers.listings) {
                val point = listing.point ?: continue
                add(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply {
                    addStringProperty("listingId", listing.id)
                    addStringProperty("label", priceLabel(listing))
                    addNumberProperty("drawOrder", if (listing.id == selectedId) 2 else 1)
                })
            }
            for (cluster in markers.clusters) {
                val point = cluster.point
                add(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply {
                    addStringProperty("label", cluster.count.toString())
                    addNumberProperty("drawOrder", 0)
                })
            }
        }
        style?.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun DiscoveryPoint.latLng() = LatLng(latitude, longitude)

private fun markerBackground(): Bitmap {
    val bitmap = Bitmap.createBitmap(84, 36, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB30D2D.toInt() }
    canvas.drawRoundRect(RectF(1f, 1f, 83f, 35f), 17f, 17f, paint)
    return bitmap
}
