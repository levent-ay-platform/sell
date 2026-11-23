package tn.malak.app.ui.explore

import org.maplibre.geojson.FeatureCollection
import tn.malak.app.ui.AppCue
import tn.malak.app.ui.LocalFeedback
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tn.malak.app.BuildConfig
import tn.malak.app.R
import tn.malak.app.ui.APP_BRAND_NAME
import tn.malak.app.ui.Brand
import tn.malak.app.ui.s
import kotlin.math.abs
import kotlin.math.log2

internal val DiscoveryStyleUrl = BuildConfig.MALAK_MAP_STYLE_URL
    .ifEmpty { "https://tiles.openfreemap.org/styles/liberty" }

private val discoveryGlobeStyle = BuildConfig.MALAK_MAP_STYLE_URL.ifEmpty { "asset://maps/liberty-globe.json" }

private val FilterSaver = listSaver<DiscoveryFilters, String>(
    save = { listOf(it.kind, it.minPrice, it.maxPrice, it.minArea, it.maxArea, it.minRooms?.toString())
        .map { item -> item.orEmpty() } },
    restore = { DiscoveryFilters.parse(it[0].ifEmpty { null }, it[1], it[2], it[3], it[4], it[5]) }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExploreScreen(
    language: String, preferredMapMode: Boolean = true,
    launchShowing: Boolean = false,
    onViewModeChanged: (Boolean) -> Unit = {},
    onAlerts: () -> Unit = {}, onAccount: () -> Unit = {},
    sourceOverride: DiscoverySource? = null
) {
    val endpoint = BuildConfig.MALAK_DISCOVERY_BASE_URL
    val source = sourceOverride ?: remember(endpoint, language) {
        when {
            BuildConfig.MALAK_DIRECT_PUBLISHING && BuildConfig.MALAK_PRIVATE_SERVICE_URL.isNotBlank() ->
                PilotPublicDiscoverySource(BuildConfig.MALAK_PRIVATE_SERVICE_URL)
            endpoint.isNotBlank() -> HttpDiscoverySource(endpoint, language)
            else -> null
        }
    }
    var mapMode by rememberSaveable { mutableStateOf(preferredMapMode) }
    LaunchedEffect(preferredMapMode) { mapMode = preferredMapMode }
    val feedback = LocalFeedback.current
    var openingGlobe by rememberSaveable {
        mutableStateOf(preferredMapMode)
    }
    var flyToTunisia by remember { mutableStateOf(false) }
    var globeVisible by remember { mutableStateOf(false) }
    var cameraMoving by remember { mutableStateOf(false) }
    var mapReady by remember { mutableStateOf(false) }
    LaunchedEffect(mapMode) {
        if (!mapMode) {
            openingGlobe = false
            flyToTunisia = false
            mapReady = false
        }
    }
    var mapIssue by rememberSaveable { mutableStateOf(false) }
    // A map with an unresponsive style endpoint must not strand the user on a blank surface.
    // List discovery uses the same authorized source and remains available offline/when tiles fail.
    LaunchedEffect(mapMode, mapReady) {
        if (mapMode && !mapReady) {
            delay(18_000L)
            if (mapMode && !mapReady) {
                mapIssue = true
                mapMode = false
                onViewModeChanged(false)
            }
        }
    }
    var centerLatitude by rememberSaveable { mutableDoubleStateOf(InitialCenter.latitude) }
    var centerLongitude by rememberSaveable { mutableDoubleStateOf(InitialCenter.longitude) }
    var zoom by rememberSaveable { mutableDoubleStateOf(InitialZoom) }
    var committedZoom by rememberSaveable { mutableIntStateOf(InitialZoom.toInt()) }
    var visible by rememberSaveable { mutableStateOf(InitialBounds.encode()) }
    var viewportSearchable by remember { mutableStateOf(true) }
    var committed by rememberSaveable { mutableStateOf(InitialBounds.encode()) }
    var areaMoved by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var placeId by rememberSaveable { mutableStateOf<String?>(null) }
    var filters by rememberSaveable(stateSaver = FilterSaver) { mutableStateOf(DiscoveryFilters()) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var jump by remember { mutableStateOf<CameraJump?>(null) }
    var jumpSerial by remember { mutableIntStateOf(0) }
    var recenterSearch by remember { mutableStateOf(false) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showLayers by rememberSaveable { mutableStateOf(false) }
    var radarEditing by rememberSaveable { mutableStateOf(false) }
    var radarRadius by rememberSaveable { mutableIntStateOf(5) }
    var radarSelection by rememberSaveable { mutableStateOf("") }
    var radarResultsOpen by rememberSaveable { mutableStateOf(false) }
    val activeRadar = DiscoveryRadar.decode(radarSelection)
    val radarPreview = if (radarEditing) DiscoveryRadar.decode("$centerLatitude,$centerLongitude,$radarRadius") else null
    var showSalesLayer by rememberSaveable { mutableStateOf(true) }
    var showAtlasLayer by rememberSaveable { mutableStateOf(true) }
    var showAdministrativeLayer by rememberSaveable { mutableStateOf(true) }
    var showIndustrialLayer by rememberSaveable { mutableStateOf(true) }
    var showLotLayer by rememberSaveable { mutableStateOf(true) }
    var industrialSheet by rememberSaveable { mutableStateOf<String?>(null) }
    var industrialZoneId by rememberSaveable { mutableStateOf<String?>(null) }
    var industrialLotId by rememberSaveable { mutableStateOf<String?>(null) }
    var fitIndustrialZone by remember { mutableStateOf<String?>(null) }
    var industrialLoadFailed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val industrialCatalog by produceState<IndustrialCatalog?>(null, context) {
        industrialLoadFailed = false
        value = withContext(Dispatchers.IO) {
            runCatching { loadIndustrialCatalog(context) }.getOrNull()
        }
        industrialLoadFailed = value == null
    }
    // The offline AFI catalog is searchable even when the sale API is not configured.
    val radarIndustrialHits by produceState<List<IndustrialRadarHit>?>(null, activeRadar, industrialCatalog) {
        value = null
        val radar = activeRadar
        val catalog = industrialCatalog
        if (radar != null && catalog != null) {
            value = withContext(Dispatchers.IO) {
                findIndustrialRadarHits(radar, catalog) { zone -> loadIndustrialPlan(context, zone) }
            }
        }
    }
    var allLots by remember(industrialCatalog) { mutableStateOf<FeatureCollection?>(null) }
    var lotLoadFailed by remember(industrialCatalog) { mutableStateOf(false) }
    var lotRetry by remember { mutableIntStateOf(0) }
    val needLotShapes = mapMode && showIndustrialLayer && showLotLayer && zoom >= 11
    LaunchedEffect(industrialCatalog, needLotShapes, lotRetry) {
        val catalog = industrialCatalog ?: return@LaunchedEffect
        if (needLotShapes && allLots == null) {
            lotLoadFailed = false
            try { allLots = withContext(Dispatchers.IO) { loadIndustrialShapes(context, catalog) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { lotLoadFailed = true }
        }
    }
    val industrialZone = industrialCatalog?.zones?.firstOrNull { it.id == industrialZoneId }
    var industrialPlan by remember(industrialZone) { mutableStateOf<IndustrialPlan?>(null) }
    var industrialPlanFailed by remember(industrialZone) { mutableStateOf(false) }
    LaunchedEffect(industrialZone, showIndustrialLayer) {
        if (industrialZone != null && showIndustrialLayer) {
            industrialPlan = withContext(Dispatchers.IO) {
                runCatching { loadIndustrialPlan(context, industrialZone) }.getOrNull()
            }
            industrialPlanFailed = industrialPlan == null
        }
    }
    LaunchedEffect(industrialPlan, fitIndustrialZone) {
        val plan = industrialPlan ?: return@LaunchedEffect
        if (plan.zone.id == fitIndustrialZone) {
            val (point, scale) = plan.camera(InitialCenter)
            jump = CameraJump(point, scale, ++jumpSerial)
            fitIndustrialZone = null
        }
    }
    val industrialZoneFeatures = remember(industrialCatalog, language) { industrialCatalog?.mapFeatures(language) }
    val industrialLot = industrialPlan?.lots?.firstOrNull { it.id == industrialLotId }
    val chooseIndustrialZone: (String) -> Unit = { zoneId ->
        feedback(AppCue.Action)
        selectedId = null
        radarEditing = false
        openingGlobe = false
        flyToTunisia = false
        recenterSearch = false
        industrialZoneId = zoneId
        industrialLotId = null
        fitIndustrialZone = zoneId
        industrialSheet = null
    }
    val noMarkers = remember { DiscoveryMarkers(emptyList()) }
    var selectedAtlasBoundaries by remember(source, selectedId, showAtlasLayer, mapMode) {
        mutableStateOf(emptyList<PublicParcelBoundary>())
    }
    LaunchedEffect(source, selectedId, showAtlasLayer, mapMode) {
        val listingId = selectedId
        if (source != null && listingId != null && showAtlasLayer && mapMode) {
            try {
                selectedAtlasBoundaries = source.parcel(listingId).boundaries.filter { it.kind != BoundaryKind.Estimate }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                selectedAtlasBoundaries = emptyList()
            }
        }
    }
    var refresh by remember { mutableIntStateOf(0) }
    val query = DiscoveryQuery(activeRadar?.bounds ?: DiscoveryBounds.decode(committed) ?: InitialBounds,
        placeId.takeUnless { activeRadar != null }, filters, activeRadar)
    var page by remember(query, source, refresh) { mutableStateOf(DiscoveryPage(emptyList())) }
    var listFailed by remember(query, source, refresh) { mutableStateOf(false) }
    var listLoading by remember(query, source, refresh) { mutableStateOf(source != null) }
    var loadingMore by remember(query, source, refresh) { mutableStateOf(false) }
    var pageFailed by remember(query, source, refresh) { mutableStateOf(false) }
    val markerScope = remember(query, source, refresh, committedZoom, mapMode, showSalesLayer) {
        mutableStateOf(DiscoveryMarkers(emptyList()))
    }
    var markerFailed by remember(query, source, refresh, committedZoom, mapMode, showSalesLayer) {
        mutableStateOf(false)
    }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val resultScroll = rememberLazyListState()
    LaunchedEffect(query) { resultScroll.requestScrollToItem(0) }

    LaunchedEffect(query, source, refresh) {
        if (source == null) return@LaunchedEffect
        try {
            page = source.list(query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            listFailed = true
        } finally {
            listLoading = false
        }
    }
    LaunchedEffect(query, source, refresh, committedZoom, mapMode, showSalesLayer) {
        if (source == null || !mapMode || !showSalesLayer) return@LaunchedEffect
        try {
            markerScope.value = source.markers(query, committedZoom)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            markerFailed = true
        }
    }

    val opened = detailId

    val selected = selectedId?.let { id ->
        markerScope.value.listings.firstOrNull { it.id == id }
            ?: page.listings.firstOrNull { it.id == id }
    }

    val covered = if (opened == null) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }
    Box(Modifier.fillMaxSize().background(Brand.Pearl)) {
        if (mapMode) {
            val sGlobe = s(R.string.v2_globe_view)
            val mapDescription = s(if (globeVisible) R.string.v2_globe_view else R.string.v2_ready_map)
            DiscoveryMap(
                styleUrl = discoveryGlobeStyle,
                initial = DiscoveryCamera(
                    DiscoveryPoint(centerLatitude, centerLongitude), zoom,
                    DiscoveryBounds.decode(visible) ?: InitialBounds
                ),
                jump = jump,
                markers = if (showSalesLayer) markerScope.value else noMarkers,
                boundaries = selectedAtlasBoundaries,
                administrativeBoundariesVisible = showAdministrativeLayer,
                industrialZones = industrialZoneFeatures.takeIf { showIndustrialLayer },
                industrialLots = allLots.takeIf { showIndustrialLayer && showLotLayer },
                industrialCounts = industrialCatalog?.governorates.orEmpty().takeIf { showIndustrialLayer }.orEmpty(),
                onGovernorate = { point ->
                    feedback(AppCue.Action)
                    jump = CameraJump(point, 9.3, ++jumpSerial)
                    openingGlobe = false
                    flyToTunisia = false
                },
                radarEditing = radarEditing, onRadarMove = { feedback(AppCue.Step) },
                spinGlobe = openingGlobe && !launchShowing,
                industrialLotId = industrialLotId,
                radar = if (radarEditing) radarPreview else activeRadar,
                onIndustrialZone = chooseIndustrialZone,
                onIndustrialLot = { zoneId, lotId ->
                    feedback(AppCue.Action)
                    industrialZoneId = zoneId
                    fitIndustrialZone = null
                    selectedId = null
                    industrialLotId = lotId
                },
                minimumZoom = WorldZoom,
                globeEnabled = true,
                openFromGlobe = openingGlobe,
                flyToTunisia = flyToTunisia && jump == null,
                onArrivalFinished = { flyToTunisia = false },
                onGlobeVisible = { globeVisible = it },
                onCameraMoving = { cameraMoving = it },
                onGestureStarted = {
                    openingGlobe = false
                    flyToTunisia = false
                    recenterSearch = false
                    jump = null
                },
                onGlobeTap = {
                    openingGlobe = false
                    feedback(AppCue.Action)
                },
                selectedId = selectedId,
                priceLabel = { discoveryNumber(it.priceTnd, language) },
                modifier = Modifier.fillMaxSize().then(covered).semantics {
                    if (openingGlobe) contentDescription = sGlobe else if (mapReady) contentDescription = mapDescription
                },
                onCameraIdle = { current, gesture ->
                    centerLatitude = current.center.latitude
                    centerLongitude = current.center.longitude
                    zoom = current.zoom
                    viewportSearchable = current.searchableBounds
                    if (viewportSearchable) visible = current.bounds.encode()
                    if (recenterSearch) {
                        if (viewportSearchable) {
                            committed = visible
                            committedZoom = zoom.toInt()
                        }
                        recenterSearch = false
                        areaMoved = false
                        jump = null
                    } else if (gesture) {
                        areaMoved = true
                        selectedId = null
                    }
                },
                onListing = { feedback(AppCue.Action); selectedId = it; industrialZoneId = null; industrialLotId = null },
                onCluster = { point ->
                    if (zoom < 18.0) {
                        recenterSearch = true
                        jump = CameraJump(point, maxOf(12.0, zoom + 2).coerceAtMost(18.0), ++jumpSerial)
                    } else {
                        mapMode = false
                        onViewModeChanged(false)
                    }
                },
                onFailure = { mapIssue = true; mapMode = false; mapReady = false; onViewModeChanged(false) },
                onReady = { mapReady = true }
            )
            if (!mapReady && !openingGlobe) {
                Box(Modifier.fillMaxSize().background(Brand.Pearl), contentAlignment = Alignment.Center) {
                    Surface(
                        modifier = Modifier.padding(24.dp).widthIn(max = 300.dp),
                        color = Color.White, contentColor = Brand.Ink,
                        shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Brand.Border)
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(s(R.string.v2_map_loading), style = MaterialTheme.typography.titleSmall)
                            LinearProgressIndicator(Modifier.width(92.dp), color = Brand.Crimson)
                            TextButton(onClick = {
                                mapMode = false
                                onViewModeChanged(false)
                            }) { Text(s(R.string.v2_map_skip)) }
                        }
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxSize().then(covered).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ExploreHeader(
                searchText = searchText, language = language, source = source, filters = filters,
                mapMode = mapMode,
                onAlerts = onAlerts, onAccount = onAccount,
                onSearchChange = {
                    searchText = it
                    if (it.isBlank()) { placeId = null; selectedId = null }
                },
                onPlace = { place ->
                    openingGlobe = false
                    flyToTunisia = false
                    feedback(AppCue.Action)
                    radarSelection = ""
                    radarEditing = false
                    searchText = place.label
                    placeId = place.serverPlaceId
                    val point = place.point
                    val area = DiscoveryBounds(point.longitude - .11, point.latitude - .09,
                        point.longitude + .11, point.latitude + .09)
                    val sameCamera = abs(centerLatitude - point.latitude) < .000001 &&
                        abs(centerLongitude - point.longitude) < .000001 && abs(zoom - 12.3) < .001
                    centerLatitude = point.latitude; centerLongitude = point.longitude; zoom = 12.3
                    committedZoom = 12
                    visible = area.encode(); committed = visible
                    areaMoved = false
                    if (mapMode && !sameCamera) {
                        recenterSearch = true
                        jump = CameraJump(point, zoom, ++jumpSerial)
                    }
                    selectedId = null
                },
                onKind = { kind ->
                    filters = filters.copy(kind = kind, minRooms = filters.minRooms.takeUnless { kind in setOf("LAND", "FACTORY") })
                    selectedId = null
                },
                onFilters = { showFilters = true },
                onMode = {
                    focusManager.clearFocus()
                    mapReady = false
                    jump = null
                    recenterSearch = false
                    mapMode = !mapMode
                    if (mapMode) mapIssue = false
                    onViewModeChanged(mapMode)
                }
            )

            if (mapMode) {
                if (areaMoved && mapReady && viewportSearchable && !globeVisible && source != null &&
                    showSalesLayer && activeRadar == null && !radarEditing) {
                    FilledTonalButton(
                        onClick = {
                            committed = visible
                            committedZoom = zoom.toInt()
                            placeId = null
                            searchText = ""
                            selectedId = null
                            areaMoved = false
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Brand.Pearl, contentColor = Brand.Crimson
                        )
                    ) { Text(s(R.string.v2_search_area)) }
                }
                if (markerFailed && showSalesLayer) Surface(shape = RoundedCornerShape(16.dp), color = Brand.Pearl) {
                    ResultMessage(s(R.string.v2_find_error), retry = { refresh++ })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = { feedback(AppCue.Action); showIndustrialLayer = true; radarEditing = false; industrialSheet = "zones" },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Brand.Pearl, contentColor = Brand.Ink)
                    ) {
                        Icon(Icons.Outlined.Factory, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(s(R.string.afi_shortcut), maxLines = 1)
                    }
                    if (mapReady || openingGlobe) FilledIconButton(
                        onClick = {
                            val fromWorld = openingGlobe || globeVisible || zoom < 3
                            feedback(AppCue.Radar)
                            selectedId = null
                            industrialZoneId = null
                            industrialLotId = null
                            openingGlobe = false
                            flyToTunisia = false
                            recenterSearch = false
                            radarEditing = true
                            val center = activeRadar?.center ?: DiscoveryPoint(centerLatitude, centerLongitude)
                            val target = center.takeIf { it in InitialBounds } ?: InitialCenter
                            radarRadius = activeRadar?.radiusKm ?: radarRadius
                            if (fromWorld) {
                                jump = null
                                flyToTunisia = true
                            } else {
                                jump = CameraJump(target, 10.6 - log2(radarRadius / 5.0), ++jumpSerial)
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Brand.Pearl, contentColor = Brand.Crimson
                        )
                    ) { Icon(Icons.Outlined.Radar, s(R.string.radar_title)) }
                    FilledIconButton(
                        onClick = { feedback(AppCue.Action); showLayers = true },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Brand.Pearl, contentColor = Brand.Crimson
                        )
                    ) { Icon(Icons.Outlined.Layers, s(R.string.v2_layers)) }
                }
                if (mapReady && (globeVisible || zoom < 4.4 ||
                        DiscoveryPoint(centerLatitude, centerLongitude) !in InitialBounds)) {
                    FilledIconButton(
                        onClick = {
                            focusManager.clearFocus()
                            openingGlobe = false
                            jump = null
                            recenterSearch = false
                            areaMoved = false
                            selectedId = null
                            radarSelection = ""
                            radarEditing = false
                            placeId = null
                            searchText = ""
                            committed = InitialBounds.encode()
                            committedZoom = InitialZoom.toInt()
                            visible = committed
                            centerLatitude = InitialCenter.latitude
                            centerLongitude = InitialCenter.longitude
                            zoom = InitialZoom
                            flyToTunisia = true
                        },
                        modifier = Modifier.align(Alignment.End),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Brand.Pearl, contentColor = Brand.Ink
                        )
                    ) { Icon(Icons.Outlined.CenterFocusStrong, s(R.string.v2_recenter_tunisia)) }
                }
                Spacer(Modifier.weight(1f))
                if (activeRadar != null && !radarEditing) {
                    AssistChip(onClick = { radarSelection = ""; selectedId = null },
                        label = { Text(s(R.string.radar_radius, activeRadar.radiusKm)) },
                        trailingIcon = { Icon(Icons.Outlined.Close, s(R.string.radar_clear), Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = Brand.Pearl))
                }
                if (showIndustrialLayer && industrialCatalog != null && !globeVisible && zoom >= 2.5 && zoom < AfiZoneZoom) {
                    Surface(Modifier.align(Alignment.End), shape = RoundedCornerShape(10.dp), color = Brand.Pearl) {
                        Text(s(R.string.afi_count_legend), Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            color = Brand.Ink, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (lotLoadFailed && needLotShapes) {
                    ResultMessage(s(R.string.v2_find_error), retry = { lotRetry++ })
                }
                if (showAdministrativeLayer && mapReady && !globeVisible) {
                    val uriHandler = LocalUriHandler.current
                    Surface(
                        onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") },
                        modifier = Modifier.align(Alignment.End),
                        shape = RoundedCornerShape(8.dp), color = Brand.Pearl, contentColor = Brand.Ink
                    ) {
                        Text(s(R.string.layer_atlas_credit), modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (radarEditing) {
                    RadarControls(radarRadius,
                        !flyToTunisia && !cameraMoving && radarPreview != null &&
                            (source != null || industrialCatalog != null),
                        source != null || industrialCatalog != null,
                        onRadius = { radius ->
                            if (radarRadius != radius) {
                                radarRadius = radius
                                feedback(AppCue.Step)
                            }
                        },
                        onRadiusFinished = {
                            if (!flyToTunisia && zoom < AfiZoneZoom && radarPreview != null) {
                                jump = CameraJump(radarPreview.center, 10.6 - log2(radarRadius / 5.0), ++jumpSerial)
                            }
                        },
                        onSearch = {
                            val radar = radarPreview ?: return@RadarControls
                            feedback(AppCue.Confirm)
                            radarSelection = radar.encode()
                            placeId = null
                            searchText = ""
                            selectedId = null
                            areaMoved = false
                            showSalesLayer = true
                            committedZoom = zoom.toInt()
                            radarEditing = false
                            radarResultsOpen = true
                        },
                        onClose = { radarEditing = false })
                } else if (showSalesLayer && selected != null) {
                    MapListingPreview(selected, language, { detailId = selected.id }, { selectedId = null })
                } else if (showIndustrialLayer && industrialZone != null && !globeVisible) {
                    IndustrialMapCard(industrialZone, industrialPlan, industrialLot, language,
                        onOpen = { industrialSheet = "plan" },
                        onDismiss = { industrialZoneId = null; industrialLotId = null })
                } else if (showSalesLayer && mapReady && !globeVisible &&
                    source == null && industrialCatalog == null) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Brand.Pearl) {
                        ResultMessage(s(R.string.v2_map_data_unavailable))
                    }
                } else if (showSalesLayer && source != null && !openingGlobe && !areaMoved && !globeVisible &&
                    !listLoading && !listFailed && page.listings.isEmpty() && !markerFailed) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Brand.Pearl) {
                        ResultMessage(s(R.string.v2_empty))
                    }
                } else if (showSalesLayer && mapReady && !openingGlobe && !globeVisible && markerScope.value.listings.isEmpty() &&
                    markerScope.value.clusters.isEmpty() && page.listings.isNotEmpty()
                ) {
                    OutlinedButton(onClick = { mapMode = false; onViewModeChanged(false) }) { Text(s(R.string.v2_map_skip)) }
                }
                Spacer(Modifier.height(32.dp))
            } else {
                if (mapIssue) ResultMessage(s(R.string.v2_tiles_error), retry = {
                    mapIssue = false
                    mapReady = false
                    mapMode = true
                    onViewModeChanged(true)
                })
                when {
                    source == null -> ResultMessage(s(R.string.v2_not_configured))
                    listLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    listFailed -> ResultMessage(s(R.string.v2_find_error), retry = { refresh++ })
                    page.listings.isEmpty() -> ResultMessage(s(R.string.v2_empty))
                    else -> LazyColumn(
                        Modifier.weight(1f),
                        state = resultScroll,
                        contentPadding = PaddingValues(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(page.listings, key = DiscoveryListing::id) { listing ->
                            ListingCard(listing, language, { detailId = listing.id })
                        }
                        val cursor = page.nextCursor
                        if (cursor != null) item {
                            if (pageFailed) ResultMessage(s(R.string.v2_find_error))
                            if (loadingMore) LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(enabled = !loadingMore, onClick = {
                                val expected = query
                                loadingMore = true
                                pageFailed = false
                                scope.launch {
                                    try {
                                        val next = source.list(expected, cursor)
                                        page = DiscoveryPage((page.listings + next.listings).distinctBy { it.id }, next.nextCursor)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        pageFailed = true
                                    } finally {
                                        loadingMore = false
                                    }
                                }
                            }) { Text(s(R.string.v2_more_results)) }
                        }
                    }
                }
            }
        }
        if (opened != null) {
            Surface(Modifier.fillMaxSize(), color = Brand.Pearl) {
                ListingDetail(source, opened, language) { detailId = null }
            }
        }
    }
    BackHandler(enabled = opened != null || (mapMode && (radarEditing || selectedId != null || industrialZoneId != null))) {
        when {
            detailId != null -> detailId = null
            radarEditing -> radarEditing = false
            selectedId != null -> selectedId = null
            industrialLotId != null -> industrialLotId = null
            else -> industrialZoneId = null
        }
    }
    if (radarResultsOpen && activeRadar != null) {
        RadarResultsSheet(
            radar = activeRadar,
            industrialHits = radarIndustrialHits,
            industrialFailed = industrialLoadFailed,
            listings = page.listings,
            listingsLoading = listLoading,
            listingsFailed = listFailed,
            listingsAvailable = source != null,
            language = language,
            onIndustrialZone = { zoneId ->
                radarResultsOpen = false
                chooseIndustrialZone(zoneId)
            },
            onListing = { id ->
                radarResultsOpen = false
                detailId = id
            },
            onDismiss = { radarResultsOpen = false }
        )
    }
    if (showFilters) {
        DiscoveryFilterSheet(filters, onDismiss = { showFilters = false }) {
            feedback(AppCue.Confirm)
            filters = it; showFilters = false; selectedId = null
        }
    }
    if (showLayers) {
        MapLayersSheet(
            salesVisible = showSalesLayer, atlasVisible = showAtlasLayer,
            administrativeVisible = showAdministrativeLayer, listingsAvailable = source != null,
            industrialVisible = showIndustrialLayer,
            lotsVisible = showLotLayer, onLotsVisible = { showLotLayer = it; feedback(AppCue.Action) },
            onSalesVisible = { showSalesLayer = it },
            onAtlasVisible = { showAtlasLayer = it },
            onAdministrativeVisible = { showAdministrativeLayer = it; feedback(AppCue.Action) },
            onIndustrialVisible = {
                feedback(AppCue.Action)
                showIndustrialLayer = it
                if (!it) {
                    industrialZoneId = null
                    industrialLotId = null
                    fitIndustrialZone = null
                }
            },
            onBrowseIndustrial = {
                showIndustrialLayer = true
                showLayers = false
                industrialSheet = "zones"
            },
            onDismiss = { showLayers = false }
        )
    }
    val activeIndustrialSheet = industrialSheet
    if (activeIndustrialSheet != null) {
        IndustrialMapSheet(
            industrialCatalog, industrialPlan, activeIndustrialSheet == "plan", language,
            failed = industrialLoadFailed || activeIndustrialSheet == "plan" && industrialPlanFailed,
            onZone = { chooseIndustrialZone(it.id) },
            onLot = { lot ->
                industrialLotId = lot.id
                selectedId = null
                lot.anchor?.let { point -> jump = CameraJump(point, 16.2, ++jumpSerial) }
                industrialSheet = null
            },
            onBrowse = { industrialSheet = "zones" },
            onDismiss = { industrialSheet = null }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExploreHeader(
    searchText: String, language: String, source: DiscoverySource?, filters: DiscoveryFilters,
    mapMode: Boolean,
    onAlerts: () -> Unit, onAccount: () -> Unit,
    onSearchChange: (String) -> Unit, onPlace: (DiscoveryPlace) -> Unit, onKind: (String?) -> Unit,
    onFilters: () -> Unit, onMode: () -> Unit
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val searchField = remember { FocusRequester() }
    var expanded by rememberSaveable(mapMode) { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var suggestions by remember(source, language, searchText) { mutableStateOf<List<DiscoveryPlace>?>(null) }
    var placeError by remember(source, language, searchText) { mutableStateOf(false) }
    var atlasPlaces by remember(context) { mutableStateOf<List<AtlasPlace>?>(null) }
    val activeFilters = listOf(
        filters.kind != null,
        filters.minPrice != null || filters.maxPrice != null,
        filters.minArea != null || filters.maxArea != null,
        filters.minRooms != null
    ).count { it }
    val dismissSearch = {
        keyboard?.hide()
        focus.clearFocus()
        if (mapMode) expanded = false
    }
    BackHandler(enabled = mapMode && expanded, onBack = dismissSearch)
    LaunchedEffect(mapMode, expanded) {
        if (mapMode && expanded) searchField.requestFocus()
    }
    LaunchedEffect(searchText, searching, language, source) {
        val term = searchText.trim()
        if (!searching || term.length < 2) return@LaunchedEffect
        delay(260)
        val places = atlasPlaces ?: withContext(Dispatchers.IO) {
            runCatching { readAtlasPlaces(context) }.getOrDefault(emptyList())
        }.also { atlasPlaces = it }
        val fallback = places.searchAtlasPlaces(term, language)
        suggestions = fallback
        if (source == null) return@LaunchedEffect
        try {
            suggestions = source.suggest(term, language).ifEmpty { fallback }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            placeError = fallback.isEmpty()
        }
    }

    Surface(color = Color.White, contentColor = Brand.Ink, shape = RoundedCornerShape(22.dp),
        shadowElevation = 2.dp, border = BorderStroke(1.dp, Brand.Border)) {
        if (mapMode && !expanded) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { expanded = true },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = Brand.Ink)
                ) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(searchText.ifBlank { s(R.string.v2_place_search) }, Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = Brand.Muted)
                }
                IconButton(onClick = onFilters) {
                    FilterIndicator(activeFilters)
                }
            }
            return@Surface
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(APP_BRAND_NAME, modifier = Modifier.weight(1f),
                    color = Brand.Ink, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (mapMode) {
                        IconButton(onClick = dismissSearch) {
                            Icon(Icons.Outlined.ExpandLess, s(R.string.v2_collapse_search), tint = Brand.Ink)
                        }
                    } else {
                        val label = s(if (mapMode) R.string.v2_list_mode else R.string.v2_map_mode)
                        TextButton(
                            onClick = onMode, modifier = Modifier.heightIn(min = 48.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Icon(if (mapMode) Icons.Outlined.ViewList else Icons.Outlined.Map, null,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(label, maxLines = 1)
                        }
                    }
                    IconButton(onClick = onAlerts, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.NotificationsNone, s(R.string.v2_ui_alerts), tint = Brand.Ink)
                    }
                    IconButton(onClick = onAccount, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.PersonOutline, s(R.string.v2_ui_account), tint = Brand.Ink)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchText, onValueChange = { onSearchChange(it.take(100)) },
                    modifier = Modifier.weight(1f).focusRequester(searchField).onFocusChanged { searching = it.isFocused },
                    singleLine = true, shape = RoundedCornerShape(16.dp),
                    placeholder = { Text(s(R.string.v2_place_search), maxLines = 1) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Brand.Muted) },
                    trailingIcon = if (searchText.isEmpty()) null else ({
                        IconButton(onClick = { onSearchChange("") }) { Icon(Icons.Outlined.Close, s(R.string.clear)) }
                    }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val first = suggestions?.firstOrNull()
                        if (first != null) {
                            onPlace(first)
                            dismissSearch()
                        }
                    })
                )
                FilledTonalIconButton(
                    onClick = onFilters,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Brand.NeutralContainer, contentColor = Brand.Ink
                    )
                ) { FilterIndicator(activeFilters) }
            }
            val results = suggestions
            if (searching && (results != null || placeError)) {
                when {
                    placeError -> ResultMessage(s(R.string.v2_places_error))
                    results.isNullOrEmpty() -> ResultMessage(s(R.string.v2_places_empty))
                    else -> Column(Modifier.heightIn(max = 160.dp)) {
                        LazyColumn {
                            items(results, key = DiscoveryPlace::id) { place ->
                                Text(
                                    place.label, style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        onPlace(place); dismissSearch()
                                    }.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PropertyKinds.forEach { (kind, label) ->
                        FilterChip(
                            selected = filters.kind == kind, onClick = { onKind(kind) },
                            label = { Text(s(label)) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = Brand.Muted, selectedContainerColor = Brand.NeutralContainer,
                                selectedLabelColor = Brand.Ink
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterIndicator(activeFilters: Int) {
    BadgedBox(badge = {
        if (activeFilters > 0) Badge(containerColor = Brand.Crimson) { Text(activeFilters.toString()) }
    }) {
        Icon(Icons.Outlined.Tune, s(R.string.v2_filters), tint = Brand.Ink)
    }
}
