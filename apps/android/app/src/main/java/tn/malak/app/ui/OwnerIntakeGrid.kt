package tn.malak.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tn.malak.app.R

internal val IntakeTint = Color(0xFFF8EDF1)
internal val IntakeGreen = Color(0xFF267457)
internal val IntakeLine = Color(0xFFE8E5E6)

@Composable
internal fun IntakeGridCard(
    title:String, icon:ImageVector, required:Boolean, state:String,
    modifier:Modifier=Modifier, ready:Boolean=false, warning:Boolean=false,
    iconTint:Color=Brand.Crimson, iconBackground:Color=IntakeTint,
    uploadEnabled:Boolean=true,captureEnabled:Boolean=true,
    onUpload:()->Unit,onCapture:()->Unit,
    detail:String?=null,onDetail:(()->Unit)?=null
){
    val stateColor=if(ready)IntakeGreen else if(warning)Color(0xFF8E5C20) else Brand.Muted
    Surface(modifier=modifier,shape=RoundedCornerShape(16.dp),color=Color.White,
        border=BorderStroke(1.dp,IntakeLine)){
        Column(Modifier.fillMaxWidth().padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.SpaceBetween){
                Box(Modifier.size(38.dp).background(iconBackground,RoundedCornerShape(11.dp)),
                    contentAlignment=Alignment.Center){
                    Icon(icon,null,tint=iconTint,modifier=Modifier.size(23.dp))
                }
                Text(s(if(required)R.string.intake_required else R.string.intake_optional),
                    color=Brand.Muted,style=MaterialTheme.typography.labelSmall)
            }
            Text(title,color=Brand.Ink,fontSize=14.sp,fontWeight=FontWeight.Bold,
                lineHeight=19.sp,maxLines=2,overflow=TextOverflow.Ellipsis,
                modifier=Modifier.heightIn(min=38.dp))
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
                Box(Modifier.size(6.dp).background(stateColor,CircleShape))
                Text(state,color=stateColor,fontSize=12.sp,lineHeight=16.sp,maxLines=2,
                    overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                IntakeAction(s(R.string.intake_upload),Icons.Outlined.FileUpload,true,uploadEnabled,onUpload,Modifier.weight(1f))
                IntakeAction(s(R.string.intake_take_photo),Icons.Outlined.CameraAlt,false,captureEnabled,onCapture,Modifier.weight(1f))
            }
            if(detail!=null&&onDetail!=null){
                TextButton(onClick=onDetail,modifier=Modifier.fillMaxWidth().heightIn(min=40.dp),
                    contentPadding=PaddingValues(2.dp)){
                    Text(detail,fontSize=12.sp,lineHeight=15.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun IntakeAction(label:String,icon:ImageVector,primary:Boolean,enabled:Boolean,
    onClick:()->Unit,modifier:Modifier){
    OutlinedButton(onClick=onClick,enabled=enabled,modifier=modifier.heightIn(min=44.dp),
        shape=RoundedCornerShape(10.dp),contentPadding=PaddingValues(horizontal=2.dp,vertical=5.dp),
        border=BorderStroke(1.dp,if(primary)IntakeTint else IntakeLine),
        colors=ButtonDefaults.outlinedButtonColors(containerColor=if(primary)IntakeTint else Color.White,
            contentColor=if(primary)Brand.Crimson else Brand.Ink)){
        // Vertical layout avoids clipped translations on narrow handsets.
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(1.dp)){
            Icon(icon,null,Modifier.size(16.dp))
            Text(label,fontSize=12.sp,fontWeight=FontWeight.SemiBold,maxLines=1)
        }
    }
}
