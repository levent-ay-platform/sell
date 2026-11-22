package tn.malak.app.ui.explore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tn.malak.app.R
import tn.malak.app.ui.Brand
import tn.malak.app.ui.s
import kotlin.math.roundToInt

@Composable
internal fun RadarControls(
    radius: Int, canSearch: Boolean, dataAvailable: Boolean,
    onRadius: (Int) -> Unit, onSearch: () -> Unit, onClose: () -> Unit
) {
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), Color.White, Brand.Ink,
        shadowElevation = 4.dp, border = BorderStroke(1.dp, Brand.Border)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Radar, null, tint = Brand.Crimson)
                Text(s(R.string.radar_panel_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(s(R.string.radar_radius, radius), color = Brand.Crimson, style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, s(R.string.close)) }
            }
            Text(s(R.string.radar_hint), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
            Slider(radius.toFloat(), { onRadius(it.roundToInt()) }, valueRange = 1f..20f, steps = 18,
                colors = SliderDefaults.colors(thumbColor = Brand.Crimson, activeTrackColor = Brand.Crimson))
            if (!dataAvailable) {
                Text(s(R.string.v2_map_data_unavailable), color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onSearch, enabled = canSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(s(R.string.radar_apply))
            }
        }
    }
}
