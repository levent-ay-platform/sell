package tn.malak.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tn.malak.app.R
import tn.malak.app.ui.explore.AdminContext

@Composable
internal fun OwnerAdminContextPanel(context:AdminContext?,loading:Boolean,hasLocation:Boolean,failed:Boolean,
    arabic:Boolean,onApply:()->Unit){
    JourneySectionHeading(s(R.string.intake_admin_title),Icons.Outlined.LocationOn)
    JourneyPanel{
        when{
            loading->LinearProgressIndicator(Modifier.fillMaxWidth())
            context!=null->{
                JourneyStatusLine(s(R.string.intake_governorate),context.governorate.label(arabic),true)
                JourneyStatusLine(s(R.string.intake_delegation),context.delegation?.label(arabic)?:s(R.string.intake_admin_unresolved),context.delegation!=null)
                JourneyStatusLine(s(R.string.intake_imada),s(R.string.intake_imada_unavailable),false)
                Text(s(R.string.intake_admin_source),fontSize=12.sp,color=Brand.Muted)
                TextButton(onClick=onApply){Text(s(R.string.intake_admin_use_region))}
            }
            else->Text(s(if(!hasLocation)R.string.intake_admin_choose_location else if(failed)R.string.intake_admin_load_failed else R.string.intake_admin_unresolved),
                fontSize=13.sp,color=Brand.Muted)
        }
    }
}

@Composable
internal fun OwnerSourceFactsPanel(facts:List<PilotPropertyFact>,onArea:((String)->Unit)?=null,areaEvidence:PilotPropertyFact?=null){
    if(facts.isEmpty())return
    JourneyPanel{
        Text(s(R.string.intake_doc_facts),fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold,color=Brand.Ink)
        facts.forEach{fact->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Column(Modifier.weight(1f)){
                    Text(s(familyFactLabel(fact.key)),fontSize=12.sp,color=Brand.Muted)
                    Text(fact.value,color=Brand.Ink,fontSize=14.sp)
                }
                Text(s(R.string.smart_read_page)+" "+fact.page+" · "+s(if(fact.status=="CANDIDATE")R.string.smart_read_source_available else R.string.smart_read_needs_review),
                    modifier=Modifier.weight(.8f),fontSize=12.sp,color=Brand.Muted)
            }
        }
        if(areaEvidence!=null&&onArea!=null)TextButton(onClick={onArea(areaEvidence.value)}){
            Text(s(R.string.intake_use_area))
        }
        Text(s(R.string.intake_doc_facts_notice),fontSize=12.sp,color=Brand.Muted)
    }
}
