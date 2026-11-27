package tn.malak.app.ui
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tn.malak.app.R

@Composable
internal fun IntakeOptionalDocument(label:Int,name:String?,onCapture:()->Unit,onUpload:()->Unit,onRemove:()->Unit){
    Surface(shape=RoundedCornerShape(18.dp),color=Color.White,border=BorderStroke(1.dp,Brand.Border)){
        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(s(label),fontWeight=FontWeight.SemiBold,color=Brand.Ink)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick=onCapture,modifier=Modifier.weight(1f)){
                    Icon(Icons.Outlined.CameraAlt,null,Modifier.size(18.dp));Spacer(Modifier.width(5.dp));Text(s(R.string.intake_take_photo))
                }
                OutlinedButton(onClick=onUpload,modifier=Modifier.weight(1f)){
                    Icon(Icons.Outlined.FolderOpen,null,Modifier.size(18.dp));Spacer(Modifier.width(5.dp));Text(s(R.string.intake_upload))
                }
            }
            if(name!=null){
                Text(name,style=MaterialTheme.typography.bodySmall,color=Brand.Ink)
                TextButton(onClick=onRemove){Text(s(R.string.remove))}
            }
        }
    }
}
@Composable
internal fun IntakeImagePreview(uri:Uri){
    val resolver=LocalContext.current.contentResolver
    val bmp by produceState<Bitmap?>(null,uri){
        value=withContext(Dispatchers.IO){
            runCatching{
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
                require(bounds.outWidth in 1..30000&&bounds.outHeight in 1..30000)
                val opts=BitmapFactory.Options().apply{
                    inSampleSize=1
                    while(maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize>1200)inSampleSize*=2
                }
                resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,opts)}
            }.getOrNull()
        }
    }
    bmp?.let{Image(it.asImageBitmap(),null,Modifier.fillMaxWidth().heightIn(max=180.dp),contentScale=ContentScale.Fit)}
}
