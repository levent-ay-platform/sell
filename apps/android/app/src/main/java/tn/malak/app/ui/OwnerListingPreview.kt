package tn.malak.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun OwnerListingPreview(title:String,region:String,area:String,price:String,
    description:String,photos:List<DraftListingPhoto>) {
    val context=LocalContext.current
    val first=photos.firstOrNull()
    var image by remember(first?.uri){mutableStateOf<android.graphics.Bitmap?>(null)}
    LaunchedEffect(first?.uri){
        if(first!=null)image=withContext(Dispatchers.IO){runCatching{
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            context.contentResolver.openInputStream(first.uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
            var sample=1
            while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1200)sample*=2
            context.contentResolver.openInputStream(first.uri)?.use{
                BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})
            }
        }.getOrNull()}
    }
    Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface,tonalElevation=2.dp){
        Column(Modifier.fillMaxWidth()){
            image?.let{Image(it.asImageBitmap(),null,Modifier.fillMaxWidth().height(190.dp),contentScale=ContentScale.Crop)}
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
                Text(title,fontSize=22.sp,fontWeight=FontWeight.Bold)
                Text(region,color=Brand.Muted)
                Text("$area m² · $price TND",fontWeight=FontWeight.SemiBold,fontSize=18.sp)
                if(description.isNotBlank())Text(description)
            }
        }
    }
}
