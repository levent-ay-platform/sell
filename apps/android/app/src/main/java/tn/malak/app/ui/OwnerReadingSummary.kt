package tn.malak.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tn.malak.app.R

@Composable
internal fun OwnerReadingSummary(report:CertificateIdentityReview?,scan:LocalDocumentOcr.FrontScan?,
    busy:Boolean,error:Int,details:Boolean,onDetails:()->Unit,onRetry:()->Unit,onPages:()->Unit) {
    Surface(color=if(busy)Brand.Rose else MaterialTheme.colorScheme.surface,
        shape=RoundedCornerShape(22.dp),tonalElevation=2.dp){
        Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Icon(Icons.Outlined.AutoAwesome,null,tint=Brand.Crimson)
            Text(s(when{busy->R.string.owner_reading;report!=null->R.string.owner_reading_ready;else->R.string.owner_reading_retry}),
                fontWeight=FontWeight.Bold,fontSize=20.sp)
            scan?.fullName?.let{Text(it,fontSize=16.sp)}
            if(busy){
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(s(R.string.owner_reading_hint),color=Brand.Muted)
            }else if(report==null){
                Text(s(if(error!=0)error else R.string.owner_reading_hint),color=Brand.Muted)
                Button(onClick=onRetry){Text(s(R.string.owner_retry))}
            }else{
                Text(s(documentFamilyLabel(report.detectedKind?:"UNKNOWN")),color=Brand.Muted)
                if("DOCUMENT_TYPE_NOT_CONFIRMED" in report.reasons)
                    Text(s(R.string.owner_type_review),color=Brand.Muted,fontSize=12.sp)
                val conflict=report.unifiedAssessment in setOf("CONFLICT_REVIEW","ROLE_TEXT_DIFFERENCE")
                Text(s(when{
                    conflict->R.string.owner_match_difference
                    report.unifiedAssessment in setOf("ROLE_TEXT_MATCH_ONLY","NAME_ONLY_REVIEW")->R.string.owner_match_candidate
                    else->R.string.owner_match_review
                }),color=if(conflict)MaterialTheme.colorScheme.error else Brand.Ink)
                report.sourceFacts.firstOrNull{it.key=="registerReference"}?.let{
                    Text(s(R.string.owner_reference_label),fontSize=12.sp,color=Brand.Muted)
                    Text(it.value,fontWeight=FontWeight.SemiBold)
                    if(it.status=="LOW_CONFIDENCE")Text(s(R.string.smart_read_needs_review),color=Brand.Muted,fontSize=12.sp)
                }
                if("CERTIFICATE_PAGES_MISSING" in report.reasons){
                    Text(s(R.string.pair_reason_pages_missing),color=MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick=onPages){Text(s(R.string.owner_review_pages))}
                }
                Text(s(R.string.owner_next_details),color=Brand.Muted)
                TextButton(onClick=onDetails){Text(s(if(details)R.string.owner_hide_details else R.string.owner_show_details))}
            }
        }
    }
}
