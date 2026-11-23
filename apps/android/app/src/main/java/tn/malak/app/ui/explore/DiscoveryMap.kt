package tn.malak.app.ui.explore

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import tn.malak.app.ui.Brand
import java.net.URI
import kotlin.math.roundToInt
import kotlin.random.Random

internal data class DiscoveryCamera(
    val center: DiscoveryPoint, val zoom: Double, val bounds: DiscoveryBounds,
    val searchableBounds: Boolean = true
)

internal data class CameraJump(val point: DiscoveryPoint, val zoom: Double, val serial: Int)

private const val SOURCE = "malak-discovery-source"
private const val MARKERS = "malak-discovery-markers"
private const val AfiZones = "afi-zones"
private const val AfiGovernorates = "afi-governorate-counts"
internal const val AfiZoneZoom = 7.8f
private const val AfiLots = "afi-lots"
private const val AfiLotFill = "afi-lot-fill"
private const val AfiSelected = "afi-lot-selected"
internal const val WorldZoom = .52

@Composable
internal fun DiscoveryMap(
    styleUrl: String,
    initial: DiscoveryCamera,
    jump: CameraJump?,
    markers: DiscoveryMarkers,
    selectedId: String?,
    priceLabel: (DiscoveryListing) -> String,
    modifier: Modifier = Modifier,
    boundaries: List<PublicParcelBoundary> = emptyList(),
    nozhaPoint: DiscoveryPoint? = null,
    onNozha: () -> Unit = {},
    nozhaPlan: List<Feature> = emptyList(),
    nozhaMeasurements: List<Feature> = emptyList(),
    onNozhaLot: (String) -> Unit = {},
    administrativeBoundariesVisible: Boolean = false,
    industrialZones: FeatureCollection? = null,
    industrialLots: FeatureCollection? = null,
    industrialLotId: String? = null,
    industrialCounts: List<IndustrialGovernorate> = emptyList(),
    onGovernorate: (DiscoveryPoint) -> Unit = {},
    radar: DiscoveryRadar? = null,
    onIndustrialZone: (String) -> Unit = {},
    onIndustrialLot: (String, String) -> Unit = { _, _ -> },
    radarEditing: Boolean = false,
    onRadarMove: () -> Unit = {},
    spinGlobe: Boolean = false,
    minimumZoom: Double = 7.0,
    globeEnabled: Boolean = false,
    openFromGlobe: Boolean = false,
    flyToTunisia: Boolean = false,
    onArrivalFinished: () -> Unit = {},
    onGlobeVisible: (Boolean) -> Unit = {},
    onGestureStarted: () -> Unit = {},
    onGlobeTap: () -> Unit = {},
    onCameraMoving: (Boolean) -> Unit = {},
    onCameraIdle: (DiscoveryCamera, Boolean) -> Unit,
    onListing: (String) -> Unit,
    onCluster: (DiscoveryPoint) -> Unit,
    onFailure: () -> Unit,
    onReady: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val cameraCallback by rememberUpdatedState(onCameraIdle)
    val nozhaLotCallback by rememberUpdatedState(onNozhaLot)
    val nozhaCallback by rememberUpdatedState(onNozha)
    val listingCallback by rememberUpdatedState(onListing)
    val clusterCallback by rememberUpdatedState(onCluster)
    val zoneCallback by rememberUpdatedState(onIndustrialZone)
    val governorateCallback by rememberUpdatedState(onGovernorate)
    val radarMove by rememberUpdatedState(onRadarMove)
    val editingRadar by rememberUpdatedState(radarEditing)
    val lotCallback by rememberUpdatedState(onIndustrialLot)
    val afiVisible by rememberUpdatedState(industrialZones != null)
    val failureCallback by rememberUpdatedState(onFailure)
    val readyCallback by rememberUpdatedState(onReady)
    val arrivalCallback by rememberUpdatedState(onArrivalFinished)
    val globeCallback by rememberUpdatedState(onGlobeVisible)
    val movingCallback by rememberUpdatedState(onCameraMoving)
    val gestureCallback by rememberUpdatedState(onGestureStarted)
    val globeTapCallback by rememberUpdatedState(onGlobeTap)
    val radarVisible by rememberUpdatedState(radar != null)
    val mapView = remember(context) {
        MapLibre.getInstance(context)
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)
            .translucentTextureSurface(globeEnabled))
    }
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var style by remember(mapView) { mutableStateOf<Style?>(null) }
    var worldCamera by remember(mapView) { mutableStateOf(if (openFromGlobe) initial.center to WorldZoom else null) }
    var spinLongitude by remember(mapView) { mutableStateOf<Double?>(null) }
    var radarCamera by remember(mapView) { mutableStateOf<CameraPosition?>(null) }

    DisposableEffect(mapView, lifecycle) {
        var active = true
        var gesture = false
        var radarGestureOrigin: LatLng? = null
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
            nativeMap.setMinZoomPreference(minimumZoom)
            nativeMap.setMaxZoomPreference(18.0)
            nativeMap.uiSettings.isRotateGesturesEnabled = false
            nativeMap.uiSettings.isTiltGesturesEnabled = false
            nativeMap.uiSettings.isLogoEnabled = false
            nativeMap.cameraPosition = CameraPosition.Builder()
                .target(initial.center.latLng()).zoom(if (openFromGlobe) WorldZoom else initial.zoom).build()
            nativeMap.addOnCameraMoveStartedListener { reason ->
                movingCallback(true)
                gesture = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                radarGestureOrigin = if (gesture && editingRadar) nativeMap.cameraPosition.target else null
                if (gesture) { spinLongitude = null; gestureCallback() }
            }
            nativeMap.addOnCameraMoveListener {
                val camera = nativeMap.cameraPosition
                if (radarVisible || editingRadar) radarCamera = camera
                val origin = radarGestureOrigin
                val target = camera.target
                if (gesture && editingRadar && origin != null && target != null) {
                    val pixel = nativeMap.projection.toScreenLocation(origin)
                    val current = nativeMap.projection.toScreenLocation(target)
                    if (kotlin.math.hypot(pixel.x - current.x, pixel.y - current.y) >= 40 * context.resources.displayMetrics.density) {
                        radarGestureOrigin = target
                        radarMove()
                    }
                }
                if (globeEnabled) {
                    val point = camera.target ?: return@addOnCameraMoveListener
                    globeCallback(camera.zoom <= 1.5)
                    worldCamera = if (camera.zoom < WorldGlobeBlendEnd) {
                        DiscoveryPoint(point.latitude, point.longitude) to camera.zoom
                    } else null
                }
            }
            nativeMap.addOnCameraIdleListener {
                movingCallback(false)
                val position = nativeMap.cameraPosition
                if (radarVisible) radarCamera = position
                val center = position.target ?: return@addOnCameraIdleListener
                val region = nativeMap.projection.visibleRegion.latLngBounds
                val bounds = DiscoveryBounds.decode(
                    "${region.longitudeWest},${region.latitudeSouth},${region.longitudeEast},${region.latitudeNorth}"
                )
                cameraCallback(
                    DiscoveryCamera(DiscoveryPoint(center.latitude, center.longitude), position.zoom,
                        bounds ?: initial.bounds, searchableBounds = bounds != null),
                    gesture
                )
                gesture = false
            }
            nativeMap.addOnMapClickListener { location ->
                val tap = nativeMap.projection.toScreenLocation(location)
                val hit = RectF(tap.x - 20, tap.y - 20, tap.x + 20, tap.y + 20)
                val feature = nativeMap.queryRenderedFeatures(hit, MARKERS).firstOrNull()
                if (feature?.hasProperty("nozha") == true) {
                    nozhaCallback()
                    return@addOnMapClickListener true
                }
                val nozhaLot = nativeMap.queryRenderedFeatures(tap, "nozha-plan-fill").firstOrNull()
                if (nozhaLot != null) {
                    nozhaLotCallback(nozhaLot.getStringProperty("code"))
                    return@addOnMapClickListener true
                }
                if (feature == null) {
                    if (!afiVisible) return@addOnMapClickListener false
                    val lot = nativeMap.queryRenderedFeatures(tap, AfiLotFill).firstOrNull()
                    if (lot != null) {
                        lotCallback(lot.getStringProperty("zoneId"), lot.getStringProperty("id"))
                        return@addOnMapClickListener true
                    }
                    val overview = nativeMap.queryRenderedFeatures(hit, AfiGovernorates).firstOrNull()
                    if (overview != null) {
                        governorateCallback(DiscoveryPoint(overview.getNumberProperty("focusLat").toDouble(),
                            overview.getNumberProperty("focusLon").toDouble()))
                        return@addOnMapClickListener true
                    }
                    val zone = nativeMap.queryRenderedFeatures(hit, AfiZones).firstOrNull()
                        ?: return@addOnMapClickListener false
                    zoneCallback(zone.getStringProperty("id"))
                    return@addOnMapClickListener true
                }
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
                if (globeEnabled) {
                    val camera = nativeMap.cameraPosition
                    globeCallback(camera.zoom <= 1.5)
                    camera.target?.let { point ->
                        if (camera.zoom < WorldGlobeBlendEnd) {
                            worldCamera = DiscoveryPoint(point.latitude, point.longitude) to camera.zoom
                        }
                    }
                }
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

    fun alignGlobeForFlight(nativeMap: MapLibreMap) {
        val longitude = spinLongitude ?: return
        spinLongitude = null
        val position = nativeMap.cameraPosition
        val center = position.target ?: return
        if (position.zoom <= 1.5) {
            nativeMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(center.latitude, longitude), position.zoom))
        }
    }

    LaunchedEffect(map, jump) {
        val target = jump ?: return@LaunchedEffect
        val native = map ?: return@LaunchedEffect
        alignGlobeForFlight(native)
        val update = CameraUpdateFactory.newLatLngZoom(target.point.latLng(), target.zoom)
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) native.animateCamera(update, 450)
        else native.moveCamera(update)
    }

    LaunchedEffect(map, style, flyToTunisia) {
        val nativeMap = map ?: return@LaunchedEffect
        if (style == null || !flyToTunisia) return@LaunchedEffect
        alignGlobeForFlight(nativeMap)
        val density = context.resources.displayMetrics.density
        val bounds = LatLngBounds.from(InitialBounds.north, InitialBounds.east, InitialBounds.south, InitialBounds.west)
        val sides = (24 * density).roundToInt()
        val target = CameraUpdateFactory.newLatLngBounds(
            bounds, sides, (74 * density).roundToInt(), sides, ((if (editingRadar) 150 else 36) * density).roundToInt()
        )
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            nativeMap.moveCamera(target)
            arrivalCallback()
            return@LaunchedEffect
        }
        nativeMap.animateCamera(target, 1450, object : MapLibreMap.CancelableCallback {
            override fun onFinish() = arrivalCallback()
            override fun onCancel() = arrivalCallback()
        })
    }

    LaunchedEffect(style, administrativeBoundariesVisible) {
        style?.let { updateAdministrativeLayers(it, administrativeBoundariesVisible) }
    }

    LaunchedEffect(style, industrialZones, industrialLots) {
        style?.let { updateIndustrialLayers(it, industrialZones, industrialLots) }
    }

    LaunchedEffect(style, industrialZones, industrialLotId) {
        style?.getLayerAs<LineLayer>(AfiSelected)?.setFilter(
            Expression.eq(Expression.get("id"), Expression.literal(industrialLotId.orEmpty()))
        )
    }

    LaunchedEffect(style, industrialCounts) {
        style?.let { updateGovernorateCounts(it, industrialCounts) }
    }

    LaunchedEffect(style, boundaries) {
        style?.let { updateBoundaryLayers(it, boundaries) }
    }

    LaunchedEffect(style, nozhaPlan) {
        val mapStyle = style ?: return@LaunchedEffect
        val collection = FeatureCollection.fromFeatures(nozhaPlan)
        val existing = mapStyle.getSourceAs<GeoJsonSource>("nozha-plan")
        if (existing != null) {
            existing.setGeoJson(collection)
        } else if (nozhaPlan.isNotEmpty()) {
            mapStyle.addSource(GeoJsonSource("nozha-plan", collection))
            val color = Expression.match(Expression.get("status"), Expression.color(0xFF64748B.toInt()),
                Expression.stop("AVAILABLE", Expression.color(0xFF17764C.toInt())),
                Expression.stop("RESERVED", Expression.color(0xFFAB710E.toInt())),
                Expression.stop("SOLD", Expression.color(0xFFA81835.toInt())))
            mapStyle.addLayerBelow(FillLayer("nozha-plan-fill", "nozha-plan").withProperties(
                PropertyFactory.fillColor(color), PropertyFactory.fillOpacity(.47f)
            ).apply { minZoom = 13f }, MARKERS)
            mapStyle.addLayerBelow(LineLayer("nozha-plan-outline", "nozha-plan").withProperties(
                PropertyFactory.lineColor(color), PropertyFactory.lineWidth(2f),
                PropertyFactory.lineDasharray(arrayOf(3f, 2f))
            ).apply { minZoom = 13f }, MARKERS)
            mapStyle.addLayerBelow(SymbolLayer("nozha-plan-label", "nozha-plan").withProperties(
                PropertyFactory.textField(Expression.get("code")), PropertyFactory.textSize(12f),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold"))
            ).apply { minZoom = 15.5f }, MARKERS)
        }
    }

    LaunchedEffect(style, nozhaMeasurements) {
        val mapStyle = style ?: return@LaunchedEffect
        val collection = FeatureCollection.fromFeatures(nozhaMeasurements)
        val source = mapStyle.getSourceAs<GeoJsonSource>("nozha-measurements")
        if (source != null) {
            source.setGeoJson(collection)
        } else if (nozhaMeasurements.isNotEmpty()) {
            mapStyle.addSource(GeoJsonSource("nozha-measurements", collection))
            mapStyle.addLayerAbove(SymbolLayer("nozha-measurement-label", "nozha-measurements").withProperties(
                PropertyFactory.textField(Expression.get("label")), PropertyFactory.textSize(14f),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")), PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textColor(0xFF142E42.toInt()), PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
                PropertyFactory.textHaloWidth(2f)
            ), MARKERS)
        }
    }

    LaunchedEffect(style, markers, selectedId, nozhaPoint) {
        val features = buildList {
            if (nozhaPoint != null) add(Feature.fromGeometry(Point.fromLngLat(nozhaPoint.longitude, nozhaPoint.latitude)).apply {
                addBooleanProperty("nozha", true)
                addStringProperty("label", "Nozha")
                addNumberProperty("drawOrder", 1)
            })
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

    val radarRing = remember(radar, radarEditing, radarCamera) {
        val target = radarCamera?.target
        val preview = if (radarEditing && target != null) radar?.copy(center = DiscoveryPoint(target.latitude, target.longitude)) else radar
        preview?.ring().orEmpty()
    }
    val radarPath = remember(map, radarRing, radarCamera) {
        val projection = map?.projection
        if (projection == null || radarRing.isEmpty()) null else Path().apply {
            radarRing.forEachIndexed { index, point ->
                val pixel = projection.toScreenLocation(point.latLng())
                if (index == 0) moveTo(pixel.x, pixel.y) else lineTo(pixel.x, pixel.y)
            }
            close()
        }
    }
    Box(modifier.background(Brand.Pearl)) {
        if (globeEnabled && (radar == null || worldCamera != null)) GlobeSky(Modifier.matchParentSize())
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        worldCamera?.let { (center, zoom) ->
            WorldGlobe(center, zoom, Modifier.matchParentSize(), spinGlobe,
                onStopSpin = { globeTapCallback() }, onSpinLongitude = { spinLongitude = it })
        }
        if (radarPath != null && worldCamera == null) {
            val projectedCenter = radarCamera?.target?.let { map?.projection?.toScreenLocation(it) }
            Canvas(Modifier.matchParentSize().clipToBounds()) {
                if (radarEditing && projectedCenter != null) {
                    val origin = Offset(projectedCenter.x, projectedCenter.y)
                    drawCircle(Color.White, 6.dp.toPx(), origin)
                    drawCircle(Brand.Crimson, 3.5.dp.toPx(), origin)
                }
                drawPath(radarPath, Brand.Crimson.copy(alpha = .10f))
                drawPath(radarPath, Brand.Crimson, style = Stroke(
                    width = 2.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 5.dp.toPx()))
                ))
            }
        }
    }
}

@Composable
internal fun GlobeSky(modifier: Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier) {
        val stars = remember {
            val random = Random(721)
            List(148) { Triple(random.nextFloat(), random.nextFloat(), random.nextFloat()) }
        }
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF080E20), Color(0xFF172C46), Color(0xFF080F20))))
            for ((x, y, light) in stars) {
                val center = Offset(x * size.width, y * size.height)
                if (light > .96f) drawCircle(Color(0x306C9BD5), 2.8.dp.toPx(), center)
                drawCircle(Color(0xFFDCEAFF), radius = (.38f + light * 1.02f).dp.toPx(),
                    center = center, alpha = .26f + light * .64f)
            }
        }
        content()
    }
}

private fun DiscoveryPoint.latLng() = LatLng(latitude, longitude)

private fun updateGovernorateCounts(style: Style, rows: List<IndustrialGovernorate>) {
    val features = rows.map { (place, count, focus) ->
        val icon = "afi-count-$count"
        if (style.getImage(icon) == null) {
            val size = 58
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
            canvas.drawCircle(29f, 29f, 28f, paint)
            paint.color = if (count == 0) 0xFF55616A.toInt() else 0xFF145D68.toInt()
            canvas.drawCircle(29f, 29f, 25f, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = 24f
            canvas.drawText(count.toString(), 29f, 29f - (paint.descent() + paint.ascent()) / 2, paint)
            style.addImage(icon, bitmap)
        }
        Feature.fromGeometry(Point.fromLngLat(place.point.longitude, place.point.latitude)).apply {
            addStringProperty("badge", icon)
            addNumberProperty("lots", count)
            addNumberProperty("focusLat", focus.latitude)
            addNumberProperty("focusLon", focus.longitude)
        }
    }
    val collection = FeatureCollection.fromFeatures(features)
    val source = style.getSourceAs<GeoJsonSource>(AfiGovernorates)
    if (source != null) {
        source.setGeoJson(collection)
    } else if (rows.isNotEmpty()) {
        style.addSource(GeoJsonSource(AfiGovernorates, collection))
        val layer = SymbolLayer(AfiGovernorates, AfiGovernorates).withProperties(
            PropertyFactory.iconImage(Expression.get("badge")), PropertyFactory.iconSize(.80f),
            PropertyFactory.iconPadding(1f), PropertyFactory.symbolSortKey(Expression.subtract(Expression.literal(0), Expression.get("lots")))
        )
        layer.minZoom = 2.5f
        layer.maxZoom = AfiZoneZoom
        style.addLayerBelow(layer, MARKERS)
    }
}

private fun updateIndustrialLayers(style: Style, zones: FeatureCollection?, lots: FeatureCollection?) {
    val empty = FeatureCollection.fromFeatures(emptyList())
    val zoneSource = style.getSourceAs<GeoJsonSource>(AfiZones)
    if (zoneSource != null) {
        zoneSource.setGeoJson(zones ?: empty)
        style.getSourceAs<GeoJsonSource>(AfiLots)?.setGeoJson(lots ?: empty)
        return
    }
    if (zones == null) return
    val teal = 0xFF14666F.toInt()
    style.addSource(GeoJsonSource(AfiZones, zones))
    style.addSource(GeoJsonSource(AfiLots, lots ?: empty))
    style.addLayerBelow(FillLayer(AfiLotFill, AfiLots).withProperties(
        PropertyFactory.fillColor(teal), PropertyFactory.fillOpacity(.26f)
    ).apply { minZoom = 11f }, MARKERS)
    style.addLayerBelow(LineLayer("afi-lot-outline", AfiLots).withProperties(
        PropertyFactory.lineColor(teal), PropertyFactory.lineWidth(1.6f)
    ).apply { minZoom = 11f }, MARKERS)
    val selected = LineLayer(AfiSelected, AfiLots).withProperties(
        PropertyFactory.lineColor(0xFFAD6111.toInt()), PropertyFactory.lineWidth(3.6f)
    )
    selected.setFilter(Expression.eq(Expression.get("id"), Expression.literal("")))
    style.addLayerBelow(selected, MARKERS)
    val lotNumbers = SymbolLayer("afi-lot-numbers", AfiLots).withProperties(
        PropertyFactory.textField(Expression.get("number")), PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
        PropertyFactory.textColor(teal), PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
        PropertyFactory.textHaloWidth(1.3f), PropertyFactory.textSize(12f)
    )
    lotNumbers.minZoom = 15f
    style.addLayerBelow(lotNumbers, MARKERS)
    val markers = CircleLayer(AfiZones, AfiZones).withProperties(
        PropertyFactory.circleColor(teal), PropertyFactory.circleRadius(6f),
        PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE), PropertyFactory.circleStrokeWidth(1.6f)
    )
    markers.minZoom = AfiZoneZoom
    markers.maxZoom = 14f
    style.addLayerBelow(markers, MARKERS)
}

private fun updateAdministrativeLayers(style: Style, visible: Boolean) {
    val layers = listOf("governorates", "delegations")
    for (partition in layers) {
        val source = "atlas-reference-$partition"
        val id = "$source-line"
        val visibility = PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE)
        val existing = style.getLayerAs<LineLayer>(id)
        if (existing != null) {
            existing.setProperties(visibility)
        } else if (visible) {
            style.addSource(GeoJsonSource(source, URI.create("asset://atlas-admin/$partition.geojson")))
            val governorates = partition == "governorates"
            val line = LineLayer(id, source).withProperties(
                PropertyFactory.lineColor(if (governorates) 0xFF225866.toInt() else 0xFF497783.toInt()),
                PropertyFactory.lineWidth(if (governorates) 2.2f else 1.35f),
                PropertyFactory.lineOpacity(.9f)
            )
            if (!governorates) {
                line.minZoom = 8f
                line.setProperties(PropertyFactory.lineDasharray(arrayOf(3f, 2f)))
            }
            style.addLayerBelow(line, MARKERS)
        }
    }
}

private fun markerBackground(): Bitmap {
    val bitmap = Bitmap.createBitmap(84, 36, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB30D2D.toInt() }
    canvas.drawRoundRect(RectF(1f, 1f, 83f, 35f), 17f, 17f, paint)
    return bitmap
}

private fun updateBoundaryLayers(style: Style, boundaries: List<PublicParcelBoundary>) {
    for (kind in BoundaryKind.entries) {
        val items = boundaries.filter { it.kind == kind }
        val id = "malak-boundary-${kind.name.lowercase()}"
        val vertexId = "$id-vertices"
        val existing = style.getSourceAs<GeoJsonSource>(id)
        if (existing == null && items.isEmpty()) continue
        val polygons = FeatureCollection.fromFeatures(items.map {
            Feature.fromJson("""{"type":"Feature","geometry":${it.geometry},"properties":{}}""")
        })
        val vertices = FeatureCollection.fromFeatures(items.flatMap { boundary ->
            boundary.labelRing.takeIf { ring -> ring.size <= 40 }.orEmpty().distinct().map {
                Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))
            }
        })
        if (existing != null) {
            existing.setGeoJson(polygons)
            style.getSourceAs<GeoJsonSource>(vertexId)?.setGeoJson(vertices)
            continue
        }
        val color = when (kind) {
            BoundaryKind.Official -> 0xFFB30D2D.toInt()
            BoundaryKind.Georeferenced -> 0xFF8B4C05.toInt()
            BoundaryKind.Estimate -> 0xFF48566B.toInt()
        }
        style.addSource(GeoJsonSource(id, polygons))
        style.addSource(GeoJsonSource(vertexId, vertices))
        style.addLayerBelow(FillLayer("$id-fill", id).withProperties(
            PropertyFactory.fillColor(color), PropertyFactory.fillOpacity(.19f)
        ), MARKERS)
        val line = LineLayer("$id-line", id).withProperties(
            PropertyFactory.lineColor(color), PropertyFactory.lineWidth(3f)
        )
        if (kind != BoundaryKind.Official) line.setProperties(PropertyFactory.lineDasharray(arrayOf(2.5f, 1.5f)))
        style.addLayerBelow(line, MARKERS)
        style.addLayerBelow(CircleLayer("$vertexId-layer", vertexId).withProperties(
            PropertyFactory.circleRadius(3f), PropertyFactory.circleColor(color),
            PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
            PropertyFactory.circleStrokeWidth(1f)
        ), MARKERS)
    }
}
