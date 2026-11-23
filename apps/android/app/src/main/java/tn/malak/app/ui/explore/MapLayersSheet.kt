package tn.malak.app.ui.explore

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tn.malak.app.R
import tn.malak.app.ui.Brand
import tn.malak.app.ui.s

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MapLayersSheet(
    salesVisible: Boolean,
    atlasVisible: Boolean,
    administrativeVisible: Boolean,
    listingsAvailable: Boolean,
    industrialVisible: Boolean,
    lotsVisible: Boolean,
    onLotsVisible: (Boolean) -> Unit,
    onSalesVisible: (Boolean) -> Unit,
    onAtlasVisible: (Boolean) -> Unit,
    onAdministrativeVisible: (Boolean) -> Unit,
    onIndustrialVisible: (Boolean) -> Unit,
    onBrowseIndustrial: () -> Unit,
    onAtlasLens: () -> Unit,
    onSources: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Brand.Pearl
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s(R.string.v2_layers), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall, color = Brand.Ink)
                IconButton(onClick = onSources) {
                    Icon(Icons.Outlined.Info, s(R.string.discover_map_sources), tint = Brand.Ink)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, s(R.string.close), tint = Brand.Ink)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAtlasLens) {
                    Icon(Icons.Outlined.Map, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s(R.string.atlas_lens_short))
                }
                OutlinedButton(onClick = onBrowseIndustrial) {
                    Icon(Icons.Outlined.Factory, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s(R.string.afi_browse))
                }
            }
            LayerSwitch(Icons.Outlined.Layers, s(R.string.layer_sales),
                s(if (listingsAvailable) R.string.layer_sales_hint else R.string.v2_not_configured),
                salesVisible && listingsAvailable, onSalesVisible.takeIf { listingsAvailable })
            LayerSwitch(Icons.Outlined.Factory, s(R.string.afi_layer), s(R.string.afi_layer_hint),
                industrialVisible, onIndustrialVisible)
            LayerSwitch(Icons.Outlined.Map, s(R.string.afi_lots_layer), s(R.string.afi_lots_hint),
                lotsVisible && industrialVisible, onLotsVisible.takeIf { industrialVisible })
            LayerSwitch(Icons.Outlined.Map, s(R.string.layer_atlas_admin), s(R.string.layer_atlas_admin_hint),
                administrativeVisible, onAdministrativeVisible)
            LayerSwitch(Icons.Outlined.Map, s(R.string.layer_atlas),
                s(if (listingsAvailable) R.string.layer_atlas_hint else R.string.layer_atlas_parcels_unavailable),
                atlasVisible && listingsAvailable, onAtlasVisible.takeIf { listingsAvailable })
            LayerSwitch(Icons.Outlined.Search, s(R.string.layer_purchase), s(R.string.layer_purchase_pending),
                checked = false, onChecked = null)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun LayerSwitch(
    icon: ImageVector,
    label: String,
    detail: String,
    checked: Boolean,
    onChecked: ((Boolean) -> Unit)?
) {
    Surface(color = Color.White, contentColor = Brand.Ink, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Brand.Border)) {
        Row(
            Modifier.fillMaxWidth().then(if (onChecked == null) Modifier else
                Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChecked)
                    .semantics { contentDescription = label }
            ).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = if (checked) Brand.Crimson else Brand.Muted)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(detail, color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (onChecked != null) {
                Switch(checked = checked, onCheckedChange = null)
            }
        }
    }
}

@Composable
internal fun MapSourcesDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s(R.string.discover_map_sources)) },
        text = {
            Column {
                TextButton(onClick = { uri.openUri("https://www.openstreetmap.org/copyright") }) {
                    Text("© OpenStreetMap")
                }
                TextButton(onClick = { uri.openUri("https://openmaptiles.org/") }) {
                    Text("© OpenMapTiles")
                }
                TextButton(onClick = { uri.openUri("https://openfreemap.org/") }) {
                    Text("OpenFreeMap")
                }
                Text(s(R.string.layer_atlas_credit), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s(R.string.close)) } }
    )
}
