package tn.malak.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tn.malak.app.R

/**
 * Ephemeral pilot screen. It never persists a phone number, calls an API or
 * creates AuthenticatedOwnerSession / OwnerEvidenceSession. The fixed PIN only
 * exists in the debug source set; the release verifier always returns false.
 */
@Composable
internal fun PilotOtpScreen(onEnterPilot:(String)->Unit) {
    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf(false) }
    val phoneReady=phone.matches(Regex("^[2-9][0-9]{7}$"))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal=24.dp, vertical=28.dp),
        horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.spacedBy(18.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        Surface(shape=RoundedCornerShape(20.dp),color=Brand.Rose) {
            Icon(Icons.Outlined.Security,contentDescription=null,
                modifier=Modifier.padding(17.dp).size(38.dp),tint=Brand.Crimson)
        }
        Text(s(R.string.pilot_login_title),fontSize=25.sp,color=Brand.Ink)
        Text(s(R.string.pilot_login_subtitle),color=Brand.Muted,
            fontSize=14.sp,textAlign=TextAlign.Center,lineHeight=22.sp)
        Surface(
            shape=RoundedCornerShape(16.dp),color=Color.White,
            border=BorderStroke(1.dp,Color(0xFFE7E8EC))
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement=Arrangement.spacedBy(14.dp)
            ) {
                if(stage==0) {
                    OutlinedTextField(
                        value=phone,
                        onValueChange={phone=it.filter(Char::isDigit).take(8);error=false},
                        label={Text(s(R.string.pilot_phone_label))},
                        prefix={Text("+216 ")},
                        leadingIcon={Icon(Icons.Outlined.Phone,null)},
                        modifier=Modifier.fillMaxWidth(),singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),
                        textStyle=TextStyle(textDirection=TextDirection.Ltr,
                            textAlign=TextAlign.Start,fontSize=17.sp),
                        isError=error,
                        supportingText={Text(s(if(error)R.string.pilot_phone_invalid
                            else R.string.pilot_phone_hint))}
                    )
                    Button(
                        onClick={if(phoneReady){stage=1;otp="";error=false}else error=true},
                        modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),
                        shape=RoundedCornerShape(14.dp)
                    ){Text(s(R.string.pilot_continue))}
                } else {
                    Text("+216 "+phone,color=Brand.Ink,fontSize=15.sp)
                    OutlinedTextField(
                        value=otp,
                        onValueChange={otp=it.filter(Char::isDigit).take(4);error=false},
                        label={Text(s(R.string.pilot_otp_label))},
                        leadingIcon={Icon(Icons.Outlined.Lock,null)},
                        modifier=Modifier.fillMaxWidth(),singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),
                        textStyle=TextStyle(textDirection=TextDirection.Ltr,
                            textAlign=TextAlign.Start,fontSize=19.sp),
                        isError=error,
                        supportingText={Text(s(if(error)R.string.pilot_otp_invalid
                            else R.string.pilot_otp_hint))}
                    )
                    Button(
                        onClick={
                            if(PilotOtpGate.verify(phone,otp)){
                                onEnterPilot("+216"+phone)
                                otp=""
                            } else error=true
                        },
                        enabled=otp.length==4,
                        modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),
                        shape=RoundedCornerShape(14.dp)
                    ){Text(s(R.string.pilot_enter))}
                    TextButton(onClick={stage=0;otp="";error=false}){
                        Text(s(R.string.pilot_change_phone))
                    }
                }
            }
        }
        Surface(shape=RoundedCornerShape(14.dp),color=Color(0xFFF1F5F7)){
            Text(
                s(R.string.pilot_no_real_identity),
                Modifier.fillMaxWidth().padding(15.dp),
                color=Brand.Muted,fontSize=12.sp,lineHeight=19.sp
            )
        }
    }
}

@Composable
internal fun PilotSessionBar(phone:String,onSignOut:()->Unit){
    Surface(color=Color(0xFFF1F5F7)){
        Row(
            Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=5.dp),
            verticalAlignment=Alignment.CenterVertically
        ){
            Text(s(R.string.pilot_session_badge),color=Brand.Crimson,
                fontSize=12.sp,modifier=Modifier.weight(1f))
            Text("+216 ••••••"+phone.takeLast(2),color=Brand.Muted,fontSize=11.sp)
            TextButton(onClick=onSignOut){Text(s(R.string.pilot_sign_out),fontSize=12.sp)}
        }
    }
}
