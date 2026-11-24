package tn.malak.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import tn.malak.app.R
import tn.malak.app.ui.explore.*
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

private const val DirectPath = "/api/pilot/direct-listings"

private data class ListingDocumentReading(val reviewId: String, val kind: String, val area: PilotPropertyFact?)
private fun CertificateIdentityReview.forListing(id: String): ListingDocumentReading {
    require(reviewId == id)
    return ListingDocumentReading(id, detectedKind ?: "UNKNOWN", listingAreaEvidence(detectedKind, sourceFacts))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DirectOwnerListing(
    session: PilotOcrSession,
    account: String,
    listing: JSONObject?,
    canAttachDocuments: Boolean,
    onBack: () -> Unit,
    onPublished: (String) -> Unit,
    readReview: suspend (PilotOcrSession, String) -> CertificateIdentityReview = { owner, id ->
        parsePilotComparison(PilotListingClient.call(owner, "/api/pilot/reviews/$id"))
    }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedback.current
    val prefs = remember(context, account) {
        val key = MessageDigest.getInstance("SHA-256").digest(account.toByteArray())
            .joinToString("") { "%02x".format(it) }
        context.getSharedPreferences("amlak_direct_$key", Context.MODE_PRIVATE)
    }
    fun initial(field: String, fallback: String = "") =
        listing?.optString(field, fallback) ?: prefs.getString(field, fallback).orEmpty()

    val editing = listing != null
    var kind by rememberSaveable { mutableStateOf(initial("kind")) }
    var title by rememberSaveable { mutableStateOf(initial("title")) }
    var region by rememberSaveable { mutableStateOf(initial("region")) }
    var price by rememberSaveable { mutableStateOf(initial("price")) }
    var area by rememberSaveable { mutableStateOf(initial("area")) }
    var description by rememberSaveable { mutableStateOf(initial("description")) }
    var latitude by rememberSaveable { mutableStateOf(initial("latitude")) }
    var longitude by rememberSaveable { mutableStateOf(initial("longitude")) }
    val photos = remember(prefs, editing) {
        mutableStateListOf<DraftListingPhoto>().apply {
            if (!editing) {
                val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }
                    .map { it.uri }.toSet()
                val saved = runCatching { JSONArray(prefs.getString("photos", "[]")) }.getOrElse { JSONArray() }
                for (index in 0 until saved.length().coerceAtMost(8)) {
                    val row = saved.optJSONObject(index) ?: continue
                    val uri = Uri.parse(row.optString("uri"))
                    val length = row.optLong("length")
                    if (uri in granted && uri.scheme == "content" && length in 20..PILOT_PHOTO_MAX_BYTES) {
                        add(DraftListingPhoto(uri, row.optString("name"), length))
                    }
                }
            }
        }
    }
    var reviewId by remember(prefs, editing) {
        mutableStateOf(if (editing) null else prefs.getString("documentReview", null))
    }
    var readingDocuments by rememberSaveable { mutableStateOf(false) }
    var reading by remember(session) { mutableStateOf<ListingDocumentReading?>(null) }
    var reviewLoadFailed by remember(reviewId) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(session, reviewId, reload) {
        val id = reviewId ?: return@LaunchedEffect
        if (reading?.reviewId == id) return@LaunchedEffect
        reviewLoadFailed = false
        try {
            reading = readReview(session, id).forListing(id)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            reviewLoadFailed = true
        }
    }
    var choosingPoint by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableIntStateOf(0) }

    val draft = DirectListingDraft(kind, title, region, price, area, description, latitude, longitude)
    val point = draft.point
    var stageIndex by rememberSaveable {
        val saved = if (editing) 0 else prefs.getInt("stage", 0)
        val stage = DirectListingStage.entries.getOrElse(saved) { DirectListingStage.Kind }
        mutableIntStateOf(draft.resume(stage).ordinal)
    }
    val stage = DirectListingStage.entries[stageIndex]
    val preview = stage == DirectListingStage.Preview
    val currentPhotos = photos.toList()
    LaunchedEffect(draft, currentPhotos, stage, editing, busy) {
        if (editing || busy) return@LaunchedEffect
        val photoArray = JSONArray()
        currentPhotos.forEach { photo ->
            photoArray.put(JSONObject().put("uri", photo.uri.toString())
                .put("name", photo.name).put("length", photo.length))
        }
        prefs.edit().apply {
            draft.fields.forEach { (field, value) -> putString(field, value) }
            putInt("stage", stageIndex)
            putString("photos", photoArray.toString())
        }.apply()
    }

    val focus = LocalFocusManager.current
    val previousStep = {
        if (!busy) {
            focus.clearFocus()
            error = 0
            if (stageIndex > 0) stageIndex-- else onBack()
        }
    }
    if (readingDocuments) {
        val intake = remember(prefs) {
            prefs.getString("documentIntake", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("documentIntake", it).apply()
            }
        }
        SellerOcrJourney(
            draftScope = "$account/$intake",
            intakePhotos = photos,
            onBack = { prefs.edit().remove("documentIntake").apply(); readingDocuments = false },
            onDocumentRead = { report ->
                report.reviewId?.let { id ->
                    prefs.edit().putString("documentReview", id).remove("documentIntake").apply()
                    reading = report.forListing(id)
                    reviewId = id
                    readingDocuments = false
                }
            }
        )
        return
    }
    BackHandler(enabled = !choosingPoint, onBack = previousStep)
    if (choosingPoint) {
        DirectLocationPicker(point?.let { DiscoveryPoint(it.first, it.second) },
            onDismiss = { choosingPoint = false },
            onSelect = {
                latitude = String.format(Locale.ROOT, "%.3f", it.latitude)
                longitude = String.format(Locale.ROOT, "%.3f", it.longitude)
                choosingPoint = false
            }
        )
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        PremiumPageHeading(s(when {
            editing -> R.string.edit_property
            else -> R.string.direct_heading
        }), if (busy) null else previousStep)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s(R.string.direct_step_count, stageIndex + 1, DirectListingStage.entries.size),
                color = Brand.Muted, style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(
                progress = { (stageIndex + 1).toFloat() / DirectListingStage.entries.size },
                modifier = Modifier.fillMaxWidth(),
                color = Brand.Crimson, trackColor = Brand.NeutralContainer
            )
        }
        val scroll = key(stage) { rememberScrollState() }
        Column(
            Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(s(when (stage) {
                DirectListingStage.Kind -> R.string.direct_stage_kind
                DirectListingStage.Location -> if (editing) R.string.smart_location_heading else R.string.direct_stage_location
                DirectListingStage.Details -> R.string.direct_stage_details
                DirectListingStage.Preview -> R.string.direct_preview
            }), color = Brand.Ink, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            when (stage) {
                DirectListingStage.Kind -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PropertyKinds.forEach { (value, label) ->
                            if (value != null) FilterChip(kind == value, { kind = value; error = 0 }, { Text(s(label)) })
                        }
                    }
                    if(!editing&&canAttachDocuments){
                        OutlinedButton(onClick={focus.clearFocus();readingDocuments=true},modifier=Modifier.fillMaxWidth()){
                            Text(s(R.string.intake_documents_and_photos))
                        }
                    }
                }
                DirectListingStage.Location -> {
                    DirectField(region, { region = it.take(100) }, R.string.property_region)
                    OutlinedButton(onClick = { choosingPoint = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.LocationOn, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(s(if (point == null) R.string.direct_choose_location else R.string.direct_change_location))
                    }
                    if (!editing) {
                        Surface(color = Color.White, contentColor = Brand.Ink,
                            shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Brand.Border)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                ListingPhotoEditor(photos, directPublishing = true, persistAccess = true)
                                Text(s(R.string.direct_media_public), color = Brand.Muted,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                DirectListingStage.Details -> {
                    DirectField(title, { title = it.take(100) }, R.string.property_name)
                    val areaFocus = remember { FocusRequester() }
                    val keyboard = LocalSoftwareKeyboardController.current
                    if (!editing && canAttachDocuments) {
                        OutlinedButton(onClick = { focus.clearFocus(); readingDocuments = true }) {
                            Text(s(R.string.listing_read_documents))
                        }
                    }
                    if (reviewId != null) {
                        Text(s(R.string.listing_review_attached), color = Brand.Ink)
                        val current = reading?.takeIf { it.reviewId == reviewId }
                        val fact = current?.area
                        when {
                            reviewLoadFailed -> {
                                Text(s(R.string.owner_restore_failed), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { reload++ }) { Text(s(R.string.owner_retry)) }
                            }
                            current == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            fact != null -> ListingAreaSuggestion(current.kind, fact, area,
                                onAccept = { area = it },
                                onEditPart = { area = it; areaFocus.requestFocus(); keyboard?.show() })
                            else -> Text(s(R.string.listing_area_none), color = Brand.Muted)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DirectField(area, { area = it.take(16) }, R.string.area_m2, Modifier.weight(1f).focusRequester(areaFocus), true)
                        DirectField(price, { price = it.take(16) }, R.string.price_tnd, Modifier.weight(1f), true)
                    }
                    OutlinedTextField(description, { description = it.take(650) },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                        label = { Text(s(R.string.smart_description_heading)) })
                }
                DirectListingStage.Preview -> {
                    OwnerListingPreview(title.trim(), region.trim(), area, price, description, photos)
                    if (!editing) Text(s(if (reviewId == null) R.string.direct_preview_note
                        else R.string.listing_review_preview), color = Brand.Muted)
                }
            }
            if (error != 0) Text(s(error), color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = {
            if (!draft.canContinue(stage)) {
                error = when (stage) {
                    DirectListingStage.Kind -> R.string.direct_choose_kind
                    DirectListingStage.Location -> R.string.direct_check_location
                    DirectListingStage.Details -> R.string.direct_check_details
                    DirectListingStage.Preview -> R.string.direct_missing_fields
                }
            } else if (preview && reviewId != null && !canAttachDocuments) {
                error = R.string.listing_review_unavailable
            } else if (!preview) {
                focus.clearFocus()
                error = 0
                feedback(AppCue.Action)
                stageIndex++
            } else {
                busy = true
                error = 0
                scope.launch {
                    val payload = JSONObject(draft.fields)
                    val id = try {
                        if (listing != null) {
                            val id = listing.getString("id")
                            PilotListingClient.call(session, "$DirectPath/$id/edit",
                                payload.put("version", listing.getInt("version")))
                            id
                        } else {
                            val fingerprint = payload.toString()
                            val previous = prefs.getString("fingerprint", null)
                            val requestKey = prefs.getString("requestKey", null)?.takeIf { previous == fingerprint }
                                ?: UUID.randomUUID().toString()
                            check(prefs.edit().putString("fingerprint", fingerprint).putString("requestKey", requestKey).commit())
                            val draft = PilotListingClient.call(session, DirectPath, payload.put("requestKey", requestKey))
                                .getJSONObject("listing")
                            val id = draft.getString("id")
                            if (draft.getString("status") == "DRAFT") {
                                val uploaded = JSONArray()
                                photos.forEach {
                                    uploaded.put(PilotListingClient.photo(context, session, draft.getString("reviewId"), it.uri, it.length))
                                }
                                val publish = JSONObject().put("photoIds", uploaded).put("reviewId", reviewId)
                                val published = PilotListingClient.call(session, "$DirectPath/$id/publish", publish)
                                    .getJSONObject("listing")
                                check(reviewId == null || published.optString("reviewId") == reviewId)
                            } else {
                                check(reviewId == null || draft.optString("reviewId") == reviewId)
                            }
                            prefs.edit().clear().apply()
                            id
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        error = R.string.direct_publish_failed
                        null
                    } finally {
                        busy = false
                    }
                    if (id != null) { feedback(AppCue.Confirm); onPublished(id) }
                }
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 50.dp)) {
            Text(s(when {
                busy -> R.string.submission_sending
                stage == DirectListingStage.Details -> R.string.direct_preview
                !preview -> R.string.direct_next
                editing -> R.string.direct_save
                else -> R.string.direct_publish
            }))
        }
    }
}

@Composable
private fun DirectField(value: String, update: (String) -> Unit, label: Int,
    modifier: Modifier = Modifier.fillMaxWidth(), decimal: Boolean = false
) {
    OutlinedTextField(value, update, modifier, label = { Text(s(label)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Text))
}

@Composable
private fun DirectLocationPicker(previous: DiscoveryPoint?, onDismiss: () -> Unit, onSelect: (DiscoveryPoint) -> Unit) {
    val start = previous ?: InitialCenter
    var point by remember { mutableStateOf(start) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Brand.Pearl) {
            Column {
                PremiumPageHeading(s(R.string.direct_choose_location), onDismiss)
                Text(s(R.string.direct_pin_hint), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = Brand.Muted)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DiscoveryMap(styleUrl = DiscoveryStyleUrl, initial = DiscoveryCamera(start,
                        if (previous != null) 14.0 else 7.0, InitialBounds), jump = null,
                        markers = DiscoveryMarkers(emptyList()), selectedId = null, priceLabel = { "" },
                        modifier = Modifier.fillMaxSize(), onCameraIdle = { camera, _ -> point = camera.center },
                        onListing = {}, onCluster = {}, onFailure = { failed = true; ready = false },
                        onReady = { ready = true })
                    if (ready) Surface(shape = MaterialTheme.shapes.medium, color = Brand.Pearl) {
                        Icon(Icons.Outlined.LocationOn, null, Modifier.padding(6.dp).size(30.dp), tint = Brand.Crimson)
                    } else if (failed) Text(s(R.string.v2_tiles_error)) else CircularProgressIndicator()
                }
                Button(onClick = { onSelect(point) }, enabled = ready,
                    modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(s(R.string.direct_confirm_pin))
                }
            }
        }
    }
}
