package tn.malak.app.ui

import android.content.Context
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
    "IDENTITY_REVERSE_MISSING"->R.string.pair_reason_missing_back
    "OFFICIAL_CPF_AND_DOCUMENT_AUTHENTICITY_NOT_CHECKED"->R.string.pair_reason_not_official
    else->R.string.pair_reason_other
}

private fun readIdentityChoice(context:Context,uri:Uri,isPdf:Boolean):ChosenDocument? {
    val mime=context.contentResolver.getType(uri)?:return null
    if(if(isPdf)mime!="application/pdf" else mime !in setOf("image/jpeg","image/png"))
        return null
    var label=""
    var length=0L
    context.contentResolver.query(
        uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null
    )?.use { cursor->
        if(cursor.moveToFirst()) {
            val nameIndex=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex=cursor.getColumnIndex(OpenableColumns.SIZE)
            if(nameIndex>=0&&!cursor.isNull(nameIndex))
                label=cursor.getString(nameIndex).take(90)
            if(sizeIndex>=0&&!cursor.isNull(sizeIndex))
                length=cursor.getLong(sizeIndex)
        }
    }
    if(length !in 20..MAX_DOC_BYTES)return null
    return ChosenDocument(uri,label.ifBlank {uri.lastPathSegment?.take(50)?:"Document"},length,mime)
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
 * Selected documents are held in memory only and never uploaded by this screen.
 */
@Composable
fun SellerOcrJourney(onBack:()->Unit, draftScope:String?=null) {
    val context=LocalContext.current
    val ownerSession=LocalOwnerEvidenceSession.current
    val authenticatedOwnerSession=LocalAuthenticatedOwnerSession.current
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
    var uploadConsent by remember(draftFile) {mutableStateOf(false)}
    var step by rememberSaveable(draftFile) {mutableIntStateOf(0)}
    var name by rememberSaveable(draftFile) {mutableStateOf(prefs.getString("title","") ?: "")}
    var region by rememberSaveable(draftFile) {mutableStateOf(prefs.getString("region","") ?: "")}
    var area by rememberSaveable(draftFile) {mutableStateOf(prefs.getString("area","") ?: "")}
    var price by rememberSaveable(draftFile) {mutableStateOf(prefs.getString("price","") ?: "")}
    var capacity by rememberSaveable(draftFile) {mutableStateOf(prefs.getString("capacity","owner") ?: "owner")}
    // Generate the case request key only when the user submits. Persist the
    // payload fingerprint + opaque key BEFORE the first network request, so an
    // unknown server response followed by app restart cannot create a new case.
    var showInvalid by rememberSaveable(draftFile) {mutableStateOf(false)}
    var saveMessage by remember(draftFile) {mutableIntStateOf(0)}
    var pickMessage by remember(draftFile) {mutableIntStateOf(0)}
    val files=remember(draftFile) {mutableStateListOf<ChosenDocument>()}
    val identityFiles=remember(draftFile) {mutableStateListOf<ChosenDocument>()}
    var identityMessage by remember(draftFile) {mutableIntStateOf(0)}
    // Never let a stale comparison be displayed after selecting other documents,
    // navigation, account or case changes. Private report is memory-only.
    LaunchedEffect(draftFile,files.toList(),identityFiles.toList(),ownerSession,authenticatedOwnerSession,
        name,region,area,price,capacity) {
        requestEpoch++
        requestJob?.cancel()
        provisionedCase=null
        pairReport=null
        pairProgress=null
        pairError=0
        uploadConsent=false
    }
    DisposableEffect(Unit) {
        onDispose {
            requestEpoch++
            requestJob?.cancel()
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

    // Explicitly bind recto to page 1 and verso to page 2. An unordered
    // multi-file picker could silently swap sides and mislabel OCR evidence.
    // None of these URI references are persisted to device preferences.
    val pickIdentityPdf=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->
        if(uri!=null) {
            val chosen=runCatching{readIdentityChoice(context,uri,true)}.getOrNull()
            if(chosen==null)identityMessage=R.string.identity_file_rejected
            else {
                identityFiles.clear()
                identityFiles.add(chosen)
                identityMessage=R.string.file_selected
            }
        }
    }
    val pickIdentityFront=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->
        if(uri!=null) {
            val chosen=runCatching{readIdentityChoice(context,uri,false)}.getOrNull()
            if(chosen==null)identityMessage=R.string.identity_file_rejected
            else {
                // New front invalidates any old back; user must pair them again.
                identityFiles.clear()
                identityFiles.add(chosen)
                identityMessage=R.string.file_selected
            }
        }
    }
    val pickIdentityBack=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri->
        if(uri!=null) {
            val chosen=runCatching{readIdentityChoice(context,uri,false)}.getOrNull()
            if(chosen==null||identityFiles.size!=1||
               identityFiles[0].mime=="application/pdf"||
               chosen.uri==identityFiles[0].uri)
                identityMessage=R.string.identity_file_rejected
            else {
                identityFiles.add(chosen)
                identityMessage=R.string.file_selected
            }
        }
    }
    // The selected PDF must be checked for both faces by the future private OCR service.
    val identitySelected=(identityFiles.size==1&&identityFiles[0].mime=="application/pdf") ||
        (identityFiles.size==2&&identityFiles.none{it.mime=="application/pdf"})
    // Tunisian/French comma is normalized only for numeric validation and API.
    val normalizedArea=area.replace(',', '.')
    val validArea=normalizedArea.matches(ValidLandArea)
    val validPrice=price.matches(ValidLandPrice)
    val validFirst=name.trim().length in 2..100&&region.trim().length in 2..100&&validArea&&validPrice
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
    val stepLabels=listOf(R.string.step_property,R.string.step_capacity,R.string.step_certificate,
        R.string.step_identity,R.string.step_summary)

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
                    SectionHeading(s(R.string.property_section),s(R.string.property_section_hint))
                    OutlinedTextField(name,{name=it.take(100);saveMessage=0},
                        label={Text(s(R.string.property_name))}, modifier=Modifier.fillMaxWidth(),singleLine=true,
                        isError=showInvalid&&name.trim().length<2,
                        supportingText=if(showInvalid&&name.trim().length<2){{Text(s(R.string.field_required))}}else null)
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
                }
                1 -> {
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
                2 -> {
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
                }
                3 -> {
                    SectionHeading(s(R.string.identity_section),s(R.string.identity_hint))
                    Surface(color=Color.White,shape=RoundedCornerShape(21.dp),border=BorderStroke(1.dp,CardBorder)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.Badge,null,Modifier.size(34.dp),tint=Brand.Crimson)
                            Text(s(R.string.identity_two_sides),color=Brand.Ink,fontSize=13.sp)
                            OutlinedButton(onClick={pickIdentityPdf.launch(arrayOf("application/pdf"))},
                                modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(16.dp)) {
                                Icon(Icons.Outlined.Description,null,Modifier.size(21.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(s(R.string.pick_identity_pdf))
                            }
                            OutlinedButton(onClick={pickIdentityFront.launch(arrayOf("image/jpeg","image/png"))},
                                modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(16.dp)) {
                                Icon(Icons.Outlined.Badge,null,Modifier.size(21.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(s(R.string.pick_identity_front))
                            }
                            OutlinedButton(
                                onClick={pickIdentityBack.launch(arrayOf("image/jpeg","image/png"))},
                                enabled=identityFiles.size==1&&identityFiles[0].mime!="application/pdf",
                                modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),
                                shape=RoundedCornerShape(16.dp)
                            ) {
                                Icon(Icons.Outlined.Badge,null,Modifier.size(21.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(s(R.string.pick_identity_back))
                            }
                        }
                    }
                    if(identityMessage!=0)Text(s(identityMessage),color=Brand.Muted,fontSize=12.sp)
                    if(identityFiles.isEmpty())PlainNotice(Icons.Outlined.Badge,s(R.string.identity_none))
                    else {
                        Text(s(R.string.identity_selected),color=Brand.Ink,fontSize=15.sp,fontWeight=FontWeight.SemiBold)
                        identityFiles.toList().forEachIndexed { index,doc ->
                            Surface(shape=RoundedCornerShape(16.dp),color=Color.White,border=BorderStroke(1.dp,CardBorder)) {
                                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=11.dp),
                                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Outlined.InsertDriveFile,null,tint=Brand.Crimson,modifier=Modifier.size(23.dp))
                                    val faceLabel=when {
                                        doc.mime=="application/pdf"->R.string.pick_identity_pdf
                                        index==0->R.string.pick_identity_front
                                        else->R.string.pick_identity_back
                                    }
                                    Text("${s(faceLabel)}: ${doc.displayName}",Modifier.weight(1f),
                                        color=Brand.Ink,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=13.sp)
                                    IconButton(onClick={identityFiles.clear();identityMessage=0},modifier=Modifier.size(48.dp)) {
                                        Icon(Icons.Outlined.DeleteOutline,s(R.string.remove),tint=Brand.Muted)
                                    }
                                }
                            }
                        }
                    }
                    if(!identitySelected)PlainNotice(Icons.Outlined.Info,s(R.string.identity_required))
                    PlainNotice(Icons.Outlined.Lock,s(R.string.identity_privacy))
                }
                else -> {
                    SectionHeading(s(R.string.summary_title),s(R.string.summary_hint))
                    SummaryFact(s(R.string.summary_property),name.ifBlank {s(R.string.no_value)})
                    SummaryFact(s(R.string.summary_region),region.ifBlank {s(R.string.no_value)})
                    SummaryFact(s(R.string.summary_area),"$area m²")
                    SummaryFact(s(R.string.summary_price),"$price TND")
                    SummaryFact(s(R.string.summary_capacity),s(Capacities.firstOrNull {it.code==capacity}?.label ?: R.string.capacity_owner))
                    SummaryFact(s(R.string.summary_docs),if(files.isNotEmpty())"${files.size}" else s(R.string.not_uploaded))
                    SummaryFact(s(R.string.summary_identity),if(identitySelected)s(R.string.identity_selected) else s(R.string.not_uploaded))
                    SectionHeading(s(R.string.review_status_title),
                        s(if(pairReport==null)R.string.review_status_intro else R.string.pair_review_received))
                    VerificationStage(Icons.Outlined.Description,s(R.string.ocr_status),
                        s(when {
                            pairReport?.certificateName!=null->R.string.pair_name_extracted
                            pairReport!=null->R.string.pair_name_unreadable
                            pairProgress!=null->R.string.pair_reading_in_progress
                            else->R.string.ocr_pending
                        }),
                        when {
                            pairReport?.certificateName!=null->ReadingStatus.EXTRACTED
                            pairReport!=null->ReadingStatus.REVIEW
                            pairProgress!=null->ReadingStatus.READING
                            else->ReadingStatus.PENDING
                        })
                    VerificationStage(Icons.Outlined.Badge,s(R.string.identity_ocr_status),
                        s(when {
                            pairReport?.identityName!=null->R.string.pair_name_extracted
                            pairReport!=null->R.string.pair_name_unreadable
                            pairProgress!=null->R.string.pair_reading_in_progress
                            else->R.string.identity_ocr_pending
                        }),
                        when {
                            pairReport?.identityName!=null->ReadingStatus.EXTRACTED
                            pairReport!=null->ReadingStatus.REVIEW
                            pairProgress!=null->ReadingStatus.READING
                            else->ReadingStatus.PENDING
                        })
                    VerificationStage(Icons.Outlined.CompareArrows,s(R.string.match_status),
                        s(when(pairReport?.assessment){
                            "PRELIMINARY_TEXT_AGREEMENT"->R.string.pair_agreement
                            "TEXT_DIFFERENCE_NEEDS_REVIEW"->R.string.pair_difference
                            "INSUFFICIENT_OR_AMBIGUOUS"->R.string.pair_uncertain
                            else->R.string.match_pending
                        }),
                        when(pairReport?.assessment){
                            "PRELIMINARY_TEXT_AGREEMENT"->ReadingStatus.EXTRACTED
                            "TEXT_DIFFERENCE_NEEDS_REVIEW",
                            "INSUFFICIENT_OR_AMBIGUOUS"->ReadingStatus.REVIEW
                            else->if(pairProgress!=null)ReadingStatus.READING else ReadingStatus.PENDING
                        })
                    if(pairReport!=null) {
                        val result=pairReport!!
                        SummaryFact(s(R.string.pair_certificate_pages),result.certificatePages.toString())
                        SummaryFact(s(R.string.pair_identity_pages),result.identityPages.toString())
                        SummaryFact(s(R.string.pair_certificate_owner),
                            result.certificateName?.value?:s(R.string.pair_unreadable))
                        result.certificateName?.let { evidence->
                            val source=s(if(evidence.source=="pdf-text")
                                R.string.pair_source_pdf else R.string.pair_source_ocr)
                            val score=evidence.confidence?.let {
                                " · ${s(R.string.pair_confidence)}: ${it}%"
                            }?:""
                            Text("${s(R.string.pair_source_page)} ${evidence.page} · $source$score",
                                color=Brand.Muted,fontSize=12.sp)
                        }
                        SummaryFact(s(R.string.pair_identity_owner),
                            result.identityName?.value?:s(R.string.pair_unreadable))
                        result.identityName?.let { evidence->
                            val source=s(if(evidence.source=="pdf-text")
                                R.string.pair_source_pdf else R.string.pair_source_ocr)
                            val score=evidence.confidence?.let {
                                " · ${s(R.string.pair_confidence)}: ${it}%"
                            }?:""
                            Text("${s(R.string.pair_source_page)} ${evidence.page} · $source$score",
                                color=Brand.Muted,fontSize=12.sp)
                        }
                        SummaryFact(s(R.string.pair_cin_label),
                            s(when(result.cinStatus) {
                                "SAME_NUMBER_UNVERIFIED"->R.string.pair_cin_equal
                                "NUMBER_DIFFERS_NEEDS_REVIEW"->R.string.pair_cin_diff
                                "AMBIGUOUS_OR_LOW_CONFIDENCE"->R.string.pair_cin_uncertain
                                else->R.string.pair_cin_missing
                            }))
                        SummaryFact(s(R.string.pair_identity_coverage),
                            s(when(result.identityCoverage){
                                "MISSING_REVERSE"->R.string.pair_reverse_missing
                                else->R.string.pair_reverse_unconfirmed
                            }))
                        if(result.reasons.isNotEmpty()) {
                            Text(s(R.string.pair_reasons_title),color=Brand.Ink,
                                fontSize=15.sp,fontWeight=FontWeight.SemiBold)
                            result.reasons.distinct().forEach { code->
                                PlainNotice(Icons.Outlined.Info,s(pairReasonText(code)))
                            }
                        }
                        PlainNotice(Icons.Outlined.Info,s(R.string.pair_preliminary_only))
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
                    if(ownerSession==null&&authenticatedOwnerSession==null) {
                        PlainNotice(Icons.Outlined.Lock,s(R.string.pair_not_connected))
                    }else{
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(checked=uploadConsent,onCheckedChange={uploadConsent=it},
                                enabled=pairProgress==null)
                            Text(s(R.string.pair_upload_consent),Modifier.weight(1f),
                                fontSize=12.sp,color=Brand.Ink,lineHeight=19.sp)
                        }
                        Button(onClick={
                            if(pairProgress!=null||!uploadConsent||files.isEmpty()||!identitySelected)
                                return@Button
                            val cert=files.toList().map{
                                PairEvidenceFile(it.uri,it.length,it.mime,it.uploadKey)
                            }
                            val idDocs=identityFiles.toList().map{
                                PairEvidenceFile(it.uri,it.length,it.mime,it.uploadKey)
                            }
                            val caseRequestKey=try {
                                val parts=listOf(name.trim(),region.trim(),normalizedArea,price,capacity)
                                val serialized=parts.joinToString(separator="") { "${it.length}:${it}" }
                                val fingerprint=java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(serialized.toByteArray(Charsets.UTF_8))
                                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                                val saved=prefs.getString("pending_case_key",null)
                                val key=if(prefs.getString("pending_case_fingerprint",null)==fingerprint &&
                                    saved!=null && Regex("^[0-9a-fA-F-]{36}$").matches(saved)) saved
                                    else UUID.randomUUID().toString()
                                // A failed disk write means we must not risk a duplicate
                                // case on retry after process death.
                                val stored=prefs.edit()
                                    .putString("pending_case_fingerprint",fingerprint)
                                    .putString("pending_case_key",key)
                                    .putString("title",name.trim())
                                    .putString("region",region.trim())
                                    .putString("area",area)
                                    .putString("price",price)
                                    .putString("capacity",capacity)
                                    .commit()
                                if(!stored)throw IllegalStateException("CASE_INTENT_NOT_DURABLE")
                                key
                            }catch (_:Exception) {
                                pairError=R.string.pair_request_failed
                                return@Button
                            }
                            pairError=0
                            pairReport=null
                            pairProgress=PairProgress.CASE_PROVISION
                            val thisRequest=++requestEpoch
                            requestJob=scope.launch {
                                try {
                                    // The trusted sign-in identity first provisions a real,
                                    // consented case; an absent login never invents a case.
                                    val activeCase=ownerSession?:provisionedCase?:
                                        provisionOwnerEvidenceCase(
                                            authenticatedOwnerSession?:throw OwnerPairException(401),
                                            NewOwnerCaseDraft(caseRequestKey,name.trim(),region.trim(),
                                                normalizedArea,price,capacity)
                                        ).also{if(requestEpoch==thisRequest)provisionedCase=it}
                                    val result=compareOwnerCertificateAndIdentity(
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
                        },enabled=uploadConsent&&pairProgress==null&&files.isNotEmpty()&&identitySelected,
                            modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),
                            colors=ButtonDefaults.buttonColors(containerColor=Brand.Crimson),
                            shape=RoundedCornerShape(16.dp)) {
                            Icon(Icons.Outlined.FactCheck,null)
                            Spacer(Modifier.width(8.dp))
                            Text(s(R.string.pair_start))
                        }
                    }
                    VerificationStage(Icons.Outlined.Gavel,s(R.string.official_status),s(R.string.legal_pending))
                    PlainNotice(Icons.Outlined.Info,s(R.string.never_verified))
                    OutlinedButton(onClick={step=0;showInvalid=false},modifier=Modifier.fillMaxWidth().heightIn(min=51.dp)) {
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
            if(step==4) Text(s(R.string.draft_local),color=Brand.Muted,fontSize=12.sp)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Button(onClick={
                if(step==0&&!validFirst)showInvalid=true
                else if(step==2&&files.isEmpty()){pickMessage=R.string.certificate_required}
                else if(step==3&&!identitySelected){identityMessage=R.string.identity_required}
                else if(step<4){step++;showInvalid=false;saveMessage=0}
                else saveLocalDraft()
            }, modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),
                shape=RoundedCornerShape(17.dp),colors=ButtonDefaults.buttonColors(containerColor=Brand.Crimson)) {
                Text(s(if(step==4)R.string.save_draft else R.string.continue_label),fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                Spacer(Modifier.width(9.dp))
                Icon(if(step==4)Icons.Outlined.Save else Icons.AutoMirrored.Filled.ArrowForward,
                    null,Modifier.size(21.dp))
            }
            if(step<4) {
                OutlinedButton(onClick=saveLocalDraft,
                    modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),
                    shape=RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.Save,contentDescription=null,modifier=Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s(R.string.save_draft))
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
