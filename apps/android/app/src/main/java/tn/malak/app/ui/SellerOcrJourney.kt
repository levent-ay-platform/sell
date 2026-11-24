package tn.malak.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tn.malak.app.BuildConfig
import tn.malak.app.R
import tn.malak.app.ui.parcel.OwnerParcelEditor
import tn.malak.app.ui.explore.DiscoveryPoint
import tn.malak.app.ui.explore.DiscoveryMap
import tn.malak.app.ui.explore.DiscoveryCamera
import tn.malak.app.ui.explore.DiscoveryMarkers
import tn.malak.app.ui.explore.DiscoveryStyleUrl
import tn.malak.app.ui.explore.InitialBounds
import tn.malak.app.ui.explore.AtlasAdminIndex
import tn.malak.app.ui.explore.loadAtlasAdminIndex
import tn.malak.app.ui.explore.AdminContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tn.malak.app.ui.parcel.OwnerParcelEditorSource
import tn.malak.app.ui.parcel.OwnerPlanDraft
import tn.malak.app.ui.parcel.OwnerMapBoundaryEditor
import tn.malak.app.ui.parcel.measureOwnerMap
import tn.malak.app.ui.parcel.OwnerBoundaryDraftStore
import tn.malak.app.ui.parcel.saveOwnerMapBoundary
import tn.malak.app.ui.parcel.PlanMapControlEditor
import tn.malak.app.ui.parcel.planControlIndices
import tn.malak.app.ui.parcel.selectedFact
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val MAX_DOC_BYTES = 12L * 1024L * 1024L
// Keep form acceptance identical to provisionIndividualOwnerCase's server contract.
private val ValidLandArea=Regex("""^[1-9][0-9]{0,8}(?:\.[0-9]{1,3})?$""")
private val ValidLandPrice=Regex("""^[1-9][0-9]{0,11}$""")
private val CardBorder = Color(0xFFCEC7CA)
private data class ChosenDocument(val uri:Uri, val displayName:String, val length:Long,
    val mime:String, val uploadKey:String=UUID.randomUUID().toString())

/** Translate only known audited reason codes; never show raw backend codes to users. */
private fun pairReasonText(code:String):Int=when(code){
    "MISSING_OR_AMBIGUOUS_LABELED_NAME"->R.string.pair_reason_name_unreadable
    "MULTIPLE_OWNERS_OR_SHARED_TITLE_NEEDS_REVIEW"->R.string.pair_reason_shared_title
    "CERTIFICATE_PAGES_MISSING"->R.string.pair_reason_pages_missing
    "CERTIFICATE_PAGE_NUMBER_INCONSISTENT"->R.string.pair_reason_pages_conflict
    "SCAN_QUALITY_NEEDS_REVIEW"->R.string.pair_reason_quality
    "LOW_OCR_CONFIDENCE"->R.string.pair_reason_low_confidence
    "NAME_TEXT_DIFFERS_OR_SCRIPT_MISMATCH"->R.string.pair_reason_name_diff
    "CIN_NUMBER_TEXT_DIFFERS"->R.string.pair_reason_cin_diff
    "CIN_NUMBER_UNCERTAIN"->R.string.pair_reason_cin_uncertain
    "DOCUMENT_TYPE_NOT_CONFIRMED","DOCUMENT_TYPE_MISMATCH"->R.string.pair_reason_document_type
    "LOW_CONFIDENCE_OR_CONFLICTING_ROLE","NO_ROLE_LABELED_PERSON",
    "UNSUPPORTED_ROLE_EXTRACTION"->R.string.pair_reason_name_unreadable
    "SHARED_RIGHTS_NEED_ROW_REVIEW","MULTIPLE_MATCHING_ROLES",
    "TARGET_RIGHTS_NOT_READABLE"->R.string.pair_reason_shared_title
    "DOCUMENT_CIN_UNREADABLE","CIN_UNREADABLE","DOCUMENT_CIN_MASKED"->R.string.pair_reason_cin_uncertain
    "ROLE_CIN_CONFLICT","CIN_TEXT_DIFFERS"->R.string.pair_reason_cin_diff
    "DOCUMENT_CIN_NOT_PRINTED"->R.string.pair_cin_missing
    "OFFICIAL_REGISTRY_NOT_CHECKED","ROLE_MATCH_NOT_OWNERSHIP"->R.string.pair_reason_not_official
    "OFFICIAL_CPF_AND_DOCUMENT_AUTHENTICITY_NOT_CHECKED"->R.string.pair_reason_not_official
    else->R.string.pair_reason_other
}

private enum class ReadingStatus { PENDING, READING, EXTRACTED, REVIEW }

private data class CapacityOption(val code:String, val label:Int)
private val Capacities = listOf(
    CapacityOption("owner",R.string.capacity_owner),
    CapacityOption("partner",R.string.capacity_partner),
    CapacityOption("heir",R.string.capacity_heir),
    CapacityOption("mandate",R.string.capacity_mandate)
)

/**
 * Buyer-facing, private land draft wizard. There are no synthetic documents,
 * technical test sessions, manual API/token inputs or fabricated OCR results.
 * Photos are submitted only after explicit consent and verified owner authentication;
 * raw OCR models remain on the isolated private Huawei service.
 */
@Composable
internal fun SellerOcrJourney(onBack:()->Unit, draftScope:String?=null,resubmission:org.json.JSONObject?=null,
                     onDocumentRead: ((CertificateIdentityReview) -> Unit)? = null,
                     intakePhotos:SnapshotStateList<DraftListingPhoto>?=null,
                     onSubmitted:(String)->Unit={onBack()}) {
    val context=LocalContext.current
    val feedback=LocalFeedback.current
    val ownerSession=LocalOwnerEvidenceSession.current
    val authenticatedOwnerSession=LocalAuthenticatedOwnerSession.current
    val pilotOcrSession=LocalPilotOcrSession.current
    val directVerification=BuildConfig.MALAK_DIRECT_PUBLISHING && resubmission?.optBoolean("direct")==true
    // Pilot is a real server OCR session, but never a verified identity
    // or owner/case/publishing credential.
    val hasOwnerReviewSession=ownerSession!=null||authenticatedOwnerSession!=null
    val hasPilotReviewSession=pilotOcrSession!=null&&
        pilotOcrSession.expiresAtMs>System.currentTimeMillis()
    val canRunPrivateMatching=hasOwnerReviewSession||hasPilotReviewSession
    val canReadPrivateId=canRunPrivateMatching
    val scope=rememberCoroutineScope()
    var requestJob by remember {mutableStateOf<Job?>(null)}
    // Invalidate old asynchronous work whenever fields, documents, consented
    // case or owner session change. Cancellation of a blocking HTTPS request
    // alone cannot guarantee its callback will never arrive after a new run.
    var requestEpoch by remember {mutableIntStateOf(0)}
    // Each pilot number gets a separate local draft; never show another user's
    // unpublished property when switching accounts on the same Android device.
    val draftFile=remember(draftScope) {
        if(draftScope==null) "malak_unpublished_land_draft"
        else {
            val hash=java.security.MessageDigest.getInstance("SHA-256")
                .digest(draftScope.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            "malak_pilot_land_draft_"+hash
        }
    }
    val prefs=remember(context,draftFile){
        context.getSharedPreferences(draftFile,Context.MODE_PRIVATE)
    }
    val readingForListing = onDocumentRead != null
    fun exitJourney() {
        if (readingForListing) prefs.edit().clear().apply()
        onBack()
    }
    var pairProgress by remember(draftFile) {mutableStateOf<PairProgress?>(null)}
    var pairReport by remember(draftFile) {mutableStateOf<CertificateIdentityReview?>(null)}
    var provisionedCase by remember(draftFile) {mutableStateOf<OwnerEvidenceSession?>(null)}
    var pairError by remember(draftFile) {mutableIntStateOf(0)}
    var customerName by remember(draftFile){mutableStateOf(resubmission?.optString("customerName").orEmpty())}
    var sending by remember(draftFile){mutableStateOf(false)}
    var submitted by remember(draftFile){mutableStateOf(false)}
    var submittedReference by remember(draftFile){mutableStateOf<String?>(null)}
    var submittedId by remember(draftFile){mutableStateOf<String?>(null)}
    var submittedStatus by remember(draftFile){mutableStateOf("PENDING_REVIEW")}
    val recoveryScope=resubmission?.optString("id")?:prefs.getString("review_scope","").orEmpty()
    val savedReview=remember(draftFile,recoveryScope){
        prefs.getString("review_id",null)?.takeIf{prefs.getString("review_scope","")==recoveryScope}
    }
    var restoring by remember(draftFile){mutableStateOf(savedReview!=null&&pilotOcrSession!=null)}
    var restoreFailed by remember(draftFile){mutableStateOf(false)}
    var restoreAttempt by remember(draftFile){mutableIntStateOf(0)}
    var restoredReview by remember(draftFile){mutableStateOf(false)}
    var showAnalysisDetails by remember(draftFile){mutableStateOf(false)}
    var identityDateBusy by remember(draftFile){mutableStateOf(false)}
    var identityDateError by remember(draftFile){mutableIntStateOf(0)}
    var showListingExtras by rememberSaveable(draftFile){mutableStateOf(false)}
    var chooseListingPoint by rememberSaveable(draftFile){mutableStateOf(false)}
    var chooseMapBoundary by remember(draftFile){mutableStateOf(false)}
    var georefDraft by remember(draftFile){mutableStateOf<OwnerPlanDraft?>(null)}
    val boundaryStore=remember(draftFile,readingForListing,resubmission?.optString("id")){
        if(draftScope.isNullOrBlank()||readingForListing)null
        else OwnerBoundaryDraftStore(context.applicationContext,draftFile+":"+(resubmission?.optString("id")?:"new"))
    }
    var mapBoundary by remember(boundaryStore){mutableStateOf(emptyList<DiscoveryPoint>())}
    var boundaryLocalReady by remember(boundaryStore){mutableStateOf(boundaryStore==null)}
    var boundaryLocalStatus by remember(boundaryStore){mutableIntStateOf(0)}
    var boundaryLocalBusy by remember(boundaryStore){mutableStateOf(false)}
    var boundarySent by remember(mapBoundary,ownerSession,provisionedCase){mutableStateOf(false)}
    var boundarySendBusy by remember{mutableStateOf(false)}
    var boundarySendError by remember(mapBoundary,ownerSession,provisionedCase){mutableStateOf(false)}
    LaunchedEffect(boundaryStore){
        val store=boundaryStore
        if(store!=null){
            try{mapBoundary=withContext(Dispatchers.IO){store.read()}
                if(mapBoundary.isNotEmpty())boundaryLocalStatus=R.string.intake_boundary_saved_local
            }catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){boundaryLocalStatus=R.string.intake_boundary_storage_error}
            finally{boundaryLocalReady=true}
        }
    }
    LaunchedEffect(submitted,boundaryStore){
        if(submitted&&boundaryStore!=null){
            // Do not carry the previous property's geometry into a new listing.
            try{withContext(Dispatchers.IO){boundaryStore.clear()}}
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){boundaryLocalStatus=R.string.intake_boundary_storage_error}
        }
    }
    var showPrivateReading by rememberSaveable(draftFile){mutableStateOf(false)}
    var editCustomerName by remember(draftFile){mutableStateOf(false)}
    var submissionMessage by remember(draftFile){mutableIntStateOf(0)}
    var submissionKey by remember(draftFile){mutableStateOf(prefs.getString("request_key",null)?:UUID.randomUUID().toString())}
    // A dedicated and clearly labelled send action records consent.
    // No redundant checkbox in addition to the explicit action.
    var step by rememberSaveable(draftFile) {mutableIntStateOf(0)}
    var name by rememberSaveable(draftFile) {mutableStateOf(resubmission?.optString("title") ?: prefs.getString("title","") ?: "")}
    var region by rememberSaveable(draftFile) {mutableStateOf(resubmission?.optString("region") ?: prefs.getString("region","") ?: "")}
    var area by rememberSaveable(draftFile) {mutableStateOf(resubmission?.optString("area") ?: prefs.getString("area","") ?: "")}
    var price by rememberSaveable(draftFile) {mutableStateOf(resubmission?.optString("price") ?: prefs.getString("price","") ?: "")}
    // Legacy drafts could contain an automatically selected "owner".
    // Without a recorded explicit user choice, do not assert legal capacity.
    var capacityConfirmed by rememberSaveable(draftFile) {
        mutableStateOf(prefs.getBoolean("capacity_user_confirmed",false))
    }
    var capacity by rememberSaveable(draftFile) {mutableStateOf(
        if(capacityConfirmed)prefs.getString("capacity","")?.takeIf{saved->
            Capacities.any{it.code==saved}
        } ?: "" else ""
    )}
    // A public listing title can be composed from facts; the seller only
    // types one when they choose to personalize it.
    var editTitle by rememberSaveable(draftFile) {mutableStateOf(name.isNotBlank())}
    val suggestedTitle=SmartListingAssist.titleSuggestion(
        region,area,s(R.string.smart_land_title_prefix),s(R.string.smart_area_unit))
    val listingTitle=if(name.isBlank()) suggestedTitle.orEmpty() else name.trim()
    // Auto-save only the non-sensitive listing fields, never scans, tokens,
    // CIN, dates of birth, credentials or document URIs.
    LaunchedEffect(draftFile,name,region,area,price,capacity,capacityConfirmed,submitted) {
        if(readingForListing)return@LaunchedEffect
        if(submitted){prefs.edit().clear().apply();return@LaunchedEffect}
        kotlinx.coroutines.delay(500)
        prefs.edit()
            .putString("title",name.trim())
            .putString("region",region.trim())
            .putString("area",area)
            .putString("price",price)
            .putString("capacity",if(capacityConfirmed)capacity else "")
            .putBoolean("capacity_user_confirmed",capacityConfirmed)
            .apply()
    }
    // Generate the case request key only when the user submits. Persist the
    // payload fingerprint + opaque key BEFORE the first network request, so an
    // unknown server response followed by app restart cannot create a new case.
    var showInvalid by rememberSaveable(draftFile) {mutableStateOf(false)}
    BackHandler(enabled = !submitted) {
        if (step > 1) { step=OwnerIntakePolicy.previous(step); showInvalid = false } else exitJourney()
    }
    var saveMessage by remember(draftFile) {mutableIntStateOf(0)}
    var pickMessage by remember(draftFile) {mutableIntStateOf(0)}
    val files=remember(draftFile) {mutableStateListOf<ChosenDocument>()}
    var replacingPage by remember(draftFile){mutableStateOf<Int?>(null)}
    // Future listing media is preview-only, never private owner title evidence.
    val localListingPhotos=remember(draftFile){mutableStateListOf<DraftListingPhoto>()}
    val listingPhotos=intakePhotos?:localListingPhotos
    var listingLatitude by rememberSaveable(draftFile){mutableStateOf(resubmission?.optString("latitude")?:prefs.getString("latitude","").orEmpty())}
    var listingLongitude by rememberSaveable(draftFile){mutableStateOf(resubmission?.optString("longitude")?:prefs.getString("longitude","").orEmpty())}
    var listingDescription by rememberSaveable(draftFile){mutableStateOf(resubmission?.optString("description")?:prefs.getString("description","").orEmpty())}
    LaunchedEffect(draftFile,listingLatitude,listingLongitude,listingDescription,submitted){
        if(submitted || readingForListing)return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        prefs.edit().putString("latitude",listingLatitude).putString("longitude",listingLongitude)
            .putString("description",listingDescription).apply()
    }
    // Never persist private OCR values or even the document URI with the draft.
    var familyReadings by remember(draftFile) {mutableStateOf<List<ReadOcrResult>>(emptyList())}
    var familyReadBusy by remember(draftFile) {mutableStateOf(false)}
    var familyReadError by remember(draftFile) {mutableIntStateOf(0)}
    var familyReadEpoch by remember(draftFile) {mutableIntStateOf(0)}
    var parcelEditorSource by remember(draftFile) { mutableStateOf<OwnerParcelEditorSource?>(null) }
    var parcelSaved by remember(draftFile) { mutableStateOf(false) }
    val parcelPreviews = remember(draftFile) { mutableStateMapOf<Pair<Uri, Int>, OwnerPlanDraft>() }
    var familyReadJob by remember {mutableStateOf<Job?>(null)}
    LaunchedEffect(draftFile,files.toList(),ownerSession,authenticatedOwnerSession) {
        parcelEditorSource = null
        parcelSaved = false
        parcelPreviews.clear()
        familyReadEpoch++
        familyReadJob?.cancel()
        familyReadings=emptyList()
        familyReadError=0
        familyReadBusy=false
    }
    val identityFiles=remember(draftFile) {mutableStateListOf<ChosenDocument>()}
    var acceptedFrontKey by remember(draftFile){mutableStateOf<String?>(null)}
    // OCR candidates are memory-only and tied to the accepted private photo.
    var acceptedRead by remember(draftFile){
        mutableStateOf<LocalDocumentOcr.FrontScan?>(null)
    }
    var identityMessage by remember(draftFile) {mutableIntStateOf(0)}
    // Private in-memory picture gets scanned on this step before acceptance.
    var cameraVisible by remember(draftFile){mutableStateOf(false)}
    var captureTarget by remember(draftFile){mutableStateOf(OwnerIntakeKind.Identity)}
    var planFile by remember(draftFile){mutableStateOf<ChosenDocument?>(null)}
    var extraFile by remember(draftFile){mutableStateOf<ChosenDocument?>(null)}
    var galleryIdentity by remember(draftFile){mutableStateOf<ChosenDocument?>(null)}
    var localIntakeError by remember(draftFile){mutableIntStateOf(0)}
    var planPage by remember(draftFile){mutableIntStateOf(1)}
    fun openCamera(target:OwnerIntakeKind){captureTarget=target;cameraVisible=true}
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager=androidx.compose.ui.platform.LocalFocusManager.current

    var pendingPhoto by remember(draftFile){mutableStateOf<ChosenDocument?>(null)}
    var frontRead by remember(draftFile){
        mutableStateOf<LocalDocumentOcr.FrontScan?>(null)
    }
    var frontReadBusy by remember(draftFile){mutableStateOf(false)}
    var frontReadRetry by remember(draftFile){mutableIntStateOf(0)}
    suspend fun ensureEvidenceIntakeCase():OwnerEvidenceSession {
        ownerSession?.let{return it}
        provisionedCase?.let{return it}
        val auth=authenticatedOwnerSession?:throw OwnerPairException(401)
        val previous=prefs.getString("pending_evidence_intake_key",null)
        val key=if(previous!=null&&Regex("^[0-9a-fA-F-]{36}$").matches(previous))
            previous else UUID.randomUUID().toString()
        if(!prefs.edit().putString("pending_evidence_intake_key",key).commit())
            throw IllegalStateException("EVIDENCE_INTENT_NOT_DURABLE")
        return provisionOwnerEvidenceIntakeCase(auth,key).also{provisionedCase=it}
    }
    LaunchedEffect(draftFile,pendingPhoto?.uploadKey,
        ownerSession,authenticatedOwnerSession,pilotOcrSession,canReadPrivateId,frontReadRetry){
        val pending=pendingPhoto
        frontRead=null;frontReadBusy=false
        if(pending!=null){
            if(!canReadPrivateId){
                frontRead=LocalDocumentOcr.FrontScan(null,null,null,null,null,0,
                    LocalDocumentOcr.FrontIssue.SESSION_REQUIRED)
            }else{
                // Camera's explicit "capture and read" action is informed
                // consent to one transient preview; no persistent evidence
                // upload or verification is implied.
                frontReadBusy=true
                try{
                    if(hasPilotReviewSession&&pilotOcrSession!=null){
                        val result=LocalDocumentOcr.inspectPilotFront(
                            context,pending.uri,pending.length,pending.mime,pilotOcrSession)
                        if(pendingPhoto?.uploadKey==pending.uploadKey)frontRead=result
                    }else{
                    val activeCase=ensureEvidenceIntakeCase()
                    if(activeCase!=null){
                        val result=LocalDocumentOcr.inspectFront(
                            context,pending.uri,pending.length,pending.mime,activeCase)
                        if(pendingPhoto?.uploadKey==pending.uploadKey)frontRead=result
                    }
                    }
                }catch(cancel:CancellationException){throw cancel}
                catch (_:Exception){
                    if(pendingPhoto?.uploadKey==pending.uploadKey) {
                        frontRead=LocalDocumentOcr.FrontScan(null,null,null,null,null,0,
                            LocalDocumentOcr.FrontIssue.SERVICE_UNAVAILABLE)
                    }
                }finally{
                    if(pendingPhoto?.uploadKey==pending.uploadKey)frontReadBusy=false
                }
            }
        }
    }
    // Never let a stale comparison be displayed after selecting other documents,
    // navigation, account or case changes. Private report is memory-only.
    var observedSelection by remember(draftFile){mutableStateOf<List<String>?>(null)}
    LaunchedEffect(draftFile,files.toList(),identityFiles.toList(),ownerSession,authenticatedOwnerSession) {
        val selection=files.map{it.uploadKey}+identityFiles.map{it.uploadKey}
        if(observedSelection==null){observedSelection=selection;return@LaunchedEffect}
        observedSelection=selection
        requestEpoch++
        requestJob?.cancel()
        provisionedCase=null
        pairReport=null
        identityDateBusy=false;identityDateError=0
        restoredReview=false
        showAnalysisDetails=false
        prefs.edit().remove("review_id").remove("request_key").remove("review_scope").apply()
        submitted=false
        sending=false
        submissionMessage=0
        submissionKey=UUID.randomUUID().toString()
        pairProgress=null
        pairError=0
    }
    DisposableEffect(Unit) {
        onDispose {
            requestEpoch++
            requestJob?.cancel()
            familyReadEpoch++
            familyReadJob?.cancel()
            pendingPhoto?.let{PrivateCaptureMemory.discard(it.uri)}
            identityFiles.forEach{PrivateCaptureMemory.discard(it.uri)}
            files.forEach{PrivateCaptureMemory.discard(it.uri)}
            planFile?.let{PrivateCaptureMemory.discard(it.uri)}
            extraFile?.let{PrivateCaptureMemory.discard(it.uri)}
        }
    }

    // Select one certificate page at a time. Android OpenMultipleDocuments
    // does not promise page order and could silently reverse evidence pages.
    val pickDocuments=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
            var refused=false
            val replacement=replacingPage
            replacingPage=null
            if(files.withIndex().any{it.index!=replacement&&it.value.uri==uri}||
                (replacement==null&&files.size>=8))refused=true
            else try {
                var label=""
                var length=0L
                context.contentResolver.query(
                    uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null
                )?.use {cursor ->
                    if(cursor.moveToFirst()) {
                        val ni=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si=cursor.getColumnIndex(OpenableColumns.SIZE)
                        if(ni>=0&&!cursor.isNull(ni))label=cursor.getString(ni).take(90)
                        if(si>=0&&!cursor.isNull(si))length=cursor.getLong(si)
                    }
                }
                val mime=context.contentResolver.getType(uri)
                if(length !in 20..MAX_DOC_BYTES || mime==null ||
                   mime !in setOf("application/pdf","image/png","image/jpeg","image/tiff") ||
                   (mime=="application/pdf"&&files.withIndex().any{it.index!=replacement}) ||
                   (mime!="application/pdf"&&files.withIndex().any{it.index!=replacement&&it.value.mime=="application/pdf"})) {
                    refused=true
                } else {
                    val page=ChosenDocument(uri,label.ifBlank{uri.lastPathSegment?.take(50)?:"Document"},length,mime)
                    if(replacement!=null&&replacement in files.indices){
                        PrivateCaptureMemory.discard(files[replacement].uri)
                        files[replacement]=page
                    }else files.add(page)
                }
            }catch(_:Exception){refused=true}
            pickMessage=if(refused)R.string.file_rejected
                else R.string.file_selected
        }
    }

    var localPickerTarget by remember(draftFile){mutableStateOf(OwnerIntakeKind.Plan)}
    val localPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            val target=localPickerTarget
            val accepted=runCatching{
                val mime=context.contentResolver.getType(uri)
                require(uri.scheme=="content" && mime!=null && mime in
                    (if(target==OwnerIntakeKind.Identity) setOf("image/jpeg","image/png") else
                    setOf("application/pdf","image/jpeg","image/png","image/tiff")))
                var size=0L;var label=""
                context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{c->
                    if(c.moveToFirst()){
                        val ni=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);val si=c.getColumnIndex(OpenableColumns.SIZE)
                        if(ni>=0&&!c.isNull(ni))label=c.getString(ni).take(90)
                        if(si>=0&&!c.isNull(si))size=c.getLong(si)
                    }
                }
                require(size in 20..(if(hasPilotReviewSession&&target!=OwnerIntakeKind.Identity)PILOT_PHOTO_MAX_BYTES else MAX_DOC_BYTES))
                ChosenDocument(uri,label.ifBlank{"Document"},size,checkNotNull(mime))
            }.getOrNull()
            localIntakeError=if(accepted==null)R.string.file_rejected else 0
            if(accepted!=null)when(target){
                OwnerIntakeKind.Identity -> galleryIdentity=accepted
                OwnerIntakeKind.Plan -> {planFile?.let{PrivateCaptureMemory.discard(it.uri)};planFile=accepted;planPage=1}
                OwnerIntakeKind.Extra -> {extraFile?.let{PrivateCaptureMemory.discard(it.uri)};extraFile=accepted}
                OwnerIntakeKind.Certificate -> Unit
            }
        }
    }
    fun pickLocal(target:OwnerIntakeKind){
        localPickerTarget=target
        localPicker.launch(if(target==OwnerIntakeKind.Identity)arrayOf("image/jpeg","image/png")
            else arrayOf("application/pdf","image/jpeg","image/png","image/tiff"))
    }

    if(sending){
        AlertDialog(onDismissRequest={},confirmButton={},
            title={Text(s(R.string.submission_sending))},
            text={LinearProgressIndicator(Modifier.fillMaxWidth())})
    }
    // Only a freshly captured private in-memory front JPEG is admissible.
    val identitySelected=(restoredReview&&pairReport?.reviewId!=null)|| (identityFiles.size==1&&
        acceptedRead?.readyForCameraAcceptance==true&&
        acceptedFrontKey==identityFiles[0].uploadKey&&
        identityFiles[0].mime=="image/jpeg"&&
        PrivateCaptureMemory.isPrivate(identityFiles[0].uri)&&
        PrivateCaptureMemory.length(identityFiles[0].uri)==identityFiles[0].length)
    // Front-only secure comparison requires explicit consent and genuine login.
    val identityReadyForRemote=identitySelected
    // A pilot OCR session is not a verified owner credential. Optional
    // evidence may be attached only to an actual existing owner case.
    val supplementCase=if(!hasPilotReviewSession && pairReport!=null)
        ownerSession ?: provisionedCase else null
    val pilotAttachmentReview=pairReport?.reviewId.takeIf{hasPilotReviewSession}
    val remoteAttachments=remember(draftFile,pilotAttachmentReview){mutableStateMapOf<String,PilotListingClient.Attachment>()}
    val uploadedAttachmentKeys=remember(draftFile,pilotAttachmentReview){mutableStateMapOf<String,String>()}
    var attachmentReady by remember(draftFile,pilotAttachmentReview){mutableStateOf(false)}
    var attachmentLoading by remember(draftFile,pilotAttachmentReview){mutableStateOf(false)}
    var attachmentSaving by remember(draftFile,pilotAttachmentReview){mutableStateOf(false)}
    var attachmentError by remember(draftFile,pilotAttachmentReview){mutableStateOf(false)}
    var attachmentRetry by remember(draftFile,pilotAttachmentReview){mutableIntStateOf(0)}
    val attachmentPending=(planFile?.uploadKey!=uploadedAttachmentKeys["CADASTRAL_PLAN"])||
        (extraFile?.uploadKey!=uploadedAttachmentKeys["OTHER_LEGAL_PROOF"])||
        (planFile==null&&remoteAttachments.containsKey("CADASTRAL_PLAN"))||
        (extraFile==null&&remoteAttachments.containsKey("OTHER_LEGAL_PROOF"))
    LaunchedEffect(pilotAttachmentReview,pilotOcrSession,attachmentRetry){
        val review=pilotAttachmentReview?:return@LaunchedEffect
        val session=pilotOcrSession?:return@LaunchedEffect
        attachmentLoading=true;attachmentReady=false;attachmentError=false
        try{
            val list=PilotListingClient.attachments(session,review)
            remoteAttachments.clear()
            for(item in list){
                remoteAttachments[item.kind]=item
                val current=if(item.kind=="CADASTRAL_PLAN")planFile else extraFile
                if(current==null){
                    val bytes=PilotListingClient.downloadAttachment(session,review,item)
                    val uri=try{PrivateCaptureMemory.addDocument(bytes,item.mime)}
                        catch(e:Exception){bytes.fill(0);throw e}
                    val doc=ChosenDocument(uri,s(if(item.kind=="CADASTRAL_PLAN")R.string.intake_plan else R.string.intake_extra),
                        bytes.size.toLong(),item.mime)
                    if(item.kind=="CADASTRAL_PLAN")planFile=doc else extraFile=doc
                    uploadedAttachmentKeys[item.kind]=doc.uploadKey
                }
            }
            attachmentReady=true
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){attachmentError=true}
        finally{attachmentLoading=false}
    }
    suspend fun syncPilotAttachments(){
        val review=pilotAttachmentReview?:return
        val session=pilotOcrSession?:return
        if(!attachmentPending)return
        if(!attachmentReady)throw OwnerPairException(503)
        for((kind,file) in listOf("CADASTRAL_PLAN" to planFile,"OTHER_LEGAL_PROOF" to extraFile)){
            val saved=remoteAttachments[kind]
            if(file==null){
                if(saved!=null)PilotListingClient.removeAttachment(session,review,saved)
                remoteAttachments.remove(kind);uploadedAttachmentKeys.remove(kind)
            }else if(uploadedAttachmentKeys[kind]!=file.uploadKey){
                val item=PilotListingClient.uploadAttachment(context,session,review,kind,file.uri,file.length,file.mime,saved)
                remoteAttachments[kind]=item;uploadedAttachmentKeys[kind]=file.uploadKey
            }
        }
    }
    var planRequest by remember(supplementCase,planFile?.uploadKey){mutableIntStateOf(0)}
    var planReading by remember(supplementCase,planFile?.uploadKey){mutableStateOf<ReadOcrResult?>(null)}
    var planBusy by remember(supplementCase,planFile?.uploadKey){mutableStateOf(false)}
    var planError by remember(supplementCase,planFile?.uploadKey){mutableStateOf(false)}
    var extraRequest by remember(supplementCase,extraFile?.uploadKey){mutableIntStateOf(0)}
    var extraReceipt by remember(supplementCase,extraFile?.uploadKey){mutableStateOf<String?>(null)}
    var extraBusy by remember(supplementCase,extraFile?.uploadKey){mutableStateOf(false)}
    var extraError by remember(supplementCase,extraFile?.uploadKey){mutableStateOf(false)}
    LaunchedEffect(supplementCase,planFile?.uploadKey,planRequest){
        val active=supplementCase ?: return@LaunchedEffect
        val file=planFile ?: return@LaunchedEffect
        if(planRequest==0)return@LaunchedEffect
        planBusy=true;planError=false
        try{
            planReading=readOcrForOwner(context,file.uri,file.length,file.mime,
                if(file.mime=="application/pdf")null else 1,file.uploadKey,"",
                QaOcrSession(active.httpsBaseUrl,active.caseId,active.bearer),"CADASTRAL_PLAN")
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){planError=true}
        finally{planBusy=false}
    }
    LaunchedEffect(supplementCase,extraFile?.uploadKey,extraRequest){
        val active=supplementCase ?: return@LaunchedEffect
        val file=extraFile ?: return@LaunchedEffect
        if(extraRequest==0)return@LaunchedEffect
        extraBusy=true;extraError=false
        try{
            extraReceipt=attachOwnerSupplement(context,active,
                PairEvidenceFile(file.uri,file.length,file.mime,file.uploadKey,"OTHER_LEGAL_PROOF"))
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){extraError=true}
        finally{extraBusy=false}
    }
    // One private matcher for the seven document classes. Mixed document
    // families must be reviewed separately; UNKNOWN continuation pages may
    // accompany ONE recognized document without inventing its type.
    val knownDocumentKinds=familyReadings.map{it.familyKind}
        .filter{it!="UNKNOWN"}.distinct()
    val canRunUnifiedPair=files.isNotEmpty()&&
        familyReadings.size==files.size &&
        familyReadings.all{it.status=="READY"}&&
        knownDocumentKinds.size==1
    // Tunisian/French comma is normalized only for numeric validation and API.
    val normalizedArea=area.replace(',', '.')
    val validArea=normalizedArea.matches(ValidLandArea)
    val validPrice=price.matches(ValidLandPrice)
    val missingListingFields=pilotMissingListingFields(listingTitle,region,area,price)+
        if(hasPilotReviewSession&&customerName.trim().length<2)listOf("customer") else emptyList()
    val validFirst=missingListingFields.isEmpty()
    // Saving a private local draft must work BEFORE document selection or OCR.
    // Do not pretend success when the device fails to persist these fields.
    val saveLocalDraft:()->Unit={
        saveMessage=try {
            val committed=prefs.edit()
                .putString("title",name.trim())
                .putString("region",region.trim())
                .putString("area",area)
                .putString("price",price)
                .putString("capacity",if(capacityConfirmed)capacity else "")
                .putBoolean("capacity_user_confirmed",capacityConfirmed)
                .commit()
            if(committed)R.string.draft_saved else R.string.draft_error
        }catch (_:Exception){R.string.draft_error}
    }
    val stepLabels=listOf(R.string.smart_step_documents,
        if(directVerification || readingForListing)R.string.direct_analysis else R.string.step_property,R.string.step_summary
    ).take(if (readingForListing) 2 else 3)
    val displayStep=OwnerIntakePolicy.displayStep(step)
    val stepScroll=rememberScrollState()
    val missingFieldsTarget=remember{BringIntoViewRequester()}
    val certificateTarget = remember { BringIntoViewRequester() }
    val intakeStep=step<=1
    val needsCertificate = intakeStep && files.isEmpty() && !restoredReview
    val certReady=files.isNotEmpty()||restoredReview
    val requiredCount=(if(certReady)1 else 0)+(if(identitySelected)1 else 0)
    var intakeDetails by remember(draftFile){mutableStateOf<OwnerIntakeKind?>(null)}
    LaunchedEffect(step) { if(step>3) step=3;stepScroll.scrollTo(0) }

    fun startPairComparison(){
                            if(pairProgress!=null||pairReport!=null||files.isEmpty()||!identityReadyForRemote||
                                !canRunPrivateMatching||familyReadBusy)
                                return
                            val selected=files.toList()
                            val idDocs=identityFiles.toList().map{
                                PairEvidenceFile(it.uri,it.length,it.mime,it.uploadKey)
                            }
                            pairError=0
                            pairReport=null
                                                                    pairProgress=PairProgress.CASE_PROVISION
                            val thisRequest=++requestEpoch
                            requestJob=scope.launch {
                                try {
                                    // The current Malak login directly scans ONLY these
                                    // newly submitted files. No owner case or extra sign-in.
                                    if(hasPilotReviewSession&&pilotOcrSession!=null){
                                        val pilotResult=comparePilotDocumentsAndIdentity(
                                            context,pilotOcrSession,
                                            selected.map{item->PairEvidenceFile(
                                                item.uri,item.length,item.mime,item.uploadKey,
                                                "OTHER_LEGAL_PROOF")},
                                            idDocs){progress->
                                                if(requestEpoch==thisRequest)
                                                    pairProgress=progress
                                            }
                                        if(requestEpoch==thisRequest&&
                                            files.map{it.uploadKey}==selected.map{it.uploadKey}&&
                                            identityFiles.map{it.uploadKey}==
                                                idDocs.map{it.idempotencyKey}){
                                            pairReport=pilotResult
                                            submissionKey=UUID.randomUUID().toString()
                                            prefs.edit().putString("review_id",pilotResult.reviewId)
                                                .putString("review_scope",recoveryScope)
                                                .putString("request_key",submissionKey).commit()
                                            if(customerName.isBlank())customerName=acceptedRead?.fullName.orEmpty()
                                            val suggestions=pilotAutofill(pilotResult.detectedKind,pilotResult.sourceFacts)
                                            if(area.isBlank())area=suggestions["area"].orEmpty()
                                            if(price.isBlank())price=suggestions["price"].orEmpty()
                                        }
                                        return@launch
                                    }
                                    // A separately provisioned owner case is needed only
                                    // for pre-existing case-based, legally reviewed paths.
                                    val activeCase=ensureEvidenceIntakeCase()
                                    val readings=if(familyReadings.size==selected.size&&
                                        familyReadings.all{it.status=="READY"})familyReadings
                                    else selected.mapIndexed{index,item->
                                        readOcrForOwner(context,item.uri,item.length,item.mime,
                                            if(item.mime=="application/pdf")null else index+1,
                                            item.uploadKey+"-ocr",normalizedArea,
                                            QaOcrSession(activeCase.httpsBaseUrl,
                                                activeCase.caseId,activeCase.bearer))
                                    }
                                    if(requestEpoch!=thisRequest)return@launch
                                    val recognized=readings.map{it.familyKind}
                                        .filter{it!="UNKNOWN"}.distinct()
                                    if(recognized.size!=1||readings.any{it.status!="READY"}){
                                        pairError=R.string.unified_review_separately
                                        return@launch
                                    }
                                    familyReadings=readings
                                    val cert=selected.mapIndexed{index,item->
                                        val kind=readings[index].familyKind
                                        PairEvidenceFile(item.uri,item.length,item.mime,item.uploadKey,
                                            if(kind=="UNKNOWN")"OTHER_LEGAL_PROOF" else kind)
                                    }
                                    val result=compareOwnerDocumentsAndIdentity(
                                        context,activeCase,cert,idDocs){
                                        if(requestEpoch==thisRequest)pairProgress=it
                                    }
                                    if(requestEpoch==thisRequest &&
                                       files.map{it.uploadKey}==cert.map{it.idempotencyKey} &&
                                       identityFiles.map{it.uploadKey}==idDocs.map{it.idempotencyKey}) {
                                        pairReport=result
                                    }
                                }catch(cancel:CancellationException) {
                                    throw cancel
                                }catch(err:OwnerPairException) {
                                    // Never show tokens, provider URLs or internal service errors.
                                    if(requestEpoch==thisRequest)pairError=when(err.statusCode) {
                                        401->R.string.pair_session_invalid
                                        403->R.string.pair_case_forbidden
                                        413,415,422->R.string.pair_document_rejected
                                        429->R.string.pair_rate_limited
                                        502,503,504->R.string.pair_service_unavailable
                                        else->R.string.pair_request_failed
                                    }
                                }catch(_:Exception){
                                    if(requestEpoch==thisRequest)
                                        pairError=R.string.pair_request_failed
                                }finally{
                                    if(requestEpoch==thisRequest)pairProgress=null
                                }
                            }

    }
    fun refreshIdentityDate(){
        val review=pairReport?.reviewId?:return
        val session=pilotOcrSession?:return
        if(identityDateBusy)return
        identityDateBusy=true;identityDateError=0
        val epoch=++requestEpoch
        requestJob=scope.launch{
            try{
                val refined=parsePilotComparison(PilotListingClient.call(session,
                    "/api/pilot/reviews/"+review+"/identity-date",org.json.JSONObject(),privateReadConsent=true))
                if(requestEpoch==epoch&&pairReport?.reviewId==review){
                    pairReport=refined;acceptedRead=refined.identityScan
                    if(refined.identityScan?.birthDate==null)identityDateError=R.string.owner_date_not_updated
                }
            }catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){if(requestEpoch==epoch)identityDateError=R.string.owner_date_not_updated}
            finally{if(requestEpoch==epoch)identityDateBusy=false}
        }
    }
    LaunchedEffect(draftFile,restoreAttempt){
        if(savedReview==null||pilotOcrSession==null)return@LaunchedEffect
        restoring=true;restoreFailed=false
        try{
            val response=PilotListingClient.call(pilotOcrSession,"/api/pilot/reviews/"+savedReview)
            val report=parsePilotComparison(response)
            val receipt=response.optJSONObject("submission")
            if(receipt!=null){
                val reference=pilotSubmissionReference(receipt.optString("id"),receipt.optString("status"))?:throw OwnerPairException(502)
                submittedReference=reference;submittedId=receipt.optString("id")
                submittedStatus=receipt.optString("status");submitted=true
            }else{
                pairReport=report;acceptedRead=report.identityScan;restoredReview=true
                if(customerName.isBlank())customerName=report.identityScan?.fullName.orEmpty()
                step=2
            }
        }catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){restoreFailed=true}
        finally{restoring=false}
    }
    var autoAnalysisFor by remember(draftFile){mutableStateOf<String?>(null)}
    LaunchedEffect(step,files.map{it.uploadKey},identityFiles.map{it.uploadKey},restoring){
        val selection=(files.map{it.uploadKey}+identityFiles.map{it.uploadKey}).joinToString("|")
        if(step==2&&hasPilotReviewSession&&!restoring&&!restoreFailed&&!restoredReview&&
            identitySelected&&pairReport==null&&pairProgress==null&&autoAnalysisFor!=selection){
            autoAnalysisFor=selection
            startPairComparison()
        }
    }

    if(restoring||restoreFailed){
        Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,
            horizontalAlignment=Alignment.CenterHorizontally){
            Text(s(if(restoring)R.string.owner_restoring else R.string.owner_restore_failed),fontSize=18.sp)
            if(restoring)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=16.dp))
            else{
                Button(onClick={restoreAttempt++}){Text(s(R.string.owner_retry))}
                TextButton(onClick={
                    prefs.edit().remove("review_id").remove("request_key").remove("review_scope").apply()
                    restoreFailed=false;step=0
                }){Text(s(R.string.owner_start_fresh))}
            }
        }
        return
    }
    parcelEditorSource?.let { source ->
        val previewKey = source.uri to source.evidencePage
        OwnerParcelEditor(source, ownerSession ?: provisionedCase, parcelPreviews[previewKey],
            onPreview = { parcelPreviews[previewKey] = it },
            onFinish = { submittedToCase ->
                parcelEditorSource = null
                if (submittedToCase) parcelSaved = true
            })
        return
    }
    if(cameraVisible){
        TunisianIdCamera(onClose={cameraVisible=false},
            onPhoto={uri,length->
                if(cameraVisible){
                    val doc=ChosenDocument(uri,if(captureTarget==OwnerIntakeKind.Identity)"CIN-camera.jpg" else "document.jpg",length,"image/jpeg")
                    when(captureTarget){
                        OwnerIntakeKind.Identity -> {
                            pendingPhoto?.let{PrivateCaptureMemory.discard(it.uri)}
                            pendingPhoto=doc;identityMessage=0
                        }
                        OwnerIntakeKind.Certificate -> {
                            if(files.size<8&&files.none{it.mime=="application/pdf"}){files.add(doc);pickMessage=R.string.file_selected}
                            else {PrivateCaptureMemory.discard(uri);pickMessage=R.string.file_rejected}
                        }
                        OwnerIntakeKind.Plan -> {planFile?.let{PrivateCaptureMemory.discard(it.uri)};planFile=doc;planPage=1}
                        OwnerIntakeKind.Extra -> {extraFile?.let{PrivateCaptureMemory.discard(it.uri)};extraFile=doc}
                    }
                    cameraVisible=false
                }else PrivateCaptureMemory.discard(uri)
            },documentMode=captureTarget!=OwnerIntakeKind.Identity)
        return
    }
    if(submitted&&submittedReference!=null){
        PilotSubmissionComplete(submittedReference!!,listingTitle,{onSubmitted(submittedId!!)},submittedStatus)
        return
    }

    // The reference is bundled; no private document, point or source URL is sent
    // to an external service to resolve administrative context.
    val locationPoint=SmartListingAssist.coordinates(listingLatitude,listingLongitude)?.let{DiscoveryPoint(it.first,it.second)}
    val adminSamples=remember(locationPoint,mapBoundary){(listOfNotNull(locationPoint)+mapBoundary).distinct()}
    var adminIndex by remember{mutableStateOf<AtlasAdminIndex?>(null)}
    var adminLoadFailed by remember{mutableStateOf(false)}
    val docLocations=pairReport?.sourceFacts.orEmpty().filter{it.key in setOf("propertyGovernorate","propertyDelegation","propertyImada")}
    val needsAdmin=adminSamples.isNotEmpty()||docLocations.isNotEmpty()
    LaunchedEffect(needsAdmin){
        if(!needsAdmin||adminIndex!=null)return@LaunchedEffect
        try{adminIndex=withContext(Dispatchers.IO){loadAtlasAdminIndex(context.applicationContext)}}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){adminLoadFailed=true}
    }
    val adminContext by produceState<AdminContext?>(null,adminIndex,adminSamples){
        value=null
        val index=adminIndex
        if(index!=null&&adminSamples.isNotEmpty())value=withContext(Dispatchers.Default){index.resolveSamples(adminSamples)}
    }
    val adminArabic=s(R.string.intake_language_code)=="ar"
    val documentAdmin=remember(adminIndex,docLocations){
        fun fact(key:String)=docLocations.singleOrNull{it.key==key}?.value
        adminIndex?.resolveNames(fact("propertyGovernorate"),fact("propertyDelegation"),fact("propertyImada"))
    }
    val docLocationConflict=documentAdmin!=null&&adminContext!=null&&(
        documentAdmin.governorate.id!=adminContext?.governorate?.id||
        (documentAdmin.delegation!=null&&adminContext?.delegation!=null&&documentAdmin.delegation.id!=adminContext?.delegation?.id)||
        (documentAdmin.imada!=null&&adminContext?.imada!=null&&documentAdmin.imada.id!=adminContext?.imada?.id))
    var lastAutoRegion by remember(draftFile){mutableStateOf<String?>(null)}
    LaunchedEffect(adminContext,documentAdmin,docLocationConflict,adminSamples,adminArabic,docLocations){
        if(docLocationConflict)return@LaunchedEffect
        val candidate=adminContext?:documentAdmin?.takeIf{adminSamples.isEmpty()&&docLocations.all{it.status=="CANDIDATE"}}
        val suggestion=candidate?.region(adminArabic)?:return@LaunchedEffect
        if(!directVerification&&!readingForListing&&(region.isBlank()||region==lastAutoRegion)){
            region=suggestion;lastAutoRegion=suggestion
        }
    }
    val keepMapDrawing:(List<DiscoveryPoint>)->Unit={points->
        mapBoundary=points;chooseMapBoundary=false;georefDraft=null
        if(!directVerification&&!readingForListing&&area.isBlank()){
            measureOwnerMap(points)?.let{area=String.format(Locale.ROOT,"%.2f",it.areaM2)}
        }
        boundaryStore?.let{store->
            boundaryLocalBusy=true
            scope.launch{
                try{
                    withContext(Dispatchers.IO){store.save(points)}
                    boundaryLocalStatus=R.string.intake_boundary_saved_local
                }catch(cancel:CancellationException){throw cancel}
                catch(_:Exception){boundaryLocalStatus=R.string.intake_boundary_storage_error}
                finally{boundaryLocalBusy=false}
            }
        }
    }
    if(chooseMapBoundary){
        OwnerMapBoundaryEditor(locationPoint,mapBoundary,
            onDismiss={chooseMapBoundary=false},onSave=keepMapDrawing)
    }
    georefDraft?.let{draft->
        PlanMapControlEditor(draft,locationPoint?:mapBoundary.firstOrNull(),
            onDismiss={georefDraft=null},onAccept=keepMapDrawing)
    }
    if(chooseListingPoint){
        DirectLocationPicker(
            SmartListingAssist.coordinates(listingLatitude,listingLongitude)?.let{DiscoveryPoint(it.first,it.second)},
            onDismiss={chooseListingPoint=false},
            onSelect={point->
                listingLatitude=String.format(Locale.ROOT,"%.6f",point.latitude)
                listingLongitude=String.format(Locale.ROOT,"%.6f",point.longitude)
                chooseListingPoint=false
            })
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            IconButton(onClick={if(step>1){step=OwnerIntakePolicy.previous(step);showInvalid=false}else exitJourney()}, modifier=Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack,s(R.string.back),tint=Brand.Crimson)
            }
            Column(Modifier.weight(1f)) {
                Text(s(if(readingForListing)R.string.listing_read_documents else if(directVerification)R.string.direct_verify else R.string.intake_property_title), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Brand.Ink)
                Text(s(R.string.intake_step_count,displayStep+1,stepLabels.size),
                    style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            stepLabels.forEachIndexed { i,label ->
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { selected = i == displayStep },
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.fillMaxWidth().height(5.dp).background(
                        if(i<=displayStep)Brand.Crimson else Brand.Border,CircleShape))
                    Text(s(if(i==0)R.string.intake_docs_photos else label),style=MaterialTheme.typography.labelSmall,
                        fontWeight=if(i==displayStep)FontWeight.Bold else FontWeight.Medium,
                        color=if(i==displayStep)Brand.Ink else Brand.Muted, textAlign=TextAlign.Center)
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(stepScroll).padding(horizontal=16.dp,vertical=10.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)) {
            when(step) {
                0,1 -> {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.SpaceBetween){
                        Text(s(R.string.intake_private_heading),fontWeight=FontWeight.Bold,fontSize=17.sp,color=Brand.Ink)
                        Text(s(R.string.intake_required_count,requiredCount),fontSize=12.sp,color=Brand.Muted)
                    }
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        Icon(Icons.Outlined.Lock,null,tint=IntakeGreen,modifier=Modifier.size(16.dp))
                        Text(s(R.string.intake_private_short),fontSize=12.sp,color=Brand.Muted)
                    }
                    val certificateCanAdd=pairProgress==null&&(files.isEmpty()||
                        (files.size<8&&files.none{it.mime=="application/pdf"}))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                        IntakeGridCard(s(R.string.intake_cert_title),Icons.Outlined.Description,true,
                            s(if(certReady)R.string.intake_added else R.string.intake_empty),
                            modifier=Modifier.weight(1f).bringIntoViewRequester(certificateTarget),
                            ready=certReady,uploadEnabled=certificateCanAdd,captureEnabled=certificateCanAdd,
                            onUpload={replacingPage=null;pickDocuments.launch(
                                if(files.isEmpty())arrayOf("application/pdf","image/jpeg","image/png","image/tiff")
                                else arrayOf("image/jpeg","image/png","image/tiff"))},
                            onCapture={openCamera(OwnerIntakeKind.Certificate)},
                            detail=if(files.isNotEmpty())s(R.string.intake_manage_files,files.size) else null,
                            onDetail={intakeDetails=OwnerIntakeKind.Certificate})
                        IntakeGridCard(s(R.string.identity_section),Icons.Outlined.Badge,true,
                            s(when{identitySelected->R.string.intake_added
                                pendingPhoto!=null->R.string.intake_review_photo
                                galleryIdentity!=null->R.string.intake_preview_state
                                else->R.string.intake_empty}),
                            modifier=Modifier.weight(1f),ready=identitySelected,
                            warning=!identitySelected&&(pendingPhoto!=null||galleryIdentity!=null),
                            uploadEnabled=pendingPhoto==null,captureEnabled=pendingPhoto==null,
                            onUpload={pickLocal(OwnerIntakeKind.Identity)},
                            onCapture={intakeDetails=OwnerIntakeKind.Identity},
                            detail=if(galleryIdentity!=null)s(R.string.intake_view_file) else null,
                            onDetail={intakeDetails=OwnerIntakeKind.Identity})
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                        IntakeGridCard(s(R.string.intake_plan),Icons.Outlined.Map,false,
                            s(if(planFile!=null)R.string.intake_added else R.string.intake_empty),
                            modifier=Modifier.weight(1f),ready=planFile!=null,
                            iconTint=Color(0xFF466C56),iconBackground=Color(0xFFECF3EE),
                            onUpload={pickLocal(OwnerIntakeKind.Plan)},onCapture={openCamera(OwnerIntakeKind.Plan)},
                            detail=planFile?.let{s(R.string.intake_view_file)},
                            onDetail={intakeDetails=OwnerIntakeKind.Plan})
                        IntakeGridCard(s(R.string.intake_extra),Icons.Outlined.AttachFile,false,
                            s(if(extraFile!=null)R.string.intake_added else R.string.intake_empty),
                            modifier=Modifier.weight(1f),ready=extraFile!=null,
                            iconTint=Color(0xFF636879),iconBackground=Color(0xFFF0F0F4),
                            onUpload={pickLocal(OwnerIntakeKind.Extra)},onCapture={openCamera(OwnerIntakeKind.Extra)},
                            detail=extraFile?.let{s(R.string.intake_view_file)},
                            onDetail={intakeDetails=OwnerIntakeKind.Extra})
                    }
                    if(pickMessage!=0&&pickMessage!=R.string.file_selected)
                        Text(s(pickMessage),color=Brand.Muted,fontSize=12.sp,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                    if(identityMessage!=0)Text(s(identityMessage),color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                    if(localIntakeError!=0)Text(s(localIntakeError),color=MaterialTheme.colorScheme.error,fontSize=12.sp)
                    if(pendingPhoto!=null){
                        val pending=pendingPhoto!!
                        Surface(color=Color.White,shape=RoundedCornerShape(20.dp),
                            border=BorderStroke(1.dp,CardBorder)){
                            Column(Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement=Arrangement.spacedBy(12.dp)){
                                Text(s(R.string.camera_review_title),
                                    color=Brand.Ink,fontSize=18.sp,
                                    fontWeight=FontWeight.SemiBold)
                                val preview=remember(pending.uri){
                                    runCatching{
                                        val opts=BitmapFactory.Options().apply{inSampleSize=4}
                                        context.contentResolver.openInputStream(pending.uri)?.use{
                                            BitmapFactory.decodeStream(it,null,opts)
                                        }
                                    }.getOrNull()
                                }
                                if(preview!=null)
                                    Image(preview.asImageBitmap(),null,
                                        modifier=Modifier.fillMaxWidth().heightIn(max=255.dp)
                                            .height(235.dp),
                                        contentScale=ContentScale.Fit)
                                if(frontReadBusy)
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                val scan=frontRead?.takeIf{it.issue==null}
                                val waiting=frontReadBusy||frontRead==null
                                val placeholder=s(if(waiting)
                                    R.string.camera_field_pending else R.string.camera_field_missing)
                                // One compact readout, never five oversized
                                // duplicate "unreadable" failure cards.
                                Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
                                    IdFieldsReadout(scan,placeholder)
                                    if(scan!=null)Text(s(R.string.camera_fields_progress,
                                        scan.extractedFieldCount,4),color=Brand.Muted,fontSize=12.sp)
                                }
                                val message=when{
                                    frontReadBusy->R.string.camera_review_scanning
                                    !canReadPrivateId->
                                        R.string.camera_huawei_login_required
                                    frontRead?.issue==
                                        LocalDocumentOcr.FrontIssue.SERVICE_UNAVAILABLE->
                                        R.string.camera_huawei_unavailable
                                    frontRead?.issue==
                                        LocalDocumentOcr.FrontIssue.LOW_RESOLUTION->
                                        R.string.camera_image_low_resolution
                                    frontRead?.issue!=null->
                                        R.string.camera_review_failed
                                    scan?.readyForCameraAcceptance==true->
                                        when {
                                            scan.extractedFieldCount==0->R.string.camera_review_empty
                                            scan.cinFull!=null&&scan.birthDate!=null&&scan.fullName!=null->
                                                R.string.camera_review_ready
                                            else->R.string.camera_review_manual
                                        }
                                    else->R.string.camera_review_required_fields
                                }
                                Text(s(message),color=Brand.Muted,
                                    fontSize=12.sp,lineHeight=19.sp)
                                if(frontRead?.issue==LocalDocumentOcr.FrontIssue.SERVICE_UNAVAILABLE&&!frontReadBusy){
                                    OutlinedButton(onClick={frontReadRetry++}){Text(s(R.string.owner_retry))}
                                }
                            }
                        }
                    }

                    if(!directVerification)ListingPhotoEditor(listingPhotos,directPublishing=intakePhotos!=null,
                        persistAccess=intakePhotos!=null,compact=true)
                    if(directVerification)Text(s(if(resubmission?.optString("kind")=="LAND")R.string.direct_land_documents else R.string.direct_home_documents),fontSize=12.sp,color=Brand.Muted)
                    if(planFile!=null||extraFile!=null)Text(s(if(hasOwnerReviewSession&&!hasPilotReviewSession)
                        R.string.intake_supplement_owner_hint else if(hasPilotReviewSession)R.string.intake_pilot_attachments_hint else R.string.intake_optional_preview_only),fontSize=12.sp,color=Brand.Muted)
                    intakeDetails?.let{kind->
                        AlertDialog(onDismissRequest={intakeDetails=null},
                            title={Text(s(when(kind){
                                OwnerIntakeKind.Certificate->R.string.intake_cert_title
                                OwnerIntakeKind.Identity->R.string.identity_section
                                OwnerIntakeKind.Plan->R.string.intake_plan
                                OwnerIntakeKind.Extra->R.string.intake_extra
                            }))},
                            text={
                                Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState()),
                                    verticalArrangement=Arrangement.spacedBy(8.dp)){
                                    when(kind){
                                        OwnerIntakeKind.Certificate->{
                                            Text(s(R.string.file_rules),fontSize=12.sp)
                                            Text(s(R.string.certificate_page_order_hint),fontSize=12.sp)
                                            files.toList().forEachIndexed{index,file->
                                                Text("${index+1}. ${file.displayName}",fontWeight=FontWeight.Medium)
                                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                                                    IconButton(onClick={
                                                        PrivateCaptureMemory.discard(file.uri)
                                                        val kept=removeDocumentPage(files.toList(),index)
                                                        files.clear();files.addAll(kept);pickMessage=R.string.owner_page_removed
                                                    },enabled=pairProgress==null){Icon(Icons.Outlined.DeleteOutline,s(R.string.remove))}
                                                    IconButton(onClick={
                                                        intakeDetails=null;replacingPage=index
                                                        pickDocuments.launch(arrayOf("application/pdf","image/jpeg","image/png","image/tiff"))
                                                    },enabled=pairProgress==null){Icon(Icons.Outlined.Edit,s(R.string.owner_replace_page))}
                                                    if(files.size>1){
                                                        IconButton(onClick={val ordered=moveDocumentPage(files.toList(),index,index-1);files.clear();files.addAll(ordered)},enabled=index>0&&pairProgress==null){Icon(Icons.Outlined.KeyboardArrowUp,s(R.string.owner_move_page_up))}
                                                        IconButton(onClick={val ordered=moveDocumentPage(files.toList(),index,index+1);files.clear();files.addAll(ordered)},enabled=index<files.lastIndex&&pairProgress==null){Icon(Icons.Outlined.KeyboardArrowDown,s(R.string.owner_move_page_down))}
                                                    }
                                                }
                                                HorizontalDivider()
                                            }
                                        }
                                        OwnerIntakeKind.Identity->{
                                            Text(s(R.string.camera_capture_disclosure),color=Brand.Muted)
                                            galleryIdentity?.let{file->
                                                Text(file.displayName,fontSize=12.sp)
                                                IntakeImagePreview(file.uri)
                                                Text(s(R.string.intake_identity_preview_only),fontSize=12.sp,color=Brand.Muted)
                                                TextButton(onClick={galleryIdentity=null}){Text(s(R.string.remove))}
                                            }
                                            if(identitySelected)IdFieldsReadout(acceptedRead,s(R.string.camera_field_missing))
                                            Button(onClick={intakeDetails=null;openCamera(OwnerIntakeKind.Identity)},modifier=Modifier.fillMaxWidth()){
                                                Icon(Icons.Outlined.CameraAlt,null);Spacer(Modifier.width(6.dp));Text(s(R.string.intake_take_photo))
                                            }
                                        }
                                        OwnerIntakeKind.Plan->planFile?.let{file->
                                            Text(file.displayName)
                                            if(file.mime=="application/pdf")Row(verticalAlignment=Alignment.CenterVertically){
                                                TextButton(onClick={planPage=(planPage-1).coerceAtLeast(1)},enabled=planPage>1){Text("−")}
                                                Text(s(R.string.intake_plan_page,planPage))
                                                TextButton(onClick={planPage=(planPage+1).coerceAtMost(8)},enabled=planPage<8){Text("+")}
                                            }
                                            Button(onClick={
                                                intakeDetails=null
                                                parcelEditorSource=OwnerParcelEditorSource(file.uri,file.mime,planPage,planPage,null,null,emptyList(),"","",null)
                                            },modifier=Modifier.fillMaxWidth()){Text(s(R.string.intake_open_plan))}
                                            TextButton(onClick={PrivateCaptureMemory.discard(file.uri);planFile=null;planPage=1;intakeDetails=null}){Text(s(R.string.remove))}
                                        }
                                        OwnerIntakeKind.Extra->extraFile?.let{file->
                                            Text(file.displayName)
                                            if(file.mime=="image/jpeg"||file.mime=="image/png")IntakeImagePreview(file.uri)
                                            TextButton(onClick={PrivateCaptureMemory.discard(file.uri);extraFile=null;intakeDetails=null}){Text(s(R.string.remove))}
                                        }
                                    }
                                }
                            },confirmButton={TextButton(onClick={intakeDetails=null}){Text(s(R.string.close))}})
                    }
                }
                2 -> {
                    JourneySectionHeading(s(R.string.intake_smart_editor),Icons.Outlined.Map)
                    JourneyPanel{
                        val file=planFile
                        val draft=file?.let{parcelPreviews[it.uri to planPage]}
                        val planFacts=planReading
                        val measured=measureOwnerMap(mapBoundary)
                        OutlinedButton(onClick={chooseListingPoint=true},modifier=Modifier.fillMaxWidth()){
                            Icon(Icons.Outlined.LocationOn,null,Modifier.size(19.dp));Spacer(Modifier.width(6.dp))
                            Text(s(if(locationPoint==null)R.string.direct_choose_location else R.string.direct_change_location))
                        }
                        if(locationPoint!=null)Text(s(R.string.intake_pin_selected),fontSize=12.sp,color=IntakeGreen)
                        val previewCenter=mapBoundary.firstOrNull()?:locationPoint
                        if(previewCenter!=null){
                            key(previewCenter,mapBoundary){
                                var previewFailed by remember{mutableStateOf(false)}
                                if(!previewFailed)DiscoveryMap(styleUrl=DiscoveryStyleUrl,
                                    initial=DiscoveryCamera(previewCenter,17.0,InitialBounds),
                                    jump=null,markers=DiscoveryMarkers(emptyList()),selectedId=null,priceLabel={""},
                                    modifier=Modifier.fillMaxWidth().height(210.dp),
                                    draftBoundary=mapBoundary,onCameraIdle={_,_->},
                                    onListing={},onCluster={},onReady={},onFailure={previewFailed=true})
                                else Text(s(R.string.v2_tiles_error),fontSize=12.sp,color=Brand.Muted)
                            }
                        }
                        if(file!=null){
                            Text(s(R.string.intake_plan),fontWeight=FontWeight.SemiBold,color=Brand.Ink)
                            Text(file.displayName,fontSize=12.sp,color=Brand.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                            if(file.mime=="application/pdf")Row(verticalAlignment=Alignment.CenterVertically){
                                TextButton(onClick={planPage=(planPage-1).coerceAtLeast(1)},enabled=planPage>1){Text("−")}
                                Text(s(R.string.intake_plan_page,planPage))
                                TextButton(onClick={planPage=(planPage+1).coerceAtMost(8)},enabled=planPage<8){Text("+")}
                            }
                            OutlinedButton(onClick={
                                parcelEditorSource=OwnerParcelEditorSource(file.uri,file.mime,planPage,planPage,
                                    planFacts?.evidenceId,planFacts?.parcelScans?.firstOrNull{it.page==planPage},
                                    planFacts?.parcelOcrLengths?.filter{it.page==planPage}.orEmpty(),
                                    planFacts?.parcelPlanFacts?.selectedFact("titleReference",planPage).orEmpty(),
                                    planFacts?.parcelPlanFacts?.selectedFact("plotReference",planPage).orEmpty(),
                                    planFacts?.parcelPlanFacts?.selectedFact("printedAreaM2",planPage))
                            },modifier=Modifier.fillMaxWidth()){
                                Icon(Icons.Outlined.Edit,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp))
                                Text(s(R.string.intake_open_plan))
                            }
                            if(draft!=null){
                                if(planControlIndices(draft.vertices).size==4)OutlinedButton(onClick={georefDraft=draft},
                                    enabled=boundaryLocalReady&&!boundaryLocalBusy&&!boundarySendBusy,modifier=Modifier.fillMaxWidth()){
                                    Icon(Icons.Outlined.Map,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp))
                                    Text(s(R.string.intake_georef_title))
                                }
                                Text(s(R.string.parcel_editor_vertices,(draft.vertices.size-1).coerceAtLeast(0)),color=Brand.Muted)
                                draft.statedAreaM2?.let{Text(it+" m²",fontWeight=FontWeight.SemiBold)}
                                if(draft.edges.isNotEmpty())Text(draft.edges.joinToString(" · "){"${it.ordinal}: ${it.lengthM} m"},fontSize=12.sp,color=Brand.Muted)
                                draft.statedAreaM2?.takeIf{it.matches(ValidLandArea)}?.let{value->
                                    if(!directVerification&&!readingForListing)TextButton(onClick={area=value}){Text(s(R.string.intake_use_area))}
                                }
                            }
                        }else{
                            OutlinedButton(onClick={step=0;showInvalid=false},modifier=Modifier.fillMaxWidth()){
                                Icon(Icons.Outlined.AttachFile,null);Spacer(Modifier.width(6.dp));Text(s(R.string.intake_add_plan_first))
                            }
                        }
                        HorizontalDivider(color=IntakeLine)
                        OutlinedButton(onClick={chooseMapBoundary=true},enabled=boundaryLocalReady&&!boundaryLocalBusy&&!boundarySendBusy,modifier=Modifier.fillMaxWidth()){
                            Icon(Icons.Outlined.Map,null,Modifier.size(19.dp));Spacer(Modifier.width(6.dp))
                            Text(s(R.string.intake_map_boundary))
                        }
                        if(measured!=null){
                            Text(s(R.string.parcel_editor_calculated_area,measured.areaM2),fontWeight=FontWeight.SemiBold)
                            Text(measured.lengthsM.mapIndexed{i,v->"${i+1}: ${String.format(Locale.ROOT,"%.1f",v)} m"}.joinToString(" · "),fontSize=12.sp,color=Brand.Muted)
                            if(!directVerification&&!readingForListing)TextButton(onClick={area=String.format(Locale.ROOT,"%.2f",measured.areaM2)}){Text(s(R.string.intake_use_area))}
                        }
                        if(boundaryLocalBusy||boundarySendBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
                        if(boundaryLocalStatus!=0)Text(s(boundaryLocalStatus),fontSize=12.sp,
                            color=if(boundaryLocalStatus==R.string.intake_boundary_storage_error)Brand.Crimson else IntakeGreen)
                        if(measured!=null){
                            val ownerCase=(ownerSession?:provisionedCase).takeIf{hasOwnerReviewSession&&!hasPilotReviewSession&&pairReport!=null}
                            if(ownerCase!=null){
                                Text(s(R.string.intake_boundary_case_consent),fontSize=12.sp,color=Brand.Muted)
                                OutlinedButton(onClick={
                                    val points=mapBoundary.toList()
                                    boundarySendBusy=true;boundarySendError=false
                                    scope.launch{
                                        try{saveOwnerMapBoundary(ownerCase,points);boundarySent=true}
                                        catch(cancel:CancellationException){throw cancel}
                                        catch(_:Exception){boundarySendError=true}
                                        finally{boundarySendBusy=false}
                                    }
                                },enabled=!boundarySent&&!boundarySendBusy&&!boundaryLocalBusy,modifier=Modifier.fillMaxWidth()){
                                    Text(s(if(boundarySent)R.string.intake_boundary_case_saved else R.string.intake_boundary_case_save))
                                }
                                if(boundarySendError)Text(s(R.string.intake_boundary_case_error),fontSize=12.sp,color=Brand.Crimson)
                            }
                            TextButton(onClick={
                                val store=boundaryStore
                                if(store==null){mapBoundary=emptyList();boundaryLocalStatus=0}
                                else{
                                    boundaryLocalBusy=true
                                    scope.launch{
                                        try{
                                            withContext(Dispatchers.IO){store.clear()}
                                            mapBoundary=emptyList();boundaryLocalStatus=0
                                        }catch(cancel:CancellationException){throw cancel}
                                        catch(_:Exception){boundaryLocalStatus=R.string.intake_boundary_storage_error}
                                        finally{boundaryLocalBusy=false}
                                    }
                                }
                            },enabled=!boundaryLocalBusy&&!boundarySendBusy){Text(s(R.string.intake_boundary_remove_local))}
                        }
                        Text(s(if(boundarySent)R.string.intake_boundary_case_saved else R.string.intake_map_private),fontSize=12.sp,color=Brand.Muted)
                    }
                    OwnerAdminContextPanel(adminContext,adminSamples.isNotEmpty()&&adminIndex==null&&!adminLoadFailed,
                        adminSamples.isNotEmpty(),adminLoadFailed,adminArabic,
                        onApply={adminContext?.let{region=it.region(adminArabic);lastAutoRegion=region}},
                        hasImadaReference=(adminIndex?.sectorCount?:0)>0)
                    if(docLocations.isNotEmpty())JourneyPanel{
                        Text(s(R.string.intake_document_location),fontWeight=FontWeight.SemiBold)
                        if(docLocationConflict)Text(s(R.string.intake_location_conflict),color=MaterialTheme.colorScheme.error)
                        else if(documentAdmin!=null){
                            Text(documentAdmin.region(adminArabic),color=Brand.Ink)
                            documentAdmin.imada?.let{Text(it.label(adminArabic),color=Brand.Muted)}
                            if(!directVerification&&!readingForListing&&adminSamples.isEmpty())TextButton(onClick={
                                region=documentAdmin.region(adminArabic);lastAutoRegion=region
                            }){Text(s(R.string.intake_admin_use_region))}
                        }else Text(s(R.string.intake_admin_unresolved),color=Brand.Muted)
                    }
                    val extracted=pairReport?.sourceFacts.orEmpty().filter{it.key in setOf(
                        "registerReference","parentRegisterReference","registeredPlotNumber","soldPlotNumber",
                        "possessedPlotNumber","registeredAreaM2","soldAreaM2","possessedAreaM2","transferredAreaM2",
                        "propertyGovernorate","propertyDelegation","propertyImada")&&
                        it.page>0&&it.value.isNotBlank()&&it.status in setOf("CANDIDATE","LOW_CONFIDENCE","CONFLICT")}
                    OwnerSourceFactsPanel(extracted,
                        onArea=if(!directVerification&&!readingForListing){{value->area=value;saveMessage=0}}else null,
                        areaEvidence=pairReport?.let{listingAreaEvidence(it.detectedKind,it.sourceFacts)})
                    JourneySectionHeading(s(if(readingForListing||directVerification)R.string.direct_analysis else R.string.step_property),Icons.Outlined.EditNote)
                    if(hasPilotReviewSession&&pilotAttachmentReview!=null&&(planFile!=null||extraFile!=null||remoteAttachments.isNotEmpty()||attachmentError)){
                        JourneyPanel{
                            Text(s(R.string.intake_supplements_heading),fontWeight=FontWeight.SemiBold)
                            Text(s(if(attachmentReady&&!attachmentPending)R.string.intake_pilot_attachments_saved
                                else R.string.intake_pilot_attachments_hint),fontSize=12.sp,color=Brand.Muted)
                            if(attachmentLoading||attachmentSaving)LinearProgressIndicator(Modifier.fillMaxWidth())
                            if(attachmentError){
                                Text(s(R.string.intake_supplement_failed),color=MaterialTheme.colorScheme.error)
                                TextButton(onClick={attachmentRetry++},enabled=!attachmentLoading&&!attachmentSaving){Text(s(R.string.owner_retry))}
                            }
                            if(attachmentReady&&attachmentPending)OutlinedButton(onClick={
                                attachmentSaving=true;attachmentError=false
                                scope.launch{
                                    try{syncPilotAttachments()}
                                    catch(cancel:CancellationException){throw cancel}
                                    catch(_:Exception){attachmentError=true}
                                    finally{attachmentSaving=false}
                                }
                            },enabled=!attachmentSaving&&!attachmentLoading,modifier=Modifier.fillMaxWidth()){
                                Text(s(R.string.intake_pilot_attachments_save))
                            }
                        }
                    }
                    if(supplementCase!=null && (planFile!=null||extraFile!=null)){
                        SectionHeading(s(R.string.intake_supplements_heading),s(R.string.intake_supplement_owner_hint))
                        planFile?.let{file->
                            Text(file.displayName,color=Brand.Ink)
                            OutlinedButton(onClick={planRequest++},enabled=!planBusy&&planReading?.status!="READY",
                                modifier=Modifier.fillMaxWidth()){
                                Text(s(if(planRequest==0)R.string.intake_attach_plan else R.string.owner_retry))
                            }
                            if(planBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
                            if(planError)Text(s(R.string.intake_supplement_failed),color=MaterialTheme.colorScheme.error)
                            planReading?.let{reading->
                                Text(s(if(reading.status=="READY")R.string.intake_plan_read_ready else R.string.intake_plan_stored),
                                    color=Brand.Muted)
                                val total=if(file.mime=="application/pdf")reading.pagesReceived.coerceIn(1,8) else 1
                                for(page in 1..total){
                                    OutlinedButton(onClick={
                                        parcelEditorSource=OwnerParcelEditorSource(file.uri,file.mime,page,page,
                                            reading.evidenceId,reading.parcelScans.firstOrNull{it.page==page},
                                            reading.parcelOcrLengths.filter{it.page==page},
                                            reading.parcelPlanFacts.selectedFact("titleReference",page).orEmpty(),
                                            reading.parcelPlanFacts.selectedFact("plotReference",page).orEmpty(),
                                            reading.parcelPlanFacts.selectedFact("printedAreaM2",page))
                                    },modifier=Modifier.fillMaxWidth()){
                                        Text(s(R.string.parcel_editor_open,page))
                                    }
                                }
                            }
                        }
                        extraFile?.let{file->
                            Text(file.displayName,color=Brand.Ink)
                            OutlinedButton(onClick={extraRequest++},enabled=!extraBusy&&extraReceipt==null,
                                modifier=Modifier.fillMaxWidth()){Text(s(R.string.intake_attach_extra))}
                            if(extraBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
                            if(extraReceipt!=null)Text(s(R.string.intake_extra_stored),color=Brand.Muted)
                            if(extraError)Text(s(R.string.intake_supplement_failed),color=MaterialTheme.colorScheme.error)
                        }
                    }
                    if(hasPilotReviewSession){
                        OwnerReadingSummary(pairReport,acceptedRead,pairProgress!=null,pairError,
                            showAnalysisDetails,{showAnalysisDetails=!showAnalysisDetails},
                            {if(files.isEmpty()){step=0;pickMessage=R.string.certificate_required}else startPairComparison()},{step=0},
                            onDateRefresh={refreshIdentityDate()},dateBusy=identityDateBusy,dateError=identityDateError)
                        val result = pairReport
                        if (BuildConfig.MALAK_V2_DISCOVERY && result != null && files.isNotEmpty()) {
                            val pdf = files.singleOrNull()?.takeIf { it.mime == "application/pdf" }
                            val pageCount = if (pdf != null) result.certificatePages else files.size
                            for (page in 1..pageCount) {
                                val file = pdf ?: files[page - 1]
                                val scan = result.parcelScans.firstOrNull { it.page == page }
                                OutlinedButton(onClick = {
                                    parcelEditorSource = OwnerParcelEditorSource(
                                        file.uri, file.mime, if (pdf != null) page else 1, page, null, scan,
                                        result.parcelOcrLengths.filter { it.page == page },
                                        result.parcelPlanFacts.selectedFact("titleReference", page)
                                            ?: result.propertyFacts["registerReference"].orEmpty(),
                                        result.parcelPlanFacts.selectedFact("plotReference", page)
                                            ?: result.propertyFacts["soldPlotNumber"]
                                            ?: result.propertyFacts["registeredPlotNumber"].orEmpty(),
                                        result.parcelPlanFacts.selectedFact("printedAreaM2", page) ?: result.localFields?.areaM2
                                    )
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(s(R.string.parcel_editor_open, page))
                                }
                            }
                        }
                    }
                    if(!directVerification && !readingForListing){
                    if(!hasPilotReviewSession&&pairReport!=null){
                        JourneyToggleRow(s(R.string.intake_reading_details),Icons.Outlined.FactCheck,showPrivateReading){showPrivateReading=!showPrivateReading}
                    }
                    if((!hasPilotReviewSession&&(showPrivateReading||pairReport==null))||(hasPilotReviewSession&&showAnalysisDetails)){
                    Surface(shape=RoundedCornerShape(18.dp),color=Color.White,
                        border=BorderStroke(1.dp,CardBorder)){
                        Column(Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Text(s(R.string.malak_my_identity),fontWeight=FontWeight.Bold,
                                color=Brand.Ink)
                            IdFieldsReadout(acceptedRead,s(R.string.camera_field_missing))
                            Text(s(R.string.malak_identity_preliminary),
                                color=Brand.Muted,fontSize=12.sp,lineHeight=18.sp)
                        }
                    }
                    SectionHeading(s(R.string.review_status_title),
                        s(if(pairReport==null)R.string.review_status_intro
                            else R.string.pair_review_received))
                    pairReport?.detectedKind?.let{kind->
                        Text(s(when(kind){
                            "OWNERSHIP_CERTIFICATE","SHARED_OWNERSHIP_CERTIFICATE"->R.string.strength_strong
                            "SALE_DEED","TRANSFER_CARD"->R.string.strength_intermediate
                            "POSSESSION_REPORT"->R.string.strength_supporting
                            "FINANCIAL_RECEIPT","PROPERTY_SERVICE_RECEIPT"->R.string.strength_receipt
                            else->R.string.strength_unknown
                        }),color=Brand.Muted,fontSize=13.sp)
                    }
                    VerificationStage(Icons.Outlined.CompareArrows,
                        s(R.string.match_status),
                        s(when(pairReport?.unifiedAssessment?:pairReport?.assessment){
                            "ROLE_TEXT_MATCH_ONLY"->R.string.unified_role_match
                            "NAME_ONLY_REVIEW"->R.string.malak_name_only_review
                            "CONFLICT_REVIEW"->R.string.malak_role_cin_conflict
                            "NO_PERSON_IDENTIFIED"->R.string.malak_no_named_actor
                            "ROLE_TEXT_DIFFERENCE"->R.string.unified_role_difference
                            "ROLE_AMBIGUOUS"->R.string.unified_role_ambiguous
                            "INSUFFICIENT_EVIDENCE"->R.string.unified_role_inconclusive
                            "PRELIMINARY_TEXT_AGREEMENT"->R.string.pair_agreement
                            "TEXT_DIFFERENCE_NEEDS_REVIEW"->R.string.pair_difference
                            "INSUFFICIENT_OR_AMBIGUOUS"->R.string.pair_uncertain
                            else->R.string.match_pending
                        }),
                        when(pairReport?.unifiedAssessment?:pairReport?.assessment){
                            "ROLE_TEXT_MATCH_ONLY","NAME_ONLY_REVIEW",
                            "CONFLICT_REVIEW","NO_PERSON_IDENTIFIED",
                            "ROLE_TEXT_DIFFERENCE","ROLE_AMBIGUOUS",
                            "INSUFFICIENT_EVIDENCE"->ReadingStatus.REVIEW
                            "PRELIMINARY_TEXT_AGREEMENT"->ReadingStatus.EXTRACTED
                            "TEXT_DIFFERENCE_NEEDS_REVIEW",
                            "INSUFFICIENT_OR_AMBIGUOUS"->ReadingStatus.REVIEW
                            else->if(pairProgress!=null)
                                ReadingStatus.READING else ReadingStatus.PENDING
                        })
                    if(pairReport!=null){
                        val result=pairReport!!
                        Surface(shape=RoundedCornerShape(18.dp),
                            color=Color.White,border=BorderStroke(1.dp,CardBorder)){
                            Column(Modifier.fillMaxWidth().padding(14.dp)){
                                if(result.unifiedAssessment!=null){
                                    OcrDataRow(s(R.string.unified_document_kind),
                                        s(documentFamilyLabel(result.detectedKind?:"UNKNOWN")))
                                    val safeMatch=result.unifiedAssessment in
                                        setOf("ROLE_TEXT_MATCH_ONLY","NAME_ONLY_REVIEW")
                                    // Never show seller, buyer or coowner records that
                                    // belong to someone OTHER than the captured ID holder.
                                    val matched=if(safeMatch)
                                        result.unifiedRoles.filter{
                                            it.assessment=="PRELIMINARY_TEXT_AGREEMENT"}
                                    else emptyList()
                                    if(matched.size==1){
                                        val role=matched.single()
                                        HorizontalDivider(color=CardBorder)
                                        OcrDataRow(s(R.string.malak_matched_document_role),
                                            s(unifiedRoleLabel(role.role)))
                                        HorizontalDivider(color=CardBorder)
                                        OcrDataRow(s(R.string.malak_document_cin_status),
                                            s(when(role.documentCinStatus){
                                                "SAME_NUMBER_UNVERIFIED"->
                                                    R.string.malak_cin_text_only
                                                "NOT_PRINTED"->
                                                    R.string.malak_cin_not_printed
                                                "MASKED_IN_SOURCE"->
                                                    R.string.malak_cin_masked
                                                "NUMBER_DIFFERS_NEEDS_REVIEW"->
                                                    R.string.malak_role_cin_conflict
                                                else->R.string.malak_cin_unreadable
                                            }))
                                    }else{
                                        Text(s(R.string.malak_no_auto_identity_association),
                                            fontSize=12.sp,color=Brand.Muted,lineHeight=18.sp)
                                    }
                                    if(matched.size==1){
                                        result.matchedRightsHolder?.let{holder->
                                            HorizontalDivider(color=CardBorder)
                                            OcrDataRow(s(R.string.malak_holder_parts),
                                                holder.parts?:s(R.string.malak_parts_missing),
                                                numeric=holder.parts!=null)
                                            HorizontalDivider(color=CardBorder)
                                            OcrDataRow(s(R.string.malak_holder_row),
                                                holder.rowOrdinal.toString(),numeric=true)
                                            HorizontalDivider(color=CardBorder)
                                            OcrDataRow(s(R.string.smart_read_page),
                                                holder.page.toString(),numeric=true)
                                        }
                                    }
                                    result.sourceFacts.forEach{fact->
                                        HorizontalDivider(color=CardBorder)
                                        OcrDataRow(s(familyFactLabel(fact.key)),fact.value)
                                        Text(s(R.string.smart_read_page)+" "+fact.page+" · "+
                                            s(if(fact.source=="pdf-text")R.string.source_pdf_text else R.string.source_scan_text)+" · "+s(if(fact.status=="LOW_CONFIDENCE")
                                                R.string.smart_read_needs_review else R.string.smart_read_source_available),
                                            color=Brand.Muted,fontSize=12.sp)
                                    }
                                    Text(s(R.string.unified_not_ownership),
                                        fontSize=12.sp,color=Brand.Muted,lineHeight=18.sp)
                                }else{
                                OcrDataRow(s(R.string.pair_certificate_owner),
                                    result.certificateName?.value
                                        ?:s(R.string.camera_field_missing))
                                HorizontalDivider(color=CardBorder)
                                OcrDataRow(s(R.string.pair_identity_owner),
                                    result.identityName?.value
                                        ?:s(R.string.camera_field_missing))
                                HorizontalDivider(color=CardBorder)
                                OcrDataRow(s(R.string.pair_cin_label),
                                    s(when(result.cinStatus){
                                        "SAME_NUMBER_UNVERIFIED"->R.string.pair_cin_equal
                                        "NUMBER_DIFFERS_NEEDS_REVIEW"->R.string.pair_cin_diff
                                        "AMBIGUOUS_OR_LOW_CONFIDENCE"->R.string.pair_cin_uncertain
                                        else->R.string.pair_cin_missing
                                    }))
                                result.localFields?.let{local->
                                    if(local.titleNumber!=null){
                                        HorizontalDivider(color=CardBorder)
                                        OcrDataRow(s(R.string.local_title_ref),
                                            local.titleNumber)
                                    }
                                    if(local.areaM2!=null){
                                        HorizontalDivider(color=CardBorder)
                                        OcrDataRow(s(R.string.local_area_m2),
                                            local.areaM2+" m²",numeric=true)
                                    }
                                }
                                }
                            }
                        }
                        result.reasons.distinct().forEach{code->
                                Text(s(pairReasonText(code)),
                                    color=Brand.Muted,fontSize=12.sp,lineHeight=18.sp)
                            }
                        PlainNotice(Icons.Outlined.Info,
                            s(R.string.pair_preliminary_only))
                    }
                    if(pairProgress!=null) {
                        PlainNotice(Icons.Outlined.HourglassTop,
                            s(when(pairProgress){
                                PairProgress.CASE_PROVISION->R.string.pair_creating_case
                                PairProgress.CERTIFICATE_UPLOAD->R.string.pair_upload_cert
                                PairProgress.IDENTITY_UPLOAD->R.string.pair_upload_identity
                                else->R.string.pair_comparing
                            }))
                    }
                    if(pairError!=0)PlainNotice(Icons.Outlined.Info,s(pairError))

                    if(!canRunPrivateMatching){
                        Text(s(R.string.review_auth_needed),
                            color=Brand.Muted,fontSize=12.sp,lineHeight=18.sp)
                    }else{
                        Text(s(R.string.review_send_disclosure),
                            color=Brand.Muted,fontSize=12.sp,lineHeight=18.sp)
                        Text(s(if(familyReadings.isEmpty())R.string.smart_pair_first_read
                            else R.string.unified_identity_hint),
                            color=Brand.Muted,fontSize=12.sp,lineHeight=18.sp)
                        Button(onClick={startPairComparison()}
                        ,enabled=pairProgress==null&&pairReport==null&&files.isNotEmpty()&&identityReadyForRemote&&
                            canRunPrivateMatching&&!familyReadBusy,
                            modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),
                            colors=ButtonDefaults.buttonColors(containerColor=Brand.Crimson),
                            shape=RoundedCornerShape(16.dp)) {
                            Icon(Icons.Outlined.FactCheck,null)
                            Spacer(Modifier.width(8.dp))
                            Text(s(if(pairReport==null)R.string.pair_start else R.string.pair_already_read))
                        }
                    }
                    if(pairReport==null)
                        PlainNotice(Icons.Outlined.Info,s(R.string.legal_pending))
                    }

                    Column(Modifier.bringIntoViewRequester(missingFieldsTarget)){
                    JourneySectionHeading(s(R.string.property_section),Icons.Outlined.Home)
                    if(showInvalid&&missingListingFields.isNotEmpty()){
                        val labels=missingListingFields.map{field->s(when(field){
                            "title"->R.string.property_name
                            "region"->R.string.property_region
                            "area"->R.string.area_m2
                            "customer"->R.string.submission_customer_name
                            else->R.string.price_tnd
                        })}
                        PlainNotice(Icons.Outlined.Info,s(R.string.listing_missing_fields)+" "+labels.joinToString("، "))
                    }
                    }


                    JourneyPanel {
                    Surface(color=Color(0xFFF2F8F5),shape=RoundedCornerShape(12.dp)){
                        Column(Modifier.fillMaxWidth().padding(15.dp),
                            verticalArrangement=Arrangement.spacedBy(8.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically,
                                horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                Icon(Icons.Outlined.AutoAwesome,null,
                                    Modifier.size(20.dp),tint=Color(0xFF267B63))
                                Column(Modifier.weight(1f)){
                                    Text(s(R.string.smart_title_auto),fontSize=12.sp,
                                        color=Brand.Muted)
                                    Text(listingTitle.ifBlank{s(R.string.smart_title_pending)},
                                        fontSize=15.sp,color=Brand.Ink,
                                        fontWeight=FontWeight.SemiBold)
                                }
                                TextButton(onClick={editTitle=!editTitle}){
                                    Text(s(if(editTitle)R.string.smart_title_done else R.string.smart_title_edit),fontSize=12.sp)
                                }
                            }
                            if(editTitle)
                                OutlinedTextField(name,{name=it.take(100);saveMessage=0},
                                    label={Text(s(R.string.property_name))},
                                    modifier=Modifier.fillMaxWidth(),singleLine=true,
                                    isError=showInvalid&&name.isNotBlank()&&name.trim().length<2,
                                    supportingText=if(showInvalid&&name.isNotBlank()&&name.trim().length<2)
                                        {{Text(s(R.string.field_required))}}else null)
                        }
                    }
                    OutlinedTextField(region,{region=it.take(100);saveMessage=0},
                        label={Text(s(R.string.property_region))},modifier=Modifier.fillMaxWidth(),singleLine=true,
                        isError=showInvalid&&region.trim().length<2,
                        supportingText=if(showInvalid&&region.trim().length<2){{Text(s(R.string.field_required))}}else null)
                    val areaFocus = remember { FocusRequester() }
                    pairReport?.let { report ->
                        listingAreaEvidence(report.detectedKind, report.sourceFacts)?.let { fact ->
                            ListingAreaSuggestion(report.detectedKind.orEmpty(), fact, area,
                                onAccept = { area = it; saveMessage = 0 },
                                onEditPart = { area = it; areaFocus.requestFocus(); keyboard?.show() })
                        }
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedTextField(area,{area=it.filter { char->char.isDigit()||char=='.'||char==',' }.take(16);saveMessage=0},
                        label={Text(s(R.string.area_m2))},modifier=Modifier.weight(1f).focusRequester(areaFocus),singleLine=true,
                        textStyle=TextStyle(textDirection=TextDirection.Ltr,textAlign=TextAlign.Start,fontSize=16.sp),
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),
                        isError=showInvalid&&!validArea,
                        supportingText=if(showInvalid&&!validArea){{Text(s(R.string.area_invalid))}}else null)
                    OutlinedTextField(price,{price=it.filter { char->char.isDigit() }.take(15);saveMessage=0},
                        label={Text(s(R.string.price_tnd))},modifier=Modifier.weight(1f),singleLine=true,
                        textStyle=TextStyle(textDirection=TextDirection.Ltr,textAlign=TextAlign.Start,fontSize=16.sp),
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                        isError=showInvalid&&!validPrice,
                        supportingText=if(showInvalid&&!validPrice){{Text(s(R.string.price_invalid))}}else null)
                    }
                    val unitPrice=SmartListingAssist.pricePerSquareMeter(area,price)
                    if(unitPrice!=null){
                        Surface(color=Color(0xFFF2F8F5),shape=RoundedCornerShape(15.dp)){
                            Row(Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment=Alignment.CenterVertically,
                                horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                Icon(Icons.Outlined.Calculate,null,tint=Color(0xFF267B63))
                                Column {
                                    Text(s(R.string.smart_unit_price),fontSize=12.sp,color=Brand.Muted)
                                    Text("${java.text.NumberFormat.getNumberInstance(Locale.FRANCE).format(unitPrice)} TND / m²",
                                        fontWeight=FontWeight.SemiBold,color=Brand.Ink)
                                }
                            }
                        }
                    }
                    }

                    if(!hasPilotReviewSession){
                    Surface(color=Color(0xFFF2F8F5),shape=RoundedCornerShape(19.dp)) {
                        Column(Modifier.fillMaxWidth().padding(15.dp),
                            verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically,
                                horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                                Icon(Icons.Outlined.DocumentScanner,null,tint=Color(0xFF267B63))
                                Text(s(R.string.smart_read_source_title),
                                    fontWeight=FontWeight.Bold,color=Brand.Ink)
                            }
                            Text(s(R.string.smart_read_source_intro),fontSize=12.sp,
                                color=Brand.Muted,lineHeight=19.sp)
                            if(!hasOwnerReviewSession)
                                Text(s(if(hasPilotReviewSession)
                                    R.string.malak_pilot_scan_after_identity
                                    else R.string.smart_read_auth_required),
                                    fontSize=12.sp,color=Brand.Crimson)
                            Button(onClick={
                                if(files.isEmpty()){familyReadError=R.string.smart_read_failed}
                                else {
                                    familyReadJob?.cancel()
                                    val epoch=++familyReadEpoch
                                    val selected=files.toList()
                                    val keys=selected.map{it.uploadKey}
                                    val values=listOf(listingTitle,region.trim(),
                                        normalizedArea,price,capacity)
                                    val snapshot=values.joinToString("\u0001")
                                    familyReadBusy=true
                                    familyReadError=0
                                    familyReadings=emptyList()
                                    familyReadJob=scope.launch {
                                        try {
                                            val session=ensureEvidenceIntakeCase()
                                            val authenticated=QaOcrSession(
                                                session.httpsBaseUrl,session.caseId,session.bearer)
                                            val result=selected.mapIndexed{index,item->
                                                readOcrForOwner(context,item.uri,item.length,item.mime,
                                                    if(item.mime=="application/pdf")null else index+1,
                                                    item.uploadKey+"-ocr",normalizedArea,authenticated,
                                                    evidenceKind="OTHER_LEGAL_PROOF")
                                            }
                                            if(epoch==familyReadEpoch &&
                                                files.map{it.uploadKey}==keys &&
                                                listOf(listingTitle,region.trim(),
                                                    area.replace(',','.'),price,capacity)
                                                    .joinToString("\u0001")==snapshot)
                                                familyReadings=result
                                        }catch(cancel:CancellationException){throw cancel}
                                        catch(_:Exception){
                                            if(epoch==familyReadEpoch)
                                                familyReadError=R.string.smart_read_failed
                                        }finally{
                                            if(epoch==familyReadEpoch)familyReadBusy=false
                                        }
                                    }
                                }
                            },enabled=!familyReadBusy&&files.isNotEmpty()&&
                                hasOwnerReviewSession,
                                modifier=Modifier.fillMaxWidth().heightIn(min=50.dp),
                                shape=RoundedCornerShape(14.dp)) {
                                Icon(Icons.Outlined.AutoAwesome,null,Modifier.size(19.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(s(if(familyReadBusy)R.string.smart_read_working
                                    else R.string.smart_read_action))
                            }
                            if(familyReadError!=0)PlainNotice(Icons.Outlined.Info,s(familyReadError))
                            if(familyReadings.isNotEmpty()) {
                                familyReadings.forEachIndexed{docIndex,read->
                                    Text("${s(R.string.smart_read_document)} ${docIndex+1} · "+
                                        s(documentFamilyLabel(read.familyKind)),
                                        fontSize=13.sp,fontWeight=FontWeight.SemiBold,
                                        color=Brand.Ink)
                                    if(read.status=="READY"){
                                        read.familyFacts.filter{it.value!=null&&
                                            it.status in listOf("CANDIDATE","LOW_CONFIDENCE")&&
                                            it.id in listOf("registerReference","registeredAreaM2",
                                                "soldAreaM2","possessedAreaM2",
                                                "transferredAreaM2","soldPlotNumber")}.forEach{fact->
                                            Surface(color=Color.White,
                                                shape=RoundedCornerShape(11.dp)){
                                                Column(Modifier.fillMaxWidth().padding(10.dp),
                                                    verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                                    Text(s(familyFactLabel(fact.id)),
                                                        color=Brand.Muted,fontSize=12.sp)
                                                    Text(fact.value?:"",fontWeight=FontWeight.SemiBold,
                                                        fontSize=14.sp,color=Brand.Ink)
                                                    Text("${s(R.string.smart_read_page)} ${fact.page?:1} · "+
                                                        s(if(fact.status=="LOW_CONFIDENCE")
                                                            R.string.smart_read_needs_review
                                                          else R.string.smart_read_source_available),
                                                        color=Brand.Muted,fontSize=12.sp)
                                                    // Only explicitly SOLD area can be offered as an
                                                    // ad-area suggestion, never a whole-title area.
                                                    if(read.familyKind=="SALE_DEED"&&
                                                        fact.id=="soldAreaM2"&&
                                                        fact.status=="CANDIDATE"&&
                                                        fact.value!=null&&
                                                        fact.value.matches(ValidLandArea))
                                                        TextButton(onClick={
                                                            area=fact.value
                                                            showInvalid=false
                                                        }){Text(s(R.string.smart_use_sold_area))}
                                                }
                                            }
                                        }
                                        val file = files.getOrNull(docIndex)
                                        if (BuildConfig.MALAK_V2_DISCOVERY && file != null) {
                                            val pdf = file.mime == "application/pdf"
                                            val pages = if (pdf) (1..read.pagesReceived.coerceIn(1, 8)).toList()
                                                else listOf(docIndex + 1)
                                            for (page in pages) {
                                                val scan = if (pdf) read.parcelScans.firstOrNull { it.page == page }
                                                    else read.parcelScans.singleOrNull()
                                                OutlinedButton(onClick = {
                                                    fun fact(vararg ids: String) = ids.firstNotNullOfOrNull { id ->
                                                        read.familyFacts.firstOrNull {
                                                            it.id == id && it.status == "CANDIDATE"
                                                        }?.value
                                                    }
                                                    parcelEditorSource = OwnerParcelEditorSource(
                                                        file.uri, file.mime, if (pdf) page else 1, page,
                                                        read.evidenceId, scan,
                                                        read.parcelOcrLengths.filter { !pdf || it.page == page },
                                                        read.parcelPlanFacts.selectedFact("titleReference", page)
                                                            ?: fact("registerReference").orEmpty(),
                                                        read.parcelPlanFacts.selectedFact("plotReference", page)
                                                            ?: fact("soldPlotNumber", "registeredPlotNumber", "possessedPlotNumber").orEmpty(),
                                                        read.parcelPlanFacts.selectedFact("printedAreaM2", page)
                                                            ?: fact("soldAreaM2", "registeredAreaM2", "possessedAreaM2", "transferredAreaM2")
                                                    )
                                                }, modifier = Modifier.fillMaxWidth()) {
                                                    Text(s(R.string.parcel_editor_open, page))
                                                }
                                            }
                                            if (parcelSaved) Text(s(R.string.parcel_editor_saved),
                                                color = Brand.Ink, fontSize = 12.sp)
                                        }
                                    }else Text(s(R.string.smart_read_needs_review),
                                        color=Brand.Muted,fontSize=12.sp)
                                }
                            }
                        }
                    }
                    JourneySectionHeading(s(R.string.capacity_section),Icons.Outlined.PersonOutline)
                    JourneyPanel {
                        Capacities.chunked(2).forEach{row->
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                row.forEach{option->
                                    FilterChip(selected=capacity==option.code,onClick={
                                        capacity=option.code;capacityConfirmed=true;saveMessage=0
                                    },label={Text(s(option.label),fontSize=13.sp,maxLines=2)},
                                        modifier=Modifier.weight(1f).heightIn(min=48.dp),
                                        leadingIcon=if(capacity==option.code){{Icon(Icons.Outlined.CheckCircle,null,Modifier.size(18.dp))}}else null)
                                }
                            }
                        }
                    }
                    }
                    if(pilotOcrSession!=null){
                        if(customerName.isBlank()||editCustomerName){
                            OutlinedTextField(customerName,{customerName=it.take(120)},
                                label={Text(s(R.string.submission_customer_name))},
                                isError=showInvalid&&customerName.trim().length<2,
                                singleLine=true,modifier=Modifier.fillMaxWidth())
                        }else{
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Text(customerName,Modifier.weight(1f),color=Brand.Muted)
                                TextButton(onClick={editCustomerName=true}){Text(s(R.string.owner_edit_presenter))}
                            }
                        }
                    }
                    val descPrefix=s(R.string.smart_land_title_prefix)
                    val descUnit=s(R.string.smart_area_unit)
                    val descPrice=s(R.string.smart_description_price_prefix)
                    JourneySectionHeading(s(R.string.smart_description_heading),Icons.Outlined.Notes)
                    JourneyPanel{
                        OutlinedTextField(listingDescription,{listingDescription=it.take(650)},
                            modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=6,
                            label={Text(s(R.string.smart_description_heading))})
                        TextButton(onClick={
                            SmartListingAssist.descriptionSuggestion(region,area,price,
                                descPrefix,descUnit,descPrice)
                                ?.let{listingDescription=it}
                        },enabled=validArea&&validPrice&&region.trim().length>=2){
                            Icon(Icons.Outlined.AutoAwesome,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp))
                            Text(s(R.string.smart_description_generate))
                        }
                    }
                    // Photos stay in the first step. Advanced coordinate fields are optional.
                    JourneyToggleRow(s(R.string.intake_manual_coordinates),Icons.Outlined.Map,showListingExtras){showListingExtras=!showListingExtras}
                    if(showListingExtras)JourneyPanel{
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            OutlinedTextField(listingLatitude,{listingLatitude=it.take(14)},label={Text(s(R.string.smart_latitude))},
                                modifier=Modifier.weight(1f),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                            OutlinedTextField(listingLongitude,{listingLongitude=it.take(14)},label={Text(s(R.string.smart_longitude))},
                                modifier=Modifier.weight(1f),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                        }
                    }
                    }
                }
                else -> {
                    JourneySectionHeading(s(R.string.summary_title),Icons.Outlined.Visibility)
                    if(directVerification){
                        JourneyPanel{
                            Text(listingTitle,style=MaterialTheme.typography.titleMedium,color=Brand.Ink)
                            Text(s(R.string.direct_docs_private),color=Brand.Muted)
                        }
                    }else{
                        JourneyPanel{
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Text(s(R.string.smart_preview_heading),fontWeight=FontWeight.Bold,color=Brand.Crimson,modifier=Modifier.weight(1f))
                                TextButton(onClick={step=2;showInvalid=false}){Text(s(R.string.edit_property))}
                            }
                            OwnerListingPreview(listingTitle.ifBlank{s(R.string.no_value)},region,area,price,listingDescription,listingPhotos.toList())
                            Text(s(R.string.smart_preview_disclosure),fontSize=12.sp,color=Brand.Muted)
                        }
                    }
                    adminContext?.let{admin->JourneyPanel{
                        JourneyStatusLine(s(R.string.intake_governorate),admin.governorate.label(adminArabic),true)
                        JourneyStatusLine(s(R.string.intake_delegation),admin.delegation?.label(adminArabic)?:s(R.string.intake_admin_unresolved),admin.delegation!=null)
                        JourneyStatusLine(s(R.string.intake_imada),admin.imada?.label(adminArabic)?:s(if((adminIndex?.sectorCount?:0)>0)R.string.intake_admin_unresolved else R.string.intake_imada_unavailable),admin.imada!=null)
                        Text(s(R.string.intake_admin_source),fontSize=12.sp,color=Brand.Muted)
                    }}
                    measureOwnerMap(mapBoundary)?.let{measurement->
                        JourneyPanel{
                            Text(s(R.string.intake_map_boundary),fontWeight=FontWeight.SemiBold)
                            Text(s(R.string.parcel_editor_calculated_area,measurement.areaM2))
                            Text(s(if(boundarySent)R.string.intake_boundary_case_saved else if(hasPilotReviewSession)
                                R.string.intake_boundary_pilot_not_sent else R.string.intake_map_private),fontSize=12.sp,color=Brand.Muted)
                            TextButton(onClick={step=2;chooseMapBoundary=true},enabled=boundaryLocalReady&&!boundaryLocalBusy&&!boundarySendBusy){Text(s(R.string.intake_review_boundary))}
                        }
                    }
                    JourneySectionHeading(s(R.string.intake_private_heading),Icons.Outlined.Lock)
                    JourneyPanel{
                        JourneyStatusLine(s(R.string.intake_cert_title),
                            s(if(certReady)R.string.intake_added else R.string.intake_empty),certReady)
                        JourneyStatusLine(s(R.string.identity_section),
                            s(if(identitySelected||pairReport!=null)R.string.intake_added else R.string.intake_empty),identitySelected||pairReport!=null)
                        JourneyStatusLine(s(R.string.intake_plan),
                            s(if(planReading?.status=="READY")R.string.intake_plan_read_ready else if(planFile!=null&&uploadedAttachmentKeys["CADASTRAL_PLAN"]==planFile?.uploadKey)R.string.intake_pilot_attachments_saved else if(planFile!=null)R.string.intake_added else R.string.intake_optional),planFile!=null)
                        JourneyStatusLine(s(R.string.intake_extra),
                            s(if(extraReceipt!=null||(extraFile!=null&&uploadedAttachmentKeys["OTHER_LEGAL_PROOF"]==extraFile?.uploadKey))R.string.intake_extra_stored else if(extraFile!=null)R.string.intake_added else R.string.intake_optional),extraFile!=null)
                        Text(s(R.string.intake_private_short),fontSize=12.sp,color=Brand.Muted)
                        if(planFile!=null||extraFile!=null)Text(s(if(hasOwnerReviewSession&&!hasPilotReviewSession)
                            R.string.intake_supplement_owner_hint else if(hasPilotReviewSession)R.string.intake_pilot_attachments_hint else R.string.intake_optional_preview_only),fontSize=12.sp,color=Brand.Muted)
                        TextButton(onClick={step=0;showInvalid=false}){Icon(Icons.Outlined.Edit,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(s(R.string.intake_edit_documents))}
                    }
                    if(!directVerification){
                        JourneyPanel{
                            OcrDataRow(s(R.string.smart_media_heading),listingPhotos.size.toString())
                            OcrDataRow(s(R.string.summary_capacity),s(Capacities.firstOrNull{it.code==capacity}?.label?:R.string.malak_capacity_not_declared))
                            if(pilotOcrSession!=null)OcrDataRow(s(R.string.submission_customer_name),customerName)
                            if(pilotOcrSession!=null)Text(s(R.string.submission_review_hint),fontSize=12.sp,color=Brand.Muted)
                            else Text(s(R.string.draft_local),fontSize=12.sp,color=Brand.Muted)
                        }
                    }
                    if(submissionMessage!=0)PlainNotice(Icons.Outlined.Info,s(submissionMessage))
                }
            }
            if(saveMessage!=0) PlainNotice(
                if(saveMessage==R.string.draft_error) Icons.Outlined.ErrorOutline
                else Icons.Outlined.CheckCircle,
                s(saveMessage)
            )

        }
        key(step){
        Column(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            // Never show "Continue" under a failed scan: keep retake in reach.
            if(intakeStep&&pendingPhoto!=null){
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    OutlinedButton(onClick={
                        pendingPhoto?.let{PrivateCaptureMemory.discard(it.uri)}
                        pendingPhoto=null
                        frontRead=null
                        openCamera(OwnerIntakeKind.Identity)
                    },modifier=Modifier.weight(1f).heightIn(min=54.dp)){
                        Text(s(R.string.camera_review_retake))
                    }
                    Button(onClick={
                        val pending=pendingPhoto
                        if(pending!=null&&frontRead?.readyForCameraAcceptance==true){
                            identityFiles.forEach{PrivateCaptureMemory.discard(it.uri)}
                            identityFiles.clear()
                            identityFiles.add(pending)
                            acceptedFrontKey=pending.uploadKey
                            acceptedRead=frontRead?.takeIf{it.issue==null}
                            pendingPhoto=null
                            frontRead=null
                            identityMessage=0
                            // Stay on intake; explicit Analyze follows.
                        } else {
                            identityMessage=R.string.camera_review_required_fields
                        }
                    },enabled=!frontReadBusy&&
                        frontRead?.readyForCameraAcceptance==true,
                        modifier=Modifier.weight(1f).heightIn(min=54.dp)){
                        Text(s(R.string.camera_review_accept))
                    }
                }
            }else{
            if(intakeStep)Text(s(when{!certReady&&!identitySelected->R.string.intake_missing_both
                !certReady->R.string.intake_missing_cert
                !identitySelected->R.string.intake_missing_id
                else->R.string.intake_ready}),modifier=Modifier.fillMaxWidth().semantics{liveRegion=LiveRegionMode.Polite},
                textAlign=TextAlign.Center,fontSize=12.sp,color=if(requiredCount==2)IntakeGreen else Brand.Muted)
            if(intakeStep&&requiredCount==2&&canRunPrivateMatching)
                Text(s(R.string.intake_analysis_disclosure),fontSize=12.sp,color=Brand.Muted,
                    modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)
            Button(onClick={
                focusManager.clearFocus();keyboard?.hide()
                if(needsCertificate){
                    pickMessage=R.string.certificate_required
                    scope.launch { withFrameNanos { }; certificateTarget.bringIntoView() }
                }
                else if(step==2&&!validFirst&&!directVerification&&!readingForListing){
                    showInvalid=true
                    scope.launch{withFrameNanos{};missingFieldsTarget.bringIntoView()}
                }
                else if(intakeStep&&(!identitySelected||pendingPhoto!=null)){
                    identityMessage=R.string.identity_required
                }
                else if(step==2&&pilotOcrSession!=null&&pairReport?.reviewId==null){
                    submissionMessage=R.string.submission_scan_required
                    pairError=R.string.submission_scan_required
                }
                else if(step==2 && onDocumentRead!=null){
                    pairReport?.takeIf { it.reviewId != null }?.let { report ->
                        if(attachmentPending){
                            submissionMessage=R.string.intake_pilot_attachments_save
                        }else{
                            feedback(AppCue.Confirm)
                            onDocumentRead(report)
                            prefs.edit().clear().apply()
                        }
                    }
                }
                else if(step<3){feedback(AppCue.Action);step=OwnerIntakePolicy.next(step);showInvalid=false;saveMessage=0}
                else if(directVerification&&pilotOcrSession!=null&&!sending){
                    val review=pairReport?.reviewId
                    val listingId=resubmission?.optString("id").orEmpty()
                    if(review==null||listingId.isBlank()){submissionMessage=R.string.submission_scan_required}
                    else {
                        sending=true
                        requestJob=scope.launch{
                            try{
                                syncPilotAttachments()
                                PilotListingClient.call(pilotOcrSession,"/api/pilot/direct-listings/$listingId/verification",
                                    org.json.JSONObject().put("reviewId",review))
                                prefs.edit().remove("review_id").remove("review_scope").remove("request_key").apply()
                                onSubmitted(listingId)
                            }catch(cancel:CancellationException){throw cancel}
                            catch(_:Exception){submissionMessage=R.string.submission_failed}
                            finally{sending=false}
                        }
                    }
                }
                else if(pilotOcrSession!=null&&!sending&&!submitted){
                    val review=pairReport?.reviewId
                    if(review==null){submissionMessage=R.string.submission_scan_required}
                    else if(customerName.trim().length<2){submissionMessage=R.string.submission_name_required}
                    else{
                        sending=true
                        val data=org.json.JSONObject().put("reviewId",review)
                            .put("requestKey",submissionKey).put("customerName",customerName.trim())
                            .put("submissionId",recoveryScope.takeIf{it.isNotBlank()})
                            .put("title",listingTitle).put("region",region.trim())
                            .put("area",normalizedArea).put("price",price)
                            .put("description",listingDescription).put("latitude",listingLatitude)
                            .put("longitude",listingLongitude)
                        val sendEpoch=++requestEpoch
                        requestJob=scope.launch{
                            try{
                                syncPilotAttachments()
                                listingPhotos.toList().forEach{photo->
                                    PilotListingClient.photo(context,pilotOcrSession,review,photo.uri,photo.length)
                                }
                                val response=PilotListingClient.call(pilotOcrSession,"/api/pilot/submissions",data)
                                val receipt=response.optJSONObject("submission")?:throw OwnerPairException(502)
                                val reference=pilotSubmissionReference(receipt.optString("id"),receipt.optString("status"))?:throw OwnerPairException(502)
                                if(requestEpoch==sendEpoch&&pairReport?.reviewId==review){
                                    submittedReference=reference
                                    submittedId=receipt.optString("id");submittedStatus=receipt.optString("status")
                                    feedback(AppCue.Confirm)
                                    submitted=true;submissionMessage=R.string.submission_sent
                                }
                            }catch(cancel:CancellationException){throw cancel}
                            catch(_:Exception){if(requestEpoch==sendEpoch)submissionMessage=R.string.submission_failed}
                            finally{if(requestEpoch==sendEpoch)sending=false}
                        }
                    }
                }
                else if(pilotOcrSession==null)saveLocalDraft()
            }, enabled=(!intakeStep||requiredCount==2)&&!boundaryLocalBusy&&!boundarySendBusy&&!sending&&!submitted&&!identityDateBusy&&!planBusy&&!extraBusy&&!attachmentLoading&&!attachmentSaving&&!(step==2&&pilotOcrSession!=null&&pairReport==null), modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),
                shape=RoundedCornerShape(17.dp),colors=ButtonDefaults.buttonColors(
                    containerColor = if (needsCertificate) Brand.NeutralContainer else Brand.Crimson,
                    contentColor = if (needsCertificate) Brand.Ink else Color.White
                )) {
                Text(s(if(step==2 && readingForListing)R.string.listing_attach_reading
                    else if(step==3&&pilotOcrSession!=null)
                    if(sending)R.string.submission_sending else if(submitted)R.string.submission_sent
                    else if(directVerification)R.string.direct_submit_docs else R.string.submission_send
                    else if(step==3)R.string.save_draft else if(intakeStep)R.string.intake_analyze
                    else R.string.continue_label),fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                Spacer(Modifier.width(9.dp))
                Icon(if(step==3)Icons.Outlined.Save else Icons.AutoMirrored.Filled.ArrowForward,
                    null,Modifier.size(21.dp))
            }

            }
        }
        }
    }
}

@Composable
private fun SectionHeading(title:String,description:String) {
    Column(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Text(title, Modifier.semantics { heading() }, style=MaterialTheme.typography.titleLarge,color=Brand.Ink)
        Text(description,color=Brand.Muted,style=MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PlainNotice(icon:androidx.compose.ui.graphics.vector.ImageVector,message:String) {
    Surface(shape=RoundedCornerShape(16.dp),color=Brand.NeutralContainer,contentColor=Brand.Ink) {
        Row(Modifier.fillMaxWidth().padding(13.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Icon(icon,null,Modifier.size(22.dp),tint=Brand.Muted)
            Text(message,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun IdFieldsReadout(scan:LocalDocumentOcr.FrontScan?,placeholder:String){
    OcrDataRow(s(R.string.local_surname),scan?.surname?:placeholder)
    HorizontalDivider(color=CardBorder)
    OcrDataRow(s(R.string.local_given_name),scan?.givenName?:placeholder)
    HorizontalDivider(color=CardBorder)
    OcrDataRow(s(R.string.camera_review_cin),
        scan?.cinFull?:placeholder,numeric=true)
    HorizontalDivider(color=CardBorder)
    OcrDataRow(s(R.string.camera_review_birth),
        scan?.birthDate?:placeholder,numeric=true)
}

@Composable
private fun OcrDataRow(label:String,value:String,numeric:Boolean=false){
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp)
        .padding(horizontal=4.dp,vertical=7.dp),
        verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(12.dp)){
        Text(label,Modifier.weight(1f),fontSize=12.sp,
            color=Brand.Muted)
        Text(value,Modifier.weight(1.2f),fontSize=15.sp,
            fontWeight=FontWeight.SemiBold,color=Brand.Ink,
            textAlign=TextAlign.End,
            style=TextStyle(textDirection=if(numeric)
                TextDirection.Ltr else TextDirection.Content))
    }
}

@Composable
private fun SummaryFact(label:String,value:String) {
    Surface(shape=RoundedCornerShape(15.dp),color=Color.White,border=BorderStroke(1.dp,CardBorder)) {
        Row(Modifier.fillMaxWidth().heightIn(min=54.dp).padding(13.dp),
            horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(label,Modifier.weight(1f),color=Brand.Muted,fontSize=12.sp)
            Text(value,Modifier.weight(1.3f),color=Brand.Ink,fontSize=13.sp,
                fontWeight=FontWeight.SemiBold,textAlign=TextAlign.End)
        }
    }
}

@Composable
private fun VerificationStage(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    heading:String,detail:String,status:ReadingStatus=ReadingStatus.PENDING
){
    val accent=when(status){
        ReadingStatus.EXTRACTED->Color(0xFF267B63)
        ReadingStatus.REVIEW->Color(0xFF84571E)
        ReadingStatus.READING->Brand.Crimson
        ReadingStatus.PENDING->Brand.Muted
    }
    val statusIcon=when(status){
        ReadingStatus.EXTRACTED->Icons.Outlined.CheckCircle
        ReadingStatus.REVIEW->Icons.Outlined.ErrorOutline
        ReadingStatus.READING->Icons.Outlined.HourglassTop
        ReadingStatus.PENDING->Icons.Outlined.Schedule
    }
    Surface(shape=RoundedCornerShape(16.dp),color=Color.White,border=BorderStroke(1.dp,CardBorder)){
        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(11.dp)){
            Surface(shape=CircleShape,color=Color(0xFFF3F4F6)){
                Icon(icon,null,Modifier.padding(10.dp).size(23.dp),tint=accent)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text(heading,fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=Brand.Ink)
                Text(detail,fontSize=12.sp,color=Brand.Muted,lineHeight=19.sp)
            }
            Icon(statusIcon,null,tint=accent,modifier=Modifier.size(19.dp))
        }
    }
}

internal fun documentFamilyLabel(kind:String):Int=when(kind){
    "OWNERSHIP_CERTIFICATE"->R.string.smart_kind_title
    "SHARED_OWNERSHIP_CERTIFICATE"->R.string.smart_kind_shared
    "SALE_DEED"->R.string.smart_kind_sale
    "TRANSFER_CARD"->R.string.smart_kind_transfer
    "POSSESSION_REPORT"->R.string.smart_kind_possession
    "FINANCIAL_RECEIPT"->R.string.smart_kind_quittance
    "PROPERTY_SERVICE_RECEIPT"->R.string.smart_kind_service
    else->R.string.smart_kind_unknown
}
internal fun familyFactLabel(key:String):Int=when(key){
    "registerReference"->R.string.smart_fact_register
    "parentRegisterReference"->R.string.intake_parent_title
    "registeredPlotNumber","possessedPlotNumber"->R.string.intake_plot_number
    "propertyGovernorate"->R.string.intake_governorate
    "propertyDelegation"->R.string.intake_delegation
    "propertyImada"->R.string.intake_imada
    "registeredAreaM2"->R.string.smart_fact_registered_area
    "soldAreaM2"->R.string.smart_fact_sold_area
    "possessedAreaM2"->R.string.smart_fact_possessed_area
    "transferredAreaM2"->R.string.smart_fact_transferred_area
    "soldPlotNumber"->R.string.smart_fact_sold_plot
    else->R.string.smart_fact_other
}

private fun unifiedRoleLabel(role:String):Int=when(role){
    "REGISTERED_HOLDER"->R.string.unified_role_holder
    "SHARED_RIGHTS_HOLDER"->R.string.unified_role_shared
    "BUYER"->R.string.unified_role_buyer
    "SELLER"->R.string.unified_role_seller
    "TRANSFEREE"->R.string.unified_role_transferee
    "TRANSFEROR"->R.string.unified_role_transferor
    "SERVICE_APPLICANT"->R.string.unified_role_applicant
    "POSSESSION_REQUESTER"->R.string.unified_role_possessor
    "PAYMENT_PAYER"->R.string.unified_role_payer
    else->R.string.unified_role_other
}
private fun unifiedRoleAssessmentLabel(assessment:String):Int=when(assessment){
    "PRELIMINARY_TEXT_AGREEMENT"->R.string.unified_role_match
    "TEXT_DIFFERENCE_NEEDS_REVIEW"->R.string.unified_role_difference
    else->R.string.unified_role_inconclusive
}
