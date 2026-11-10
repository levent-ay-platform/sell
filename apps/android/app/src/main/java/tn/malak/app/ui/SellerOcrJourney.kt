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

private const val MAX_DOC_BYTES = 12L * 1024L * 1024L
private val CardBorder = Color(0xFFE7E8EC)
private data class ChosenDocument(val uri:Uri, val displayName:String, val length:Long)
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
fun SellerOcrJourney(onBack:()->Unit) {
    val context=LocalContext.current
    val prefs=remember(context){context.getSharedPreferences("malak_unpublished_land_draft",Context.MODE_PRIVATE)}
    var step by rememberSaveable {mutableIntStateOf(0)}
    var name by rememberSaveable {mutableStateOf(prefs.getString("title","") ?: "")}
    var region by rememberSaveable {mutableStateOf(prefs.getString("region","") ?: "")}
    var area by rememberSaveable {mutableStateOf(prefs.getString("area","") ?: "")}
    var price by rememberSaveable {mutableStateOf(prefs.getString("price","") ?: "")}
    var capacity by rememberSaveable {mutableStateOf(prefs.getString("capacity","owner") ?: "owner")}
    var showInvalid by rememberSaveable {mutableStateOf(false)}
    var saveMessage by remember {mutableIntStateOf(0)}
    var pickMessage by remember {mutableIntStateOf(0)}
    val files=remember {mutableStateListOf<ChosenDocument>()}

    val pickDocuments=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        var refused=false
        for(uri in uris) {
            if(files.any { it.uri==uri })continue
            if(files.size>=8) { refused=true;continue }
            try {
                var label=""
                var length=0L
                context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{cursor ->
                    if(cursor.moveToFirst()) {
                        val nameIndex=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex=cursor.getColumnIndex(OpenableColumns.SIZE)
                        if(nameIndex>=0&&!cursor.isNull(nameIndex))label=cursor.getString(nameIndex).take(90)
                        if(sizeIndex>=0&&!cursor.isNull(sizeIndex))length=cursor.getLong(sizeIndex)
                    }
                }
                val mime=context.contentResolver.getType(uri)
                if(length !in 20..MAX_DOC_BYTES || mime !in setOf("application/pdf","image/png","image/jpeg","image/tiff")) {
                    refused=true;continue
                }
                files.add(ChosenDocument(uri,label.ifBlank {uri.lastPathSegment?.take(50) ?: "PDF"},length))
            } catch (_:Exception) {refused=true}
        }
        pickMessage=if(refused)R.string.file_rejected else if(files.isNotEmpty())R.string.file_selected else 0
    }
    val validArea=(area.toDoubleOrNull() ?: 0.0).let {it>0 && it<=1_000_000_000 && it.isFinite()}
    val validPrice=(price.toLongOrNull() ?: 0L)>0L
    val validFirst=name.trim().isNotEmpty()&&region.trim().isNotEmpty()&&validArea&&validPrice
    val stepLabels=listOf(R.string.step_property,R.string.step_capacity,R.string.step_certificate,R.string.step_summary)

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
                    Text(s(label),fontSize=11.sp,fontWeight=if(i==step)FontWeight.Bold else FontWeight.Normal,
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
                        isError=showInvalid&&name.isBlank(),
                        supportingText=if(showInvalid&&name.isBlank()){{Text(s(R.string.field_required))}}else null)
                    OutlinedTextField(region,{region=it.take(100);saveMessage=0},
                        label={Text(s(R.string.property_region))},modifier=Modifier.fillMaxWidth(),singleLine=true,
                        isError=showInvalid&&region.isBlank(),
                        supportingText=if(showInvalid&&region.isBlank()){{Text(s(R.string.field_required))}}else null)
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
                            OutlinedButton(onClick={pickDocuments.launch(arrayOf("application/pdf","image/jpeg","image/png","image/tiff"))},
                                modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(16.dp)) {
                                Icon(Icons.Outlined.FolderOpen,null,Modifier.size(21.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(s(R.string.pick_document))
                            }
                        }
                    }
                    if(pickMessage!=0) Text(s(pickMessage),color=Brand.Muted,fontSize=12.sp)
                    if(files.isEmpty()) {
                        PlainNotice(Icons.Outlined.Description,s(R.string.file_none))
                    } else {
                        Text(s(R.string.documents_selected),color=Brand.Ink,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
                        files.toList().forEach {file ->
                            Surface(shape=RoundedCornerShape(16.dp),color=Color.White,border=BorderStroke(1.dp,CardBorder)) {
                                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(start=11.dp,end=6.dp),
                                    verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Outlined.InsertDriveFile,null,tint=Brand.Crimson,modifier=Modifier.size(23.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(file.displayName,color=Brand.Ink,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=13.sp)
                                        Text("${file.length/1024} KB",color=Brand.Muted,fontSize=11.sp)
                                    }
                                    IconButton(onClick={files.remove(file)},modifier=Modifier.size(48.dp)) {
                                        Icon(Icons.Outlined.DeleteOutline,s(R.string.remove),tint=Brand.Muted)
                                    }
                                }
                            }
                        }
                    }
                    PlainNotice(Icons.Outlined.Lock,s(R.string.file_privacy))
                }
                else -> {
                    SectionHeading(s(R.string.summary_title),s(R.string.summary_hint))
                    SummaryFact(s(R.string.summary_property),name.ifBlank {s(R.string.no_value)})
                    SummaryFact(s(R.string.summary_region),region.ifBlank {s(R.string.no_value)})
                    SummaryFact(s(R.string.summary_area),"$area m²")
                    SummaryFact(s(R.string.summary_price),"$price TND")
                    SummaryFact(s(R.string.summary_capacity),s(Capacities.firstOrNull {it.code==capacity}?.label ?: R.string.capacity_owner))
                    SummaryFact(s(R.string.summary_docs),if(files.isNotEmpty())"${files.size}" else s(R.string.not_uploaded))
                    Surface(color=Color(0xFFEAF6F5),shape=RoundedCornerShape(17.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                                Icon(Icons.Outlined.Description,null,tint=Color(0xFF14706E))
                                Text(s(R.string.ocr_status),color=Brand.Ink,fontWeight=FontWeight.Bold)
                            }
                            Text(s(R.string.ocr_pending),color=Brand.Muted,fontSize=13.sp)
                            Text(s(R.string.legal_pending),color=Brand.Muted,fontSize=12.sp)
                        }
                    }
                    PlainNotice(Icons.Outlined.Info,s(R.string.never_verified))
                    OutlinedButton(onClick={step=0;showInvalid=false},modifier=Modifier.fillMaxWidth().heightIn(min=51.dp)) {
                        Icon(Icons.Outlined.Edit,null,Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(s(R.string.edit_property))
                    }
                }
            }
            if(saveMessage!=0) PlainNotice(Icons.Outlined.CheckCircle,s(saveMessage))
            if(step==3) Text(s(R.string.draft_local),color=Brand.Muted,fontSize=12.sp)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal=17.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Button(onClick={
                if(step==0&&!validFirst)showInvalid=true
                else if(step<3){step++;showInvalid=false;saveMessage=0}
                else {
                    try {
                        prefs.edit().putString("title",name.trim()).putString("region",region.trim())
                            .putString("area",area).putString("price",price).putString("capacity",capacity).apply()
                        saveMessage=R.string.draft_saved
                    } catch (_:Exception) {saveMessage=R.string.draft_error}
                }
            }, modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),
                shape=RoundedCornerShape(17.dp),colors=ButtonDefaults.buttonColors(containerColor=Brand.Crimson)) {
                Text(s(if(step==3)R.string.save_draft else R.string.continue_label),fontWeight=FontWeight.SemiBold,fontSize=15.sp)
                Spacer(Modifier.width(9.dp))
                Icon(if(step==3)Icons.Outlined.Save else Icons.AutoMirrored.Filled.ArrowForward,
                    null,Modifier.size(21.dp))
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
