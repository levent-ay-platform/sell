package tn.malak.app.ui.explore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tn.malak.app.R
import tn.malak.app.ui.Brand
import tn.malak.app.ui.s

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IndustrialMapSheet(
    catalog: IndustrialCatalog?, plan: IndustrialPlan?, showPlan: Boolean, language: String,
    initialSearch: String = "", failed: Boolean, onZone: (IndustrialZone) -> Unit, onLot: (IndustrialLot) -> Unit,
    onBrowse: () -> Unit, onDismiss: () -> Unit
) {
    var query by rememberSaveable(initialSearch) { mutableStateOf(initialSearch) }
    var listedOnly by rememberSaveable(plan?.zone?.id) { mutableStateOf(false) }
    var openPdf by rememberSaveable(plan?.zone?.id) { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = Brand.Pearl,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showPlan) plan?.zone?.label(language) ?: s(R.string.afi_plans) else s(R.string.afi_layer),
                    modifier = Modifier.weight(1f), color = Brand.Ink,
                    style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, s(R.string.close), tint = Brand.Ink) }
            }
            when {
                failed -> ResultMessage(s(R.string.afi_load_failed))
                catalog == null || showPlan && plan == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                showPlan && plan != null -> LazyColumn(
                    modifier = Modifier.heightIn(max = 520.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(s(R.string.afi_indicative), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
                        val date = plan.layoutDate.ifBlank { catalog.capturedAt }
                        Text(s(R.string.afi_snapshot_date, date), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (plan.documents.isNotEmpty()) {
                        item { Text(s(R.string.afi_documents), color = Brand.Ink, style = MaterialTheme.typography.titleMedium) }
                        items(plan.documents, key = { it.page }) { document ->
                            Surface(
                                onClick = {
                                    if (document.pdf != null) openPdf = document.pdf else uriHandler.openUri(document.page)
                                },
                                shape = RoundedCornerShape(14.dp), color = Color.White, contentColor = Brand.Ink
                            ) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Description, null, tint = Brand.Muted)
                                    Text(document.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Text(s(R.string.parcel_preview), color = Brand.Crimson, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            TextButton(onClick = { uriHandler.openUri(document.page) }) { Text(s(R.string.afi_original)) }
                        }
                    } else {
                        item { Text(s(R.string.afi_no_documents), color = Brand.Muted) }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s(R.string.afi_lot_count, plan.zone.shapeCount), Modifier.weight(1f),
                                color = Brand.Ink, style = MaterialTheme.typography.titleMedium)
                            FilterChip(listedOnly, { listedOnly = !listedOnly }, { Text(s(R.string.afi_listed_only)) })
                        }
                        if (listedOnly && plan.offerDate.isNotBlank()) {
                            Text(s(R.string.afi_offer_date, plan.offerDate), color = Brand.Muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    val lots = if (listedOnly) plan.lots.filter(IndustrialLot::listed) else plan.lots
                    if (lots.isEmpty()) item { Text(s(R.string.afi_no_lots), color = Brand.Muted) }
                    items(lots, key = { it.id }) { lot ->
                        Surface(
                            onClick = { onLot(lot) }, color = Color.White, contentColor = Brand.Ink,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(10.dp)) {
                                Text(industrialLotTitle(lot), style = MaterialTheme.typography.titleSmall)
                                IndustrialLotFacts(lot, language, plan.offerDate)
                            }
                        }
                    }
                    item {
                        TextButton(onClick = onBrowse) { Text(s(R.string.afi_browse)) }
                        TextButton(onClick = { uriHandler.openUri(IndustrialAgencyMap) }) { Text(s(R.string.afi_source)) }
                    }
                }
                else -> {
                    OutlinedTextField(
                        query, { query = it.take(80) }, Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text(s(R.string.afi_search)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }
                    )
                    val results = catalog.zones.filter { it.matches(query) }
                    LazyColumn(
                        Modifier.heightIn(max = 430.dp), contentPadding = PaddingValues(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        if (results.isEmpty()) item { Text(s(R.string.v2_places_empty), color = Brand.Muted) }
                        items(results, key = { it.id }) { zone ->
                            Surface(onClick = { onZone(zone) }, color = Color.White, contentColor = Brand.Ink,
                                shape = RoundedCornerShape(12.dp)) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Icon(Icons.Outlined.Factory, null, tint = Brand.Muted)
                                    Text(zone.label(language), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                    Text(s(R.string.afi_lot_count, zone.shapeCount), color = Brand.Muted,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val document = plan?.documents?.firstOrNull { it.pdf != null && it.pdf == openPdf }
    if (document?.pdf != null) {
        PropertyDocumentViewer(
            PublicPropertyDocument(document.pdf, "AFI_PLAN", "application/pdf", document.pdf),
            document.title, { openPdf = null }
        )
    }
}

@Composable
internal fun IndustrialMapCard(
    zone: IndustrialZone, plan: IndustrialPlan?, lot: IndustrialLot?,
    language: String, onOpen: () -> Unit, onDismiss: () -> Unit
) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), shadowElevation = 4.dp,
        color = Color.White, contentColor = Brand.Ink, border = BorderStroke(1.dp, Brand.Border)) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(zone.label(language), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, s(R.string.close)) }
            }
            if (lot != null) {
                Text(industrialLotTitle(lot), style = MaterialTheme.typography.titleSmall)
                IndustrialLotFacts(lot, language, plan?.offerDate.orEmpty())
            } else {
                Text(s(R.string.afi_lot_count, zone.shapeCount), color = Brand.Muted)
            }
            Text(s(R.string.afi_indicative), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onOpen, modifier = Modifier.align(Alignment.End)) { Text(s(R.string.afi_plans)) }
        }
    }
}

@Composable
private fun industrialLotTitle(lot: IndustrialLot): String =
    lot.number?.let { s(R.string.afi_lot_number, it) } ?: s(R.string.afi_unnumbered_lot)

@Composable
private fun IndustrialLotFacts(lot: IndustrialLot, language: String, offerDate: String) {
    val area = lot.area?.let { s(R.string.v2_area, discoveryNumber(it, language)) }
        ?: lot.estimatedArea?.let { s(R.string.afi_estimated_area, discoveryNumber(it, language)) }
    if (area != null) Text(area, color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
    if (lot.anchor == null) Text(s(R.string.afi_no_shape), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
    if (lot.listed && offerDate.isNotBlank()) {
        val recorded = if (lot.availabilityRecorded) R.string.afi_available_date else R.string.afi_offer_date
        Text(s(recorded, offerDate), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
        lot.price?.let { price ->
            Text(s(if (lot.priceIsForLot) R.string.afi_lot_price else R.string.afi_zone_price,
                discoveryNumber(price, language)), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
