package tn.malak.app.ui
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun JourneySectionHeading(title:String,icon:ImageVector){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Box(Modifier.size(34.dp).background(IntakeTint,RoundedCornerShape(9.dp)),contentAlignment=Alignment.Center){
            Icon(icon,null,tint=Brand.Crimson,modifier=Modifier.size(20.dp))
        }
        Text(title,fontSize=17.sp,fontWeight=FontWeight.Bold,color=Brand.Ink)
    }
}
@Composable
internal fun JourneyPanel(content:@Composable ColumnScope.()->Unit){
    Surface(color=Color.White,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,IntakeLine)){
        Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
    }
}
@Composable
internal fun JourneyToggleRow(title:String,icon:ImageVector,expanded:Boolean,onClick:()->Unit){
    OutlinedButton(onClick=onClick,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),
        shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,IntakeLine)){
        Icon(icon,null,Modifier.size(19.dp));Spacer(Modifier.width(8.dp))
        Text(title,Modifier.weight(1f),color=Brand.Ink)
        Icon(if(expanded)Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,null)
    }
}
@Composable
internal fun JourneyStatusLine(label:String,status:String,added:Boolean){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Icon(if(added)Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,null,
            tint=if(added)IntakeGreen else Brand.Muted,modifier=Modifier.size(19.dp))
        Text(label,Modifier.weight(1f),fontSize=13.sp,color=Brand.Ink)
        Text(status,Modifier.weight(1f),fontSize=12.sp,color=if(added)IntakeGreen else Brand.Muted)
    }
}
