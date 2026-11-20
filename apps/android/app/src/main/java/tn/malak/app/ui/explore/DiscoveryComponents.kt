package tn.malak.app.ui.explore

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import tn.malak.app.R
import tn.malak.app.ui.Brand
import tn.malak.app.ui.s
import java.text.NumberFormat
import java.util.Locale

internal val PropertyKinds = listOf(
    null to R.string.all,
    "LAND" to R.string.v2_land,
    "APARTMENT" to R.string.v2_apartment,
    "HOUSE" to R.string.v2_house
)

internal fun discoveryNumber(amount: String, language: String): String =
    amount.toBigDecimalOrNull()?.let { number ->
        NumberFormat.getNumberInstance(Locale.forLanguageTag(if (language == "ar") "ar-TN" else "fr-TN"))
            .apply { maximumFractionDigits = 3 }.format(number)
    } ?: amount

@Composable
internal fun ListingCard(
    listing: DiscoveryListing, language: String, onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier.fillMaxWidth().clickable(onClickLabel = s(R.string.v2_overview), onClick = onClick),
        color = Brand.Pearl, shape = RoundedCornerShape(20.dp), shadowElevation = 4.dp
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ListingPhoto(listing, Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)), false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    s(R.string.v2_amount, discoveryNumber(listing.priceTnd, language)),
                    color = Brand.Crimson, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    listing.title, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = Brand.Ink
                )
                val facts = listOfNotNull(listing.region.takeIf(String::isNotBlank), listing.areaM2?.let {
                    s(R.string.v2_area, discoveryNumber(it, language))
                })
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
            }
        }
    }
}

@Composable
internal fun ListingDetail(
    source: DiscoverySource?,
    id: String,
    language: String,
    onBack: () -> Unit
) {
    var item by remember(id, source) { mutableStateOf<DiscoveryListing?>(null) }
    var loading by remember(id, source) { mutableStateOf(true) }
    var failed by remember(id, source) { mutableStateOf(false) }
    var retry by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(source, id, retry) {
        loading = true
        failed = false
        try {
            item = source?.detail(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().background(Brand.Pearl)) {
        Row(Modifier.padding(start = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, s(R.string.back))
            }
            Text(item?.region.orEmpty(), style = MaterialTheme.typography.titleMedium)
        }
        when {
            loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            failed -> ResultMessage(s(R.string.v2_find_error), retry = { retry++ })
            item == null -> ResultMessage(s(R.string.v2_detail_missing))
            else -> item?.let { listing ->
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ListingPhoto(listing, Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)), true)
                    Text(listing.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        s(R.string.v2_amount, discoveryNumber(listing.priceTnd, language)),
                        color = Brand.Crimson, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    val kindLabel = PropertyKinds.firstOrNull { it.first == listing.kind }?.second
                    val facts = listOfNotNull(kindLabel?.let { s(it) }, listing.areaM2?.let {
                        s(R.string.v2_area, discoveryNumber(it, language))
                    }, listing.rooms?.let { s(R.string.v2_rooms, it) })
                    Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
                    Text(s(when (listing.locationAccuracy) {
                        "EXACT_CONSENTED" -> R.string.v2_location_exact
                        "APPROXIMATE" -> R.string.v2_approximate
                        "AREA_ONLY" -> R.string.v2_area_only
                        else -> R.string.v2_location_unknown
                    }), color = Brand.Muted, style = MaterialTheme.typography.bodyMedium)
                    listing.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}

@Composable
private fun ListingPhoto(listing: DiscoveryListing, modifier: Modifier, large: Boolean) {
    var photo by remember(listing.photoUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(listing.photoUrl, large) {
        val url = listing.photoUrl ?: return@LaunchedEffect
        try {
            photo = loadDiscoveryPhoto(url, if (large) 900 else 240)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            photo = null
        }
    }
    Box(modifier.background(Brand.Rose), contentAlignment = Alignment.Center) {
        val bitmap = photo
        if (bitmap == null) {
            Icon(
                if (listing.kind == "LAND") Icons.Outlined.Landscape else Icons.Outlined.Home,
                contentDescription = null, tint = Brand.Crimson, modifier = Modifier.size(if (large) 48.dp else 28.dp)
            )
        } else {
            Image(bitmap.asImageBitmap(), s(R.string.v2_listing_photo),
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
internal fun ResultMessage(message: String, modifier: Modifier = Modifier, retry: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(message, color = Brand.Muted, style = MaterialTheme.typography.bodyMedium)
        if (retry != null) TextButton(onClick = retry) { Text(s(R.string.v2_retry)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoveryFilterSheet(
    filters: DiscoveryFilters, onDismiss: () -> Unit, onApply: (DiscoveryFilters) -> Unit
) {
    var minPrice by rememberSaveable { mutableStateOf(filters.minPrice.orEmpty()) }
    var maxPrice by rememberSaveable { mutableStateOf(filters.maxPrice.orEmpty()) }
    var minArea by rememberSaveable { mutableStateOf(filters.minArea.orEmpty()) }
    var maxArea by rememberSaveable { mutableStateOf(filters.maxArea.orEmpty()) }
    var rooms by rememberSaveable { mutableStateOf(filters.minRooms?.toString().orEmpty()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Brand.Pearl) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 22.dp)
                .verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(s(R.string.v2_filters), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterInput(R.string.v2_min_price, minPrice, { minPrice = it }, Modifier.weight(1f))
                FilterInput(R.string.v2_max_price, maxPrice, { maxPrice = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterInput(R.string.v2_min_area, minArea, { minArea = it }, Modifier.weight(1f))
                FilterInput(R.string.v2_max_area, maxArea, { maxArea = it }, Modifier.weight(1f))
            }
            if (filters.kind != "LAND") FilterInput(R.string.v2_min_rooms, rooms, { rooms = it }, Modifier.fillMaxWidth(), false)
            if (invalid) Text(s(R.string.v2_filter_error), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    minPrice = ""; maxPrice = ""; minArea = ""; maxArea = ""; rooms = ""; invalid = false
                }) { Text(s(R.string.v2_reset)) }
                Button(modifier = Modifier.weight(1f), onClick = {
                    val result = DiscoveryFilters.parse(filters.kind, minPrice, maxPrice, minArea, maxArea, rooms)
                    if (result == null) invalid = true else onApply(result)
                }) { Text(s(R.string.v2_apply)) }
            }
        }
    }
}

@Composable
private fun FilterInput(
    label: Int, value: String, onChange: (String) -> Unit, modifier: Modifier, decimal: Boolean = true
) {
    OutlinedTextField(
        value, { onChange(it.take(21)) }, modifier,
        singleLine = true, label = { Text(s(label)) },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number)
    )
}
