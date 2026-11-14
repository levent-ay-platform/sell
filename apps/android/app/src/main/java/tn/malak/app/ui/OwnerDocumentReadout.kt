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
        ownerExtractedValues(report.detectedKind,report.sourceFacts).forEach{field->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Text(s(familyFactLabel(field.key)),Modifier.weight(1f),fontSize=13.sp)
                Text(field.fact?.value?:s(R.string.owner_value_unavailable),fontWeight=FontWeight.SemiBold,fontSize=13.sp)
            }
            field.fact?.let{
                Text(s(R.string.smart_read_page)+" "+it.page+" · "+s(if(it.status=="LOW_CONFIDENCE")R.string.smart_read_needs_review else R.string.smart_read_source_available),
                    fontSize=11.sp,color=Brand.Muted)
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
