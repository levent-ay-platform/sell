package tn.malak.app.ui.explore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tn.malak.app.R
import tn.malak.app.ui.Brand
import tn.malak.app.ui.s

/**
 * The radar merges the authorized public listing feed and separately sourced
 * locally mapped AFI lots. AFI plans are NOT advertisements or live availability.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RadarResultsSheet(
    radar: DiscoveryRadar, industrialHits: List<IndustrialRadarHit>?,
    industrialFailed: Boolean, listings: List<DiscoveryListing>,
    listingsLoading: Boolean, listingsFailed: Boolean, listingsAvailable: Boolean,
    language: String, onIndustrialZone: (String) -> Unit,
    onListing: (String) -> Unit, onDismiss: () -> Unit, onNozha: (() -> Unit)? = null
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Brand.Pearl,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(s(R.string.radar_results_title), style = MaterialTheme.typography.titleLarge, color = Brand.Ink)
            Text(s(R.string.radar_radius, radar.radiusKm), color = Brand.Muted,
                style = MaterialTheme.typography.bodyMedium)
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 520.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(s(R.string.afi_layer), color = Brand.Ink,
                        style = MaterialTheme.typography.titleMedium)
                    Text(s(R.string.radar_afi_note), color = Brand.Muted,
                        style = MaterialTheme.typography.bodySmall)
                }
                when {
                    industrialFailed -> item {
                        Text(s(R.string.afi_load_failed), color = Brand.Muted)
                    }
                    industrialHits == null -> item {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand.Crimson)
                    }
                    else -> {
                        items(industrialHits, key = { it.zone.id }) { hit ->
                            Surface(
                                onClick = { onIndustrialZone(hit.zone.id) },
                                shape = RoundedCornerShape(14.dp), color = Color.White,
                                contentColor = Brand.Ink,
                                border = BorderStroke(1.dp, Brand.Border)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Outlined.Factory, null, tint = Brand.Crimson)
                                    Column(Modifier.weight(1f)) {
                                        Text(hit.zone.label(language),
                                            style = MaterialTheme.typography.titleSmall)
                                        Text(s(R.string.radar_afi_inside, hit.lotsInside),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Brand.Muted)
                                        if (hit.approximate) {
                                            Text(s(R.string.radar_afi_approximate),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Brand.Muted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (listingsAvailable) {
                    item {
                        Text(s(R.string.radar_sale_results), color = Brand.Ink,
                            style = MaterialTheme.typography.titleMedium)
                    }
                    if (listingsLoading) item {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand.Crimson)
                    } else if (listingsFailed) item {
                        Text(s(R.string.v2_find_error), color = Brand.Muted)
                    } else {
                        items(listings, key = { it.id }) { listing ->
                            ListingCard(listing, language, { onListing(listing.id) })
                        }
                    }
                }
                if (onNozha != null) item {
                    OutlinedButton(onClick = onNozha, modifier = Modifier.fillMaxWidth()) {
                        Text(s(R.string.nozha_project_approximate))
                    }
                }
                if (industrialHits?.isEmpty() == true && listings.isEmpty() && onNozha == null &&
                    !listingsLoading && !listingsFailed) {
                    item {
                        Text(s(R.string.radar_no_results), color = Brand.Muted,
                            modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
            }
        }
    }
}
