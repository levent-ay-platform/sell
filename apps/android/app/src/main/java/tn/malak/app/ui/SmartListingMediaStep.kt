package tn.malak.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tn.malak.app.R

/**
 * Only a local ADVERTISEMENT MEDIA DRAFT. Never upload listing images to a
 * title-evidence or identity endpoint, never auto-publish location or photos.
 * The separate tenant-authorized public-media pipeline still needs acceptance.
 */
internal data class DraftListingPhoto(val uri:Uri,val name:String,val length:Long)
private const val MAX_PHOTOS=8
private const val MAX_PHOTO_BYTES=12L*1024L*1024L
private val PHOTO_MIMES=setOf("image/jpeg","image/png","image/webp")

@Composable
internal fun SmartListingMediaStep(
    photos:SnapshotStateList<DraftListingPhoto>,
    latitude:String,onLatitude:(String)->Unit,
    longitude:String,onLongitude:(String)->Unit,
    description:String,onDescription:(String)->Unit,
    region:String,area:String,price:String
) {
    val context=LocalContext.current
    var message by remember {mutableIntStateOf(0)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {chosen->
        var refused=false
        for(uri in chosen) {
            if(photos.size>=MAX_PHOTOS){refused=true;break}
            if(uri.scheme!="content"||photos.any{it.uri==uri}){refused=true;continue}
            try{
                var name=""
                var size=0L
                context.contentResolver.query(uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),
                    null,null,null)?.use{cursor->
                    if(cursor.moveToFirst()){
                        val nameIndex=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex=cursor.getColumnIndex(OpenableColumns.SIZE)
                        if(nameIndex>=0&&!cursor.isNull(nameIndex))
                            name=cursor.getString(nameIndex).take(70)
                        if(sizeIndex>=0&&!cursor.isNull(sizeIndex))
                            size=cursor.getLong(sizeIndex)
                    }
                }
                val mime=context.contentResolver.getType(uri)
                if(size !in 20..MAX_PHOTO_BYTES||mime !in PHOTO_MIMES)refused=true
                else photos.add(DraftListingPhoto(uri,name.ifBlank{"Photo"},size))
            }catch(_:Exception){refused=true}
        }
        message=if(refused)R.string.smart_media_rejected else R.string.smart_media_selected
    }
    val point=SmartListingAssist.coordinates(latitude,longitude)
    Surface(color=Color.White,shape=RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(stringResource(R.string.smart_media_heading),fontSize=17.sp,
                fontWeight=FontWeight.Bold,color=Brand.Ink)
            Text(stringResource(R.string.smart_media_hint),fontSize=12.sp,
                color=Brand.Muted)
            OutlinedButton(onClick={picker.launch(arrayOf("image/jpeg","image/png","image/webp"))},
                enabled=photos.size<MAX_PHOTOS,modifier=Modifier.fillMaxWidth()
                    .heightIn(min=52.dp)) {
                Icon(Icons.Outlined.AddPhotoAlternate,null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.smart_media_pick)+" (${photos.size}/$MAX_PHOTOS)")
            }
            if(message!=0)Text(stringResource(message),fontSize=12.sp,
                color=Brand.Muted)
            photos.toList().forEachIndexed{index,photo->
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    DraftPhotoThumbnail(photo.uri)
                    Column(Modifier.weight(1f)){
                        Text(photo.name,maxLines=1,color=Brand.Ink,
                            fontSize=12.sp,fontWeight=FontWeight.Medium)
                        Text("${photo.length/1024} KB",fontSize=11.sp,
                            color=Brand.Muted)
                    }
                    IconButton(onClick={photos.removeAt(index)}) {
                        Icon(Icons.Outlined.Close,stringResource(R.string.smart_media_remove),
                            tint=Brand.Crimson)
                    }
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.smart_location_heading),
                fontWeight=FontWeight.Bold,color=Brand.Ink)
            Text(stringResource(R.string.smart_location_hint),
                fontSize=12.sp,color=Brand.Muted)
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                OutlinedTextField(latitude,{onLatitude(it.take(14))},
                    label={Text(stringResource(R.string.smart_latitude))},
                    modifier=Modifier.weight(1f),singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                OutlinedTextField(longitude,{onLongitude(it.take(14))},
                    label={Text(stringResource(R.string.smart_longitude))},
                    modifier=Modifier.weight(1f),singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            }
            OutlinedButton(onClick={
                val geo=point ?: return@OutlinedButton
                try {
                    val uri=Uri.parse("geo:${geo.first},${geo.second}?q=${geo.first},${geo.second}")
                    val intent=Intent(Intent.ACTION_VIEW,uri)
                    if(intent.resolveActivity(context.packageManager)==null)
                        message=R.string.smart_location_unavailable
                    else context.startActivity(intent)
                }catch(_:Exception){message=R.string.smart_location_unavailable}
            },enabled=point!=null,modifier=Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.LocationOn,null)
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.smart_location_preview))
            }
            HorizontalDivider()
            Text(stringResource(R.string.smart_description_heading),
                fontWeight=FontWeight.Bold,color=Brand.Ink)
            OutlinedButton(onClick={
                SmartListingAssist.descriptionSuggestion(region,area,price,
                    context.getString(R.string.smart_land_title_prefix),
                    context.getString(R.string.smart_area_unit),
                    context.getString(R.string.smart_description_price_prefix))?.let(onDescription)
            },modifier=Modifier.fillMaxWidth()){
                Text(stringResource(R.string.smart_description_generate))
            }
            OutlinedTextField(description,{onDescription(it.take(650))},
                modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=6,
                label={Text(stringResource(R.string.smart_description_heading))})
            Text(stringResource(R.string.smart_media_private_notice),fontSize=12.sp,
                color=Brand.Muted,lineHeight=18.sp)
        }
    }
}

@Composable
private fun DraftPhotoThumbnail(uri:Uri) {
    val context=LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue=null,uri){
        value=withContext(Dispatchers.IO){
            runCatching{
                context.contentResolver.openInputStream(uri)?.use{stream->
                    BitmapFactory.decodeStream(stream,null,BitmapFactory.Options().apply{
                        inSampleSize=16
                    })
                }
            }.getOrNull()
        }
    }
    if(thumbnail!=null)
        Image(thumbnail!!.asImageBitmap(),contentDescription=null,
            modifier=Modifier.size(72.dp),contentScale=ContentScale.Crop)
    else Box(Modifier.size(72.dp).border(1.dp,Color.LightGray,
        RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){
        Icon(Icons.Outlined.AddPhotoAlternate,null,tint=Brand.Muted)
    }
}
