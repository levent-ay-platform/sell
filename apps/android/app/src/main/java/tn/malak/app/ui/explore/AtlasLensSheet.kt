package tn.malak.app.ui.explore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tn.malak.app.R
import tn.malak.app.ui.Brand
import tn.malak.app.ui.s
import kotlin.math.roundToInt

/**
 * A source-scoped, offline Atlas lens over reviewed public *planning snapshots*.
 * This sheet never identifies title holders, legal parcel boundaries, or live sale availability.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AtlasLensSheet(
    focus: DiscoveryPoint,
    placeLabel: String?,
    catalog: IndustrialCatalog?,
    loadFailed: Boolean,
    language: String,
    onZone: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var radiusKm by rememberSaveable { mutableIntStateOf(25) }
    val nearby = remember(focus, catalog, radiusKm) {
        catalog?.let { atlasLensNearbyZones(focus, it, radiusKm) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Brand.Pearl,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Map, null, tint = Brand.Crimson)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(s(R.string.atlas_lens_title),
                        color = Brand.Ink, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text(placeLabel?.takeIf(String::isNotBlank)
                            ?: s(R.string.atlas_lens_map_center),
                        style = MaterialTheme.typography.bodySmall, color = Brand.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, s(R.string.close), tint = Brand.Ink)
                }
            }

            Text(s(R.string.atlas_lens_radius_note),
                color = Brand.Muted, style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 25, 50).forEach { distance ->
                    FilterChip(
                        selected = radiusKm == distance,
                        onClick = { radiusKm = distance },
                        label = { Text(s(R.string.atlas_lens_radius_chip, distance)) }
                    )
                }
            }

            when {
                loadFailed -> {
                    Text(s(R.string.afi_load_failed),
                        color = Brand.Muted, style = MaterialTheme.typography.bodyMedium)
                }
                catalog == null -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand.Crimson)
                }
                else -> {
                    val count = nearby.orEmpty().size
                    val lotCount = atlasLensPlannedLots(nearby.orEmpty())
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Brand.NeutralContainer,
                        contentColor = Brand.Ink
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(s(R.string.atlas_lens_nearby_zones, count),
                                style = MaterialTheme.typography.titleMedium)
                            Text(s(R.string.atlas_lens_planned_lots, lotCount),
                                style = MaterialTheme.typography.bodyMedium)
                            Text(s(R.string.afi_snapshot_date, catalog.capturedAt),
                                color = Brand.Muted, style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Text(s(R.string.atlas_lens_disclaimer),
                        style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                    Text(s(R.string.atlas_lens_cadastre_unknown),
                        style = MaterialTheme.typography.bodySmall, color = Brand.Muted)

                    if (nearby.isNullOrEmpty()) {
                        Text(s(R.string.atlas_lens_empty),
                            color = Brand.Muted, style = MaterialTheme.typography.bodyLarge)
                    } else {
                        LazyColumn(
                            Modifier.heightIn(max = 390.dp),
                            contentPadding = PaddingValues(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(nearby.take(15), key = { it.zone.id }) { hit ->
                                Surface(
                                    onClick = { onZone(hit.zone.id) },
                                    color = Color.White, contentColor = Brand.Ink,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, Brand.Border)
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(Icons.Outlined.Factory, null, tint = Brand.Crimson)
                                        Column(Modifier.weight(1f)) {
                                            Text(hit.zone.label(language),
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(s(R.string.afi_lot_count, hit.zone.shapeCount),
                                                color = Brand.Muted,
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                        val formatted = discoveryNumber(
                                            (hit.centerDistanceKm * 10).roundToInt().div(10.0).toString(),
                                            language
                                        )
                                        Text(s(R.string.atlas_lens_distance, formatted),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Brand.Muted)
                                    }
                                }
                            }
                            if (nearby.size > 15) item {
                                Text(s(R.string.atlas_lens_limited, 15, nearby.size),
                                    color = Brand.Muted,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
