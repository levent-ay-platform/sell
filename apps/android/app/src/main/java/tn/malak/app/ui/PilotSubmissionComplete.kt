package tn.malak.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tn.malak.app.R

@Composable
internal fun PilotSubmissionComplete(reference:String,title:String,onDone:()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.Center,
        horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.CheckCircle,null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(s(R.string.submission_sent),fontSize=24.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(title,fontSize=18.sp)
        Text(String.format(java.util.Locale.ROOT,s(R.string.submission_receipt_reference),reference))
        Spacer(Modifier.height(16.dp))
        Text(s(R.string.submission_receipt_hint))
        Spacer(Modifier.height(24.dp))
        Button(onClick=onDone,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)) {
            Text(s(R.string.my_submissions))
        }
    }
}
