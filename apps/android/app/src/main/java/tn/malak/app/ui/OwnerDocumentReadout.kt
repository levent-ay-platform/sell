package tn.malak.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import tn.malak.app.R

@Composable
internal fun OwnerDocumentReadout(report:CertificateIdentityReview,onDateRefresh:(()->Unit)?=null,
    dateBusy:Boolean=false,dateError:Int=0) {
    key(report.identityScan?.birthDate){
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Text(s(R.string.owner_extracted_heading),fontWeight=FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth()){
            Text(s(R.string.camera_review_birth),Modifier.weight(1f),fontSize=13.sp)
            Text(report.identityScan?.birthDate?:s(R.string.owner_value_unavailable),fontWeight=FontWeight.SemiBold,fontSize=13.sp)
        }
        if(report.identityScan?.birthDate==null&&onDateRefresh!=null){
            OutlinedButton(onClick=onDateRefresh,enabled=!dateBusy,modifier=Modifier.fillMaxWidth()){
                Text(s(if(dateBusy)R.string.owner_date_reading else R.string.owner_date_refresh),fontSize=12.sp)
            }
        }
        if(dateBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(dateError!=0)Text(s(dateError),color=MaterialTheme.colorScheme.error,fontSize=12.sp)
        if(report.detectedKind in setOf("OWNERSHIP_CERTIFICATE","SHARED_OWNERSHIP_CERTIFICATE")){
            HorizontalDivider()
            Text(s(R.string.owner_share_heading),fontWeight=FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth()){
                Text(s(R.string.owner_share_parts),Modifier.weight(1f),fontSize=13.sp)
                Text(report.matchedRightsHolder?.parts?:s(R.string.owner_value_unavailable),fontSize=13.sp)
            }
            val ratio=ownerSharePercentage(report.matchedRightsHolder?.parts,report.sourceFacts)
            if(ratio!=null){
                Row(Modifier.fillMaxWidth()){
                    Text(s(R.string.owner_share_percentage),Modifier.weight(1f),fontSize=13.sp)
                    Text(ratio+"%",fontSize=13.sp)
                }
            }
            Text(s(R.string.owner_share_warning),fontSize=12.sp,color=Brand.Muted)
        }
        if("CERTIFICATE_PAGE_COUNT_UNREADABLE" in report.reasons){
            Text(s(R.string.owner_page_count_unreadable),fontSize=12.sp,color=Brand.Muted)
        }
        if("CPF_PROPERTY_AREA_CONFLICT" in report.reasons){
            Text(s(R.string.owner_estate_area_conflict),fontSize=12.sp,color=MaterialTheme.colorScheme.error)
        }else if("CPF_PROPERTY_FIELDS_CONFLICT" in report.reasons){
            Text(s(R.string.owner_estate_fields_conflict),fontSize=12.sp,color=MaterialTheme.colorScheme.error)
        }
        report.propertyConflicts.forEach{conflict->
            HorizontalDivider()
            Text(s(familyFactLabel(conflict.key))+" — "+s(R.string.owner_conflicting_readings),
                fontWeight=FontWeight.SemiBold,fontSize=13.sp)
            conflict.alternatives.distinctBy{Triple(it.value,it.page,it.view)}.take(6).forEach{reading->
                Row(Modifier.fillMaxWidth()){
                    Text(s(R.string.smart_read_page)+" "+reading.page+" · "+
                        s(if(reading.view=="PROPERTY_CROP")R.string.owner_crop_reading else R.string.owner_original_reading),
                        Modifier.weight(1f),fontSize=12.sp,color=Brand.Muted)
                    Text(reading.value,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                }
            }
            Text(s(R.string.owner_conflict_review_only),fontSize=12.sp,color=Brand.Muted)
        }
        ownerExtractedValues(report.detectedKind,report.sourceFacts).forEach{field->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Text(s(familyFactLabel(field.key)),Modifier.weight(1f),fontSize=13.sp)
                Text(field.fact?.value?:s(R.string.owner_value_unavailable),fontWeight=FontWeight.SemiBold,fontSize=13.sp)
            }
            field.fact?.let{
                Text(s(R.string.smart_read_page)+" "+it.page+" · "+s(if(it.status=="LOW_CONFIDENCE")R.string.smart_read_needs_review else R.string.smart_read_source_available),
                    fontSize=13.sp,color=Brand.Muted)
            }
        }
    }
    }
}

@Composable
internal fun OwnerStoredDocumentReadout(session:PilotOcrSession,reviewId:String,allowDateRefresh:Boolean){
    val scope=rememberCoroutineScope()
    var report by remember(session,reviewId){mutableStateOf<CertificateIdentityReview?>(null)}
    var loading by remember(session,reviewId){mutableStateOf(true)}
    var busy by remember(session,reviewId){mutableStateOf(false)}
    var error by remember(session,reviewId){mutableIntStateOf(0)}
    LaunchedEffect(session,reviewId){
        try{report=parsePilotComparison(PilotListingClient.call(session,"/api/pilot/reviews/"+reviewId))}
        catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){error=R.string.pair_service_unavailable}
        finally{loading=false}
    }
    if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
    report?.let{value->
        Text(s(documentFamilyLabel(value.detectedKind?:"UNKNOWN")),fontSize=13.sp,color=Brand.Muted)
        OwnerDocumentReadout(value,onDateRefresh=if(allowDateRefresh&&value.identityScan!=null)({
            busy=true;error=0
            scope.launch{
                try{
                    report=parsePilotComparison(PilotListingClient.call(session,
                        "/api/pilot/reviews/"+reviewId+"/identity-date",JSONObject(),privateReadConsent=true))
                    if(report?.identityScan?.birthDate==null)error=R.string.owner_date_not_updated
                }catch(cancel:CancellationException){throw cancel}
                catch(_:Exception){error=R.string.owner_date_not_updated}
                finally{busy=false}
            }
        })else null,dateBusy=busy,dateError=error)
        if("CERTIFICATE_PAGES_MISSING" in value.reasons)Text(s(R.string.pair_reason_pages_missing),color=MaterialTheme.colorScheme.error,fontSize=12.sp)
    }
    if(report==null&&error!=0)Text(s(error),color=MaterialTheme.colorScheme.error)
}
