package tn.malak.app.ui.explore

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Layers
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tn.malak.app.BuildConfig
import tn.malak.app.R
import tn.malak.app.ui.APP_BRAND_ARABIC
import tn.malak.app.ui.Brand
import tn.malak.app.ui.s
import kotlin.math.abs

private const val DemoStyle = "https://tiles.openfreemap.org/styles/positron"

private val FilterSaver = listSaver<DiscoveryFilters, String>(
    save = { listOf(it.kind, it.minPrice, it.maxPrice, it.minArea, it.maxArea, it.minRooms?.toString())
        .map { item -> item.orEmpty() } },
    restore = { DiscoveryFilters.parse(it[0].ifEmpty { null }, it[1], it[2], it[3], it[4], it[5]) }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExploreScreen(language: String, onSeller: () -> Unit) {
    val endpoint = BuildConfig.MALAK_DISCOVERY_BASE_URL
    val demo = BuildConfig.DEBUG && endpoint.isEmpty()
    val source = remember(endpoint, demo, language) {
        when {
            demo -> DiscoveryDemoSource(language)
            endpoint.isNotBlank() -> HttpDiscoverySource(endpoint, language)
            else -> null
        }
    }
    val styleUrl = BuildConfig.MALAK_MAP_STYLE_URL.ifEmpty { if (demo) DemoStyle else "" }
    var mapMode by rememberSaveable { mutableStateOf(styleUrl.isNotEmpty()) }
    var mapIssue by rememberSaveable { mutableStateOf(false) }
    var mapReady by remember { mutableStateOf(false) }
    var centerLatitude by rememberSaveable { mutableDoubleStateOf(InitialCenter.latitude) }
    var centerLongitude by rememberSaveable { mutableDoubleStateOf(InitialCenter.longitude) }
    var zoom by rememberSaveable { mutableDoubleStateOf(InitialZoom) }
    var committedZoom by rememberSaveable { mutableIntStateOf(InitialZoom.toInt()) }
    var visible by rememberSaveable { mutableStateOf(InitialBounds.encode()) }
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
    var refresh by remember { mutableIntStateOf(0) }
    val query = DiscoveryQuery(DiscoveryBounds.decode(committed) ?: InitialBounds, placeId, filters)
    var page by remember(query, source, refresh) { mutableStateOf(DiscoveryPage(emptyList())) }
    var listFailed by remember(query, source, refresh) { mutableStateOf(false) }
    var listLoading by remember(query, source, refresh) { mutableStateOf(source != null) }
    var loadingMore by remember(query, source, refresh) { mutableStateOf(false) }
    var pageFailed by remember(query, source, refresh) { mutableStateOf(false) }
    val markerScope = remember(query, source, refresh, committedZoom, mapMode) {
        mutableStateOf(DiscoveryMarkers(emptyList()))
    }
    var markerFailed by remember(query, source, refresh, committedZoom, mapMode) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

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
    LaunchedEffect(query, source, refresh, committedZoom, mapMode) {
        if (source == null || !mapMode) return@LaunchedEffect
        try {
            markerScope.value = source.markers(query, committedZoom)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            markerFailed = true
        }
    }

    val opened = detailId
    BackHandler(enabled = opened != null || selectedId != null) {
        if (detailId != null) detailId = null else selectedId = null
    }
    if (opened != null) {
        ListingDetail(source, opened, language) { detailId = null }
        return
    }

    val selected = selectedId?.let { id ->
        markerScope.value.listings.firstOrNull { it.id == id }
            ?: page.listings.firstOrNull { it.id == id }
    }

    Box(Modifier.fillMaxSize().background(Brand.Pearl)) {
        if (mapMode && styleUrl.isNotBlank()) {
            val mapDescription = s(R.string.v2_ready_map)
            DiscoveryMap(
                styleUrl = styleUrl,
                initial = DiscoveryCamera(
                    DiscoveryPoint(centerLatitude, centerLongitude), zoom,
                    DiscoveryBounds.decode(visible) ?: InitialBounds
                ),
                jump = jump,
                markers = markerScope.value,
                selectedId = selectedId,
                priceLabel = { discoveryNumber(it.priceTnd, language) },
                modifier = Modifier.fillMaxSize().semantics {
                    if (mapReady) contentDescription = mapDescription
                },
                onCameraIdle = { current, gesture ->
                    centerLatitude = current.center.latitude
                    centerLongitude = current.center.longitude
                    zoom = current.zoom
                    visible = current.bounds.encode()
                    if (recenterSearch) {
                        committed = visible
                        committedZoom = zoom.toInt()
                        recenterSearch = false
                        areaMoved = false
                        jump = null
                    } else if (gesture) {
                        areaMoved = true
                        selectedId = null
                    }
                },
                onListing = { selectedId = it },
                onCluster = { point ->
                    if (zoom < 18.0) {
                        recenterSearch = true
                        jump = CameraJump(point, maxOf(12.0, zoom + 2).coerceAtMost(18.0), ++jumpSerial)
                    } else {
                        mapMode = false
                    }
                },
                onFailure = { mapIssue = true; mapMode = false; mapReady = false },
                onReady = { mapReady = true }
            )
            if (!mapReady) {
                OutlinedButton(
                    onClick = { mapMode = false },
                    modifier = Modifier.align(Alignment.Center)
                ) { Text(s(R.string.v2_map_skip)) }
            }
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ExploreHeader(
                searchText = searchText, language = language, source = source, filters = filters,
                mapMode = mapMode, styleConfigured = styleUrl.isNotEmpty(), demo = demo, onSeller = onSeller,
                onSearchChange = {
                    searchText = it
                    if (it.isBlank()) { placeId = null; selectedId = null }
                },
                onPlace = { place ->
                    searchText = place.label
                    placeId = place.id
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
                    filters = filters.copy(kind = kind, minRooms = filters.minRooms.takeUnless { kind == "LAND" })
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
                }
            )

            if (mapMode && styleUrl.isNotBlank()) {
                if (areaMoved && mapReady) {
                    FilledTonalButton(
                        onClick = {
                            committed = visible
                            committedZoom = zoom.toInt()
                            selectedId = null
                            areaMoved = false
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Brand.Pearl, contentColor = Brand.Crimson
                        )
                    ) { Text(s(R.string.v2_search_area)) }
                }
                if (markerFailed) Surface(shape = RoundedCornerShape(16.dp), color = Brand.Pearl) {
                    ResultMessage(s(R.string.v2_find_error), retry = { refresh++ })
                }
                if (mapReady) {
                    FilledIconButton(
                        onClick = { showLayers = true }, modifier = Modifier.align(Alignment.End),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Brand.Pearl, contentColor = Brand.Crimson
                        )
                    ) { Icon(Icons.Outlined.Layers, s(R.string.v2_layers)) }
                }
                Spacer(Modifier.weight(1f))
                if (selected != null) {
                    ListingCard(selected, language, { detailId = selected.id })
                } else if (!listLoading && !listFailed && page.listings.isEmpty() && !markerFailed) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Brand.Pearl) {
                        ResultMessage(s(R.string.v2_empty))
                    }
                } else if (mapReady && markerScope.value.listings.isEmpty() &&
                    markerScope.value.clusters.isEmpty() && page.listings.isNotEmpty()
                ) {
                    OutlinedButton(onClick = { mapMode = false }) { Text(s(R.string.v2_map_skip)) }
                }
                Spacer(Modifier.height(32.dp))
            } else {
                if (mapIssue) ResultMessage(s(R.string.v2_tiles_error), retry = { mapIssue = false; mapMode = true })
                when {
                    source == null -> ResultMessage(s(R.string.v2_not_configured))
                    listLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    listFailed -> ResultMessage(s(R.string.v2_find_error), retry = { refresh++ })
                    page.listings.isEmpty() -> ResultMessage(s(R.string.v2_empty))
                    else -> LazyColumn(
                        Modifier.weight(1f),
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
    }
    if (showFilters) {
        DiscoveryFilterSheet(filters, onDismiss = { showFilters = false }) {
            filters = it; showFilters = false; selectedId = null
        }
    }
    if (showLayers) {
        ModalBottomSheet(onDismissRequest = { showLayers = false }, containerColor = Brand.Pearl) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s(R.string.v2_layers), style = MaterialTheme.typography.titleLarge)
                Text(s(R.string.v2_layer_listings), color = Brand.Crimson, fontWeight = FontWeight.Bold)
                Text(s(R.string.v2_layers_cadastre_empty), color = Brand.Muted)
            }
        }
    }
}

@Composable
private fun ExploreHeader(
    searchText: String, language: String, source: DiscoverySource?, filters: DiscoveryFilters,
    mapMode: Boolean, styleConfigured: Boolean, demo: Boolean, onSeller: () -> Unit,
    onSearchChange: (String) -> Unit, onPlace: (DiscoveryPlace) -> Unit, onKind: (String?) -> Unit,
    onFilters: () -> Unit, onMode: () -> Unit
) {
    val focus = LocalFocusManager.current
    var searching by remember { mutableStateOf(false) }
    var suggestions by remember(source, searchText) { mutableStateOf<List<DiscoveryPlace>?>(null) }
    var placeError by remember(source, searchText) { mutableStateOf(false) }
    LaunchedEffect(searchText, searching, source) {
        if (!searching || searchText.trim().length < 2 || source == null) return@LaunchedEffect
        delay(260)
        try {
            suggestions = source.suggest(searchText.trim(), language)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            placeError = true
        }
    }

    Surface(color = Brand.Pearl, shape = RoundedCornerShape(24.dp), shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(APP_BRAND_ARABIC, color = Brand.Crimson, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
                FilledTonalButton(
                    onClick = onSeller, contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Brand.Rose, contentColor = Brand.Crimson)
                ) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s(R.string.v2_add_property))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchText, onValueChange = { onSearchChange(it.take(100)) },
                    modifier = Modifier.weight(1f).onFocusChanged { searching = it.isFocused },
                    singleLine = true, shape = RoundedCornerShape(16.dp),
                    placeholder = { Text(s(R.string.v2_place_search), maxLines = 1) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Brand.Crimson) },
                    trailingIcon = if (searchText.isEmpty()) null else ({
                        IconButton(onClick = { onSearchChange("") }) { Icon(Icons.Outlined.Close, s(R.string.clear)) }
                    }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val first = suggestions?.firstOrNull()
                        if (first != null) {
                            onPlace(first)
                            focus.clearFocus()
                        }
                    })
                )
                val filterLabel = s(R.string.v2_filters)
                FilledTonalIconButton(
                    onClick = onFilters,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = Brand.Rose, contentColor = Brand.Crimson
                    )
                ) { Icon(Icons.Outlined.Tune, filterLabel) }
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
                                        onPlace(place); focus.clearFocus()
                                    }.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PropertyKinds.forEach { (kind, label) ->
                            FilterChip(
                                selected = filters.kind == kind, onClick = { onKind(kind) },
                                label = { Text(s(label), maxLines = 1) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Brand.Rose, selectedLabelColor = Brand.Crimson
                                )
                            )
                        }
                    }
                    if (styleConfigured) {
                        val label = s(if (mapMode) R.string.v2_list_mode else R.string.v2_map_mode)
                        TextButton(onClick = onMode, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Icon(if (mapMode) Icons.Outlined.ViewList else Icons.Outlined.Map, null,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(label, maxLines = 1)
                        }
                    }
                }
            }
            if (demo) Text(
                s(R.string.v2_demo), color = Brand.Crimson, fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 3.dp)
            )
        }
    }
}
