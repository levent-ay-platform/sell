package tn.malak.app.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tn.malak.app.R
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val MAX_DOC_BYTES = 12L * 1024L * 1024L
// Keep form acceptance identical to provisionIndividualOwnerCase's server contract.
private val ValidLandArea=Regex("""^[1-9][0-9]{0,8}(?:\.[0-9]{1,3})?$""")
private val ValidLandPrice=Regex("""^[1-9][0-9]{0,11}$""")
private val CardBorder = Color(0xFFE7E8EC)
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
fun SellerOcrJourney(onBack:()->Unit, draftScope:String?=null) {
    val context=LocalContext.current
    val ownerSession=LocalOwnerEvidenceSession.current
    val authenticatedOwnerSession=LocalAuthenticatedOwnerSession.current
    val pilotOcrSession=LocalPilotOcrSession.current
    // Pilot is a real server OCR session, but never a verified identity
    // or owner/case/publishing credential.
    val canReadPrivateId=ownerSession!=null||authenticatedOwnerSession!=null||
        (pilotOcrSession!=null&&pilotOcrSession.expiresAtMs>System.currentTimeMillis())
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
    var pairProgress by remember(draftFile) {mutableStateOf<PairProgress?>(null)}
    var pairReport by remember(draftFile) {mutableStateOf<CertificateIdentityReview?>(null)}
    var provisionedCase by remember(draftFile) {mutableStateOf<OwnerEvidenceSession?>(null)}
    var pairError by remember(draftFile) {mutableIntStateOf(0)}
    // A dedicated and clearly labelled send action records consent.
    // No redundant checkbox in addition to the explicit action.
    var step by rememberSaveable(draftFile) {mutableIntStateOf(0)}
    var name by rememberSaveable(draftFile) {mutableStateOf(prefs.getString("title","") ?: "")}
    var region by rememberSaveable(draftFile) {mutableStateOf(prefs.getString("region","") ?: "")}
    var area by rememberSaveable(draftFile) {mutableStateOf(prefs.getString("area","") ?: "")}
    var price by rememberSaveable(draftFile) {mutableStateOf(prefs.getString("price","") ?: "")}
    var capacity by rememberSaveable(draftFile) {mutableStateOf(prefs.getString("capacity","owner") ?: "owner")}
    // A public listing title can be composed from facts; the seller only
    // types one when they choose to personalize it.
    var editTitle by rememberSaveable(draftFile) {mutableStateOf(name.isNotBlank())}
    val suggestedTitle=SmartListingAssist.titleSuggestion(
        region,area,s(R.string.smart_land_title_prefix),s(R.string.smart_area_unit))
    val listingTitle=if(name.isBlank()) suggestedTitle.orEmpty() else name.trim()
    // Auto-save only the non-sensitive listing fields, never scans, tokens,
    // CIN, dates of birth, credentials or document URIs.
    LaunchedEffect(draftFile,name,region,area,price,capacity) {
        kotlinx.coroutines.delay(500)
        prefs.edit()
            .putString("title",name.trim())
            .putString("region",region.trim())
            .putString("area",area)
            .putString("price",price)
            .putString("capacity",capacity)
            .apply()
    }
    // Generate the case request key only when the user submits. Persist the
    // payload fingerprint + opaque key BEFORE the first network request, so an
    // unknown server response followed by app restart cannot create a new case.
    var showInvalid by rememberSaveable(draftFile) {mutableStateOf(false)}
    var saveMessage by remember(draftFile) {mutableIntStateOf(0)}
    var pickMessage by remember(draftFile) {mutableIntStateOf(0)}
    val files=remember(draftFile) {mutableStateListOf<ChosenDocument>()}
    // Future listing media is preview-only, never private owner title evidence.
    val listingPhotos=remember(draftFile){mutableStateListOf<DraftListingPhoto>()}
    var listingLatitude by remember(draftFile){mutableStateOf("")}
    var listingLongitude by remember(draftFile){mutableStateOf("")}
    var listingDescription by remember(draftFile){mutableStateOf("")}
    // Never persist private OCR values or even the document URI with the draft.
    var familyReadings by remember(draftFile) {mutableStateOf<List<ReadOcrResult>>(emptyList())}
    var familyReadBusy by remember(draftFile) {mutableStateOf(false)}
    var familyReadError by remember(draftFile) {mutableIntStateOf(0)}
    var familyReadEpoch by remember(draftFile) {mutableIntStateOf(0)}
    var familyReadJob by remember {mutableStateOf<Job?>(null)}
    LaunchedEffect(draftFile,files.toList(),ownerSession,authenticatedOwnerSession) {
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

    var pendingPhoto by remember(draftFile){mutableStateOf<ChosenDocument?>(null)}
    var frontRead by remember(draftFile){
        mutableStateOf<LocalDocumentOcr.FrontScan?>(null)
    }
    var frontReadBusy by remember(draftFile){mutableStateOf(false)}
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
        ownerSession,authenticatedOwnerSession,pilotOcrSession,canReadPrivateId){
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
                    if(pilotOcrSession!=null){
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
    LaunchedEffect(draftFile,files.toList(),identityFiles.toList(),ownerSession,authenticatedOwnerSession) {
        requestEpoch++
        requestJob?.cancel()
        provisionedCase=null
        pairReport=null
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
        }
    }

    // Select one certificate page at a time. Android OpenMultipleDocuments
    // does not promise page order and could silently reverse evidence pages.
    val pickDocuments=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
            var refused=false
            if(files.any{it.uri==uri}||files.size>=8)refused=true
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
                   (mime=="application/pdf"&&files.isNotEmpty()) ||
                   (mime!="application/pdf"&&files.any{it.mime=="application/pdf"})) {
                    refused=true
                } else files.add(ChosenDocument(
                    uri,label.ifBlank{uri.lastPathSegment?.take(50)?:"Document"},length,mime))
            }catch(_:Exception){refused=true}
            pickMessage=if(refused)R.string.file_rejected
                else R.string.file_selected
        }
    }

    // Only a freshly captured private in-memory front JPEG is admissible.
    val identitySelected=identityFiles.size==1&&
        acceptedFrontKey==identityFiles[0].uploadKey&&
        identityFiles[0].mime=="image/jpeg"&&
        PrivateCaptureMemory.isPrivate(identityFiles[0].uri)&&
        PrivateCaptureMemory.length(identityFiles[0].uri)==identityFiles[0].length
    // Front-only secure comparison requires explicit consent and genuine login.
    val identityReadyForRemote=identitySelected
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
    val validFirst=listingTitle.length in 2..100&&region.trim().length in 2..100&&validArea&&validPrice
    // Saving a private local draft must work BEFORE document selection or OCR.
    // Do not pretend success when the device fails to persist these fields.
    val saveLocalDraft:()->Unit={
        saveMessage=try {
            val committed=prefs.edit()
                .putString("title",name.trim())
                .putString("region",region.trim())
                .putString("area",area)
                .putString("price",price)
                .putString("capacity",capacity)
                .commit()
            if(committed)R.string.draft_saved else R.string.draft_error
        }catch (_:Exception){R.string.draft_error}
    }
    val stepLabels=listOf(R.string.smart_step_documents,R.string.step_identity,
        R.string.step_property,R.string.step_summary)
    LaunchedEffect(step) { if(step>3) step=3 }

    if(cameraVisible){
        TunisianIdCamera(onClose={cameraVisible=false},
            onPhoto={uri,length->
                if(cameraVisible){
                    pendingPhoto?.let{PrivateCaptureMemory.discard(it.uri)}
                    pendingPhoto=ChosenDocument(uri,"CIN-camera.jpg",length,"image/jpeg")
                    cameraVisible=false
                    identityMessage=0
                }else PrivateCaptureMemory.discard(uri)
            })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            IconButton(onClick={if(step>0){step--;showInvalid=false}else onBack()}, modifier=Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack,s(R.string.back),tint=Brand.Crimson)
            }
            Column(Modifier.weight(1f)) {
                Text(s(R.string.seller_title),fontSize=21.sp,fontWeight=FontWeight.Bold,color=Brand.Ink)
                Text(s(R.string.draft_label),fontSize=12.sp,color=Brand.Muted)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            stepLabels.forEachIndexed { i,label ->
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.fillMaxWidth().height(5.dp).background(
                        if(i<=step)Brand.Crimson else Color(0xFFE5E8EE),CircleShape))
                    Text(s(label),fontSize=10.sp,fontWeight=if(i==step)FontWeight.Bold else FontWeight.Normal,
                        color=if(i==step)Brand.Crimson else Brand.Muted, maxLines=1)
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=17.dp,vertical=16.dp),
            verticalArrangement=Arrangement.spacedBy(15.dp)) {
            when(step) {
                0 -> {
                    SectionHeading(s(R.string.document_section),s(R.string.document_hint))
                    Surface(color=Color.White,shape=RoundedCornerShape(21.dp),border=BorderStroke(1.dp,CardBorder)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Description,null,Modifier.size(34.dp),tint=Brand.Crimson)
                            Text(s(R.string.file_rules),color=Brand.Muted,fontSize=12.sp)
                            Text(s(R.string.certificate_page_order_hint),color=Brand.Muted,
                                fontSize=12.sp,lineHeight=18.sp)
                            OutlinedButton(onClick={pickDocuments.launch(
                                if(files.isEmpty())arrayOf("application/pdf","image/jpeg","image/png","image/tiff")
                                else arrayOf("image/jpeg","image/png","image/tiff"))},
                                enabled=files.isEmpty()||
                                    (files.size<8&&files.none{it.mime=="application/pdf"}),
                                modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(16.dp)) {
                                Icon(Icons.Outlined.FolderOpen,null,Modifier.size(21.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(s(if(files.isEmpty())R.string.pick_document
                                    else R.string.certificate_add_next_page))
                            }
                        }
                    }
                    if(pickMessage!=0) Text(s(pickMessage),color=Brand.Muted,fontSize=12.sp)
                    if(files.isEmpty()) {
                        PlainNotice(Icons.Outlined.Description,s(R.string.file_none))
                    } else {
                        Text(s(R.string.documents_selected),color=Brand.Ink,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                        files.toList().forEachIndexed { index,file ->
                            Surface(shape=RoundedCornerShape(16.dp),color=Color.White,border=BorderStroke(1.dp,CardBorder)) {
                                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(start=11.dp,end=6.dp),
                                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Outlined.InsertDriveFile,null,tint=Brand.Crimson,modifier=Modifier.size(23.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(if(file.mime=="application/pdf")
                                            s(R.string.certificate_pdf_label)
                                            else "${s(R.string.certificate_page_label)} ${index+1}",
                                            color=Brand.Ink,fontWeight=FontWeight.SemiBold,fontSize=12.sp)
                                        Text("${file.displayName} · ${file.length/1024} KB",
                                            color=Brand.Muted,maxLines=1,
                                            overflow=TextOverflow.Ellipsis,fontSize=11.sp)
                                    }
                                    IconButton(onClick={
                                        // A later image's apparent page number must not change
                                        // silently after deleting an earlier page.
                                        if(index<files.size&&files[index].uri==file.uri){
                                            val following=files.size-index-1
                                            while(files.size>index)files.removeAt(files.lastIndex)
                                            pickMessage=if(following>0)
                                                R.string.certificate_following_pages_removed else 0
                                        }
                                    },modifier=Modifier.size(48.dp)) {
                                        Icon(Icons.Outlined.DeleteOutline,s(R.string.remove),tint=Brand.Muted)
                                    }
                                }
                            }
                        }
                    }
                    PlainNotice(Icons.Outlined.Lock,s(R.string.file_privacy))
                    PlainNotice(Icons.Outlined.Info,s(R.string.smart_document_first_hint))
                }
                1 -> {
                    SectionHeading(s(R.string.identity_section),s(R.string.identity_hint))
                    if(pendingPhoto==null&&!identitySelected){
                        Surface(color=Color.White,
                            shape=RoundedCornerShape(20.dp),
                            border=BorderStroke(1.dp,CardBorder)){
                            Column(Modifier.fillMaxWidth().padding(18.dp),
                                verticalArrangement=Arrangement.spacedBy(13.dp)){
                                Button(onClick={cameraVisible=true},
                                    modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),
                                    shape=RoundedCornerShape(16.dp),
                                    colors=ButtonDefaults.buttonColors(containerColor=Brand.Crimson)){
                                    Icon(Icons.Outlined.CameraAlt,null,Modifier.size(22.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(s(R.string.camera_front_action),fontSize=15.sp)
                                }
                                Text(s(R.string.camera_capture_disclosure),
                                    color=Brand.Muted,fontSize=12.sp,lineHeight=19.sp)
                            }
                        }
                    }
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
                                    scan?.surname!=null&&scan.givenName!=null&&
                                        scan.cinFull!=null&&scan.birthDate!=null->
                                        R.string.camera_review_ready
                                    else->R.string.camera_review_manual
                                }
                                Text(s(message),color=Brand.Muted,
                                    fontSize=12.sp,lineHeight=19.sp)
                            }
                        }
                    }else if(identitySelected){
                        Surface(color=Color(0xFFE9F6F1),
                            shape=RoundedCornerShape(17.dp)){
                            Row(Modifier.fillMaxWidth().padding(15.dp),
                                verticalAlignment=Alignment.CenterVertically,
                                horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                Icon(Icons.Outlined.CheckCircle,null,
                                    tint=Color(0xFF178063))
                                Text(s(R.string.camera_photo_accepted),
                                    Modifier.weight(1f),color=Brand.Ink,
                                    fontWeight=FontWeight.SemiBold)
                                TextButton(onClick={cameraVisible=true}){
                                    Text(s(R.string.camera_review_retake))
                                }
                            }
                        }
                    }
                    if(identityMessage!=0) PlainNotice(Icons.Outlined.Info,s(identityMessage))
                }
                2 -> {
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
                    VerificationStage(Icons.Outlined.CompareArrows,
                        s(R.string.match_status),
                        s(when(pairReport?.unifiedAssessment?:pairReport?.assessment){
                            "ROLE_TEXT_MATCH_ONLY"->R.string.unified_role_match
                            "ROLE_TEXT_DIFFERENCE"->R.string.unified_role_difference
                            "ROLE_AMBIGUOUS"->R.string.unified_role_ambiguous
                            "INSUFFICIENT_EVIDENCE"->R.string.unified_role_inconclusive
                            "PRELIMINARY_TEXT_AGREEMENT"->R.string.pair_agreement
                            "TEXT_DIFFERENCE_NEEDS_REVIEW"->R.string.pair_difference
                            "INSUFFICIENT_OR_AMBIGUOUS"->R.string.pair_uncertain
                            else->R.string.match_pending
                        }),
                        when(pairReport?.unifiedAssessment?:pairReport?.assessment){
                            "ROLE_TEXT_MATCH_ONLY","ROLE_TEXT_DIFFERENCE",
                            "ROLE_AMBIGUOUS","INSUFFICIENT_EVIDENCE"->ReadingStatus.REVIEW
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
                                if(result.unifiedAssessment!=null&&
                                    result.detectedKind!="OWNERSHIP_CERTIFICATE"){
                                    OcrDataRow(s(R.string.unified_document_kind),
                                        s(documentFamilyLabel(result.detectedKind?:"UNKNOWN")))
                                    result.unifiedRoles.forEach{role->
                                        HorizontalDivider(color=CardBorder)
                                        OcrDataRow(s(unifiedRoleLabel(role.role)),
                                            s(unifiedRoleAssessmentLabel(role.assessment)))
                                    }
                                    result.matchedRightsHolder?.let{matched->
                                        HorizontalDivider(color=CardBorder)
                                        OcrDataRow(s(R.string.malak_holder_parts),matched.parts,numeric=true)
                                        HorizontalDivider(color=CardBorder)
                                        OcrDataRow(s(R.string.malak_holder_row),
                                            matched.rowOrdinal.toString(),numeric=true)
                                        HorizontalDivider(color=CardBorder)
                                        OcrDataRow(s(R.string.smart_read_page),
                                            matched.page.toString(),numeric=true)
                                        Text(s(R.string.malak_holder_preliminary),
                                            fontSize=12.sp,color=Brand.Muted,lineHeight=17.sp)
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
                        if(result.unifiedAssessment==null||
                            result.detectedKind=="OWNERSHIP_CERTIFICATE")
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

                    if(ownerSession==null&&authenticatedOwnerSession==null){
                        Text(s(R.string.review_auth_needed),
                            color=Brand.Muted,fontSize=12.sp,lineHeight=18.sp)
                    }else{
                        Text(s(R.string.review_send_disclosure),
                            color=Brand.Muted,fontSize=12.sp,lineHeight=18.sp)
                        Text(s(if(familyReadings.isEmpty())R.string.smart_pair_first_read
                            else R.string.unified_identity_hint),
                            color=Brand.Muted,fontSize=12.sp,lineHeight=18.sp)
                        Button(onClick={
                            if(pairProgress!=null||files.isEmpty()||!identityReadyForRemote||
                                familyReadBusy)
                                return@Button
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
                                    // The trusted sign-in identity first provisions a real,
                                    // consented case; an absent login never invents a case.
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
                        },enabled=pairProgress==null&&files.isNotEmpty()&&identityReadyForRemote&&
                            !familyReadBusy,
                            modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),
                            colors=ButtonDefaults.buttonColors(containerColor=Brand.Crimson),
                            shape=RoundedCornerShape(16.dp)) {
                            Icon(Icons.Outlined.FactCheck,null)
                            Spacer(Modifier.width(8.dp))
                            Text(s(R.string.pair_start))
                        }
                    }
                    if(pairReport==null)
                        PlainNotice(Icons.Outlined.Info,s(R.string.legal_pending))

                    SectionHeading(s(R.string.property_section),s(R.string.property_section_hint))


                    Surface(color=Color(0xFFF2F8F5),shape=RoundedCornerShape(16.dp)){
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
                    OutlinedTextField(area,{area=it.filter { char->char.isDigit()||char=='.'||char==',' }.take(16);saveMessage=0},
                        label={Text(s(R.string.area_m2))},modifier=Modifier.fillMaxWidth(),singleLine=true,
                        textStyle=TextStyle(textDirection=TextDirection.Ltr,textAlign=TextAlign.Start,fontSize=16.sp),
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),
                        isError=showInvalid&&!validArea,
                        supportingText=if(showInvalid&&!validArea){{Text(s(R.string.area_invalid))}}else null)
                    OutlinedTextField(price,{price=it.filter { char->char.isDigit() }.take(15);saveMessage=0},
                        label={Text(s(R.string.price_tnd))},modifier=Modifier.fillMaxWidth(),singleLine=true,
                        textStyle=TextStyle(textDirection=TextDirection.Ltr,textAlign=TextAlign.Start,fontSize=16.sp),
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                        isError=showInvalid&&!validPrice,
                        supportingText=if(showInvalid&&!validPrice){{Text(s(R.string.price_invalid))}}else null)
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
                    HorizontalDivider(color=CardBorder)

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
                            if(ownerSession==null&&authenticatedOwnerSession==null)
                                Text(s(R.string.smart_read_auth_required),
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
                                (ownerSession!=null||authenticatedOwnerSession!=null),
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
                                                        color=Brand.Muted,fontSize=11.sp)
                                                    Text(fact.value?:"",fontWeight=FontWeight.SemiBold,
                                                        fontSize=14.sp,color=Brand.Ink)
                                                    Text("${s(R.string.smart_read_page)} ${fact.page?:1} · "+
                                                        s(if(fact.status=="LOW_CONFIDENCE")
                                                            R.string.smart_read_needs_review
                                                          else R.string.smart_read_source_available),
                                                        color=Brand.Muted,fontSize=11.sp)
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
                                    }else Text(s(R.string.smart_read_needs_review),
                                        color=Brand.Muted,fontSize=12.sp)
                                }
                            }
                        }
                    }
                    SectionHeading(s(R.string.capacity_section),s(R.string.capacity_hint))
                    Capacities.forEach { option ->
                        val active=capacity==option.code
                        Surface(Modifier.fillMaxWidth().clickable {capacity=option.code;saveMessage=0},
                            shape=RoundedCornerShape(17.dp),color=if(active)Brand.Rose else Color.White,
                            border=BorderStroke(1.dp,if(active)Brand.Crimson else CardBorder)) {
                            Row(Modifier.fillMaxWidth().heightIn(min=57.dp).padding(horizontal=15.dp),
                                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(11.dp)) {
                                Icon(if(active)Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                    null,Modifier.size(22.dp),tint=if(active)Brand.Crimson else Brand.Muted)
                                Text(s(option.label),color=Brand.Ink,fontSize=15.sp,
                                    fontWeight=if(active)FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
                else -> {
                    SectionHeading(s(R.string.summary_title),s(R.string.summary_hint))
                    Surface(color=Brand.Rose,shape=RoundedCornerShape(20.dp)){
                        Column(Modifier.fillMaxWidth().padding(17.dp),
                            verticalArrangement=Arrangement.spacedBy(9.dp)){
                            Row(verticalAlignment=Alignment.CenterVertically,
                                horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                Icon(Icons.Outlined.Visibility,null,tint=Brand.Crimson)
                                Text(s(R.string.smart_preview_heading),fontSize=13.sp,
                                    fontWeight=FontWeight.SemiBold,color=Brand.Crimson)
                            }
                            Text(listingTitle.ifBlank{s(R.string.no_value)},
                                fontSize=19.sp,fontWeight=FontWeight.Bold,color=Brand.Ink)
                            Text("${region} · ${area} m² · ${price} TND",
                                fontSize=13.sp,color=Brand.Muted)
                            Text(s(R.string.smart_preview_disclosure),fontSize=12.sp,
                                color=Brand.Muted,lineHeight=19.sp)
                        }
                    }
                    SmartListingMediaStep(
                        photos=listingPhotos,
                        latitude=listingLatitude,onLatitude={listingLatitude=it},
                        longitude=listingLongitude,onLongitude={listingLongitude=it},
                        description=listingDescription,onDescription={listingDescription=it},
                        region=region,area=area,price=price)
                    Surface(shape=RoundedCornerShape(20.dp),color=Color.White,
                        border=BorderStroke(1.dp,CardBorder)){
                        Column(Modifier.fillMaxWidth().padding(14.dp)){
                            OcrDataRow(s(R.string.summary_property),
                                listingTitle.ifBlank{s(R.string.no_value)})
                            HorizontalDivider(color=CardBorder)
                            OcrDataRow(s(R.string.summary_region),
                                region.ifBlank{s(R.string.no_value)})
                            HorizontalDivider(color=CardBorder)
                            OcrDataRow(s(R.string.summary_area),"$area m²",numeric=true)
                            HorizontalDivider(color=CardBorder)
                            OcrDataRow(s(R.string.summary_price),"$price TND",numeric=true)
                            HorizontalDivider(color=CardBorder)
                            OcrDataRow(s(R.string.summary_capacity),
                                s(Capacities.firstOrNull{it.code==capacity}?.label
                                    ?:R.string.capacity_owner))
                            HorizontalDivider(color=CardBorder)
                            OcrDataRow(s(R.string.summary_docs),
                                if(files.isNotEmpty())files.size.toString() else
                                    s(R.string.not_uploaded))
                        }
                    }
                    if(identitySelected){
                        Surface(shape=RoundedCornerShape(20.dp),color=Color.White,
                            border=BorderStroke(1.dp,CardBorder)){
                            Column(Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement=Arrangement.spacedBy(7.dp)){
                                Text(s(R.string.identity_section),fontWeight=FontWeight.Bold,
                                    fontSize=16.sp,color=Brand.Ink)
                                IdFieldsReadout(acceptedRead,
                                    s(R.string.camera_field_missing))
                            }
                        }
                    }

                    OutlinedButton(onClick={step=2;showInvalid=false},
                        modifier=Modifier.fillMaxWidth().heightIn(min=51.dp)) {
                        Icon(Icons.Outlined.Edit,null,Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(s(R.string.edit_property))
                    }

                }
            }
            if(saveMessage!=0) PlainNotice(
                if(saveMessage==R.string.draft_error) Icons.Outlined.ErrorOutline
                else Icons.Outlined.CheckCircle,
                s(saveMessage)
            )
            if(step==3) Text(s(R.string.draft_local),color=Brand.Muted,fontSize=12.sp)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            // Never show "Continue" under a failed scan: keep retake in reach.
            if(step==1&&pendingPhoto!=null){
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    OutlinedButton(onClick={
                        pendingPhoto?.let{PrivateCaptureMemory.discard(it.uri)}
                        pendingPhoto=null
                        frontRead=null
                        cameraVisible=true
                    },modifier=Modifier.weight(1f).heightIn(min=54.dp)){
                        Text(s(R.string.camera_review_retake))
                    }
                    Button(onClick={
                        val pending=pendingPhoto
                        if(pending!=null){
                            identityFiles.forEach{PrivateCaptureMemory.discard(it.uri)}
                            identityFiles.clear()
                            identityFiles.add(pending)
                            acceptedFrontKey=pending.uploadKey
                            acceptedRead=frontRead?.takeIf{it.issue==null}
                            pendingPhoto=null
                            frontRead=null
                            identityMessage=0
                        }
                    },enabled=!frontReadBusy,
                        modifier=Modifier.weight(1f).heightIn(min=54.dp)){
                        Text(s(R.string.camera_review_accept))
                    }
                }
            }else{
            Button(onClick={
                if(step==0&&files.isEmpty()){pickMessage=R.string.certificate_required}
                else if(step==2&&!validFirst)showInvalid=true
                else if(step==1&&(!identitySelected||pendingPhoto!=null)){
                    identityMessage=R.string.identity_required
                }
                else if(step<3){step++;showInvalid=false;saveMessage=0}
                else saveLocalDraft()
            }, modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),
                shape=RoundedCornerShape(17.dp),colors=ButtonDefaults.buttonColors(containerColor=Brand.Crimson)) {
                Text(s(if(step==3)R.string.save_draft else R.string.continue_label),fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                Spacer(Modifier.width(9.dp))
                Icon(if(step==3)Icons.Outlined.Save else Icons.AutoMirrored.Filled.ArrowForward,
                    null,Modifier.size(21.dp))
            }
            if(step<3) {
                Text(s(R.string.smart_autosave_hint),fontSize=11.sp,
                    color=Brand.Muted,lineHeight=17.sp)
            }
            }
        }
    }
}

@Composable
private fun SectionHeading(title:String,description:String) {
    Column(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Text(title,fontSize=21.sp,fontWeight=FontWeight.Bold,color=Brand.Ink)
        Text(description,color=Brand.Muted,fontSize=13.sp,lineHeight=21.sp)
    }
}

@Composable
private fun PlainNotice(icon:androidx.compose.ui.graphics.vector.ImageVector,message:String) {
    Surface(shape=RoundedCornerShape(16.dp),color=Color(0xFFF2F5F7)) {
        Row(Modifier.fillMaxWidth().padding(13.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Icon(icon,null,Modifier.size(22.dp),tint=Color(0xFF26718B))
            Text(message,Modifier.weight(1f),fontSize=12.sp,color=Brand.Ink,lineHeight=19.sp)
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
        ReadingStatus.REVIEW->Color(0xFFAD753A)
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

private fun documentFamilyLabel(kind:String):Int=when(kind){
    "OWNERSHIP_CERTIFICATE"->R.string.smart_kind_title
    "SHARED_OWNERSHIP_CERTIFICATE"->R.string.smart_kind_shared
    "SALE_DEED"->R.string.smart_kind_sale
    "TRANSFER_CARD"->R.string.smart_kind_transfer
    "POSSESSION_REPORT"->R.string.smart_kind_possession
    "FINANCIAL_RECEIPT"->R.string.smart_kind_quittance
    "PROPERTY_SERVICE_RECEIPT"->R.string.smart_kind_service
    else->R.string.smart_kind_unknown
}
private fun familyFactLabel(key:String):Int=when(key){
    "registerReference"->R.string.smart_fact_register
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
