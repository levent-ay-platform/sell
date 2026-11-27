package tn.malak.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tn.malak.app.R

@Composable
internal fun ListingAreaSuggestion(
    kind: String, fact: PilotPropertyFact, area: String,
    onAccept: (String) -> Unit, onEditPart: (String) -> Unit
) {
    val matches = sameListingArea(area, fact)
    Surface(color = Brand.NeutralContainer, contentColor = Brand.Ink, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s(R.string.listing_document_area, fact.value), fontWeight = FontWeight.SemiBold)
            Text(s(R.string.listing_area_source, s(documentFamilyLabel(kind)), fact.page),
                style = MaterialTheme.typography.bodySmall)
            if (area.isNotBlank() && !matches) Text(s(R.string.listing_entered_area, area))
            OutlinedButton(onClick = { onAccept(fact.value) }, enabled = !matches) {
                Text(s(R.string.listing_use_area))
            }
            TextButton(onClick = { onEditPart(if (matches) "" else area) }) {
                Text(s(R.string.listing_use_part))
            }
        }
    }
}
