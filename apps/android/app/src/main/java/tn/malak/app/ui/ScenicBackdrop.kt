package tn.malak.app.ui

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import tn.malak.app.R

/**
 * User-approved, self-contained photographic DESIGN REFERENCE in QA only.
 * Not a geo map, not live satellite tiles and not a verified legal boundary.
 * Native map camera and reviewed GIS geometry must replace this reference stage
 * before any public property launch. Never advertise this image as 3D maps.
 */
@Composable
fun ScenicBackdrop(modifier: Modifier = Modifier, previewZoom: Int = 1) {
    val amount = (previewZoom - 1).coerceIn(0, 5)
    Image(
        painter = painterResource(id = R.drawable.malak_coast_preview),
        contentDescription = "مشهد ساحلي مرجعي توضيحي، وليس خريطة حقيقية",
        contentScale = ContentScale.Crop,
        modifier = modifier.graphicsLayer {
            scaleX = 1f + amount * .08f
            scaleY = 1f + amount * .08f
        }
    )
}

@Composable
fun CoastalThumbnail(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.drawable.malak_property_photo),
        contentDescription = "صورة العقار المرجعية، ليست إعلاناً حقيقياً",
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}

@Composable
fun PreviewGalleryThumbnail(index: Int, modifier: Modifier = Modifier) {
    val drawable = when (index) {
        0 -> R.drawable.malak_thumb_a
        1 -> R.drawable.malak_thumb_b
        else -> R.drawable.malak_thumb_c
    }
    Image(
        painter = painterResource(drawable),
        contentDescription = "صورة مرجعية ${index + 1}",
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(10.dp))
    )
}

/** Transparent floral corner; do not display the old JPEG's baked system icons. */
@Composable
fun BougainvilleaSway(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val petals = remember(ctx) {
        val original = android.graphics.BitmapFactory.decodeResource(
            ctx.resources, R.drawable.malak_flower_corner
        )
        val w = (original.width * .70f).toInt()
        val output = android.graphics.Bitmap.createBitmap(
            w, original.height, android.graphics.Bitmap.Config.ARGB_8888
        )
        for(y in 0 until original.height) for(x in 0 until w) {
            val pixel = original.getPixel(x,y)
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            val chroma = maxOf(r,g,b)-minOf(r,g,b)
            val isFlower = r > g+25 && r > b+5
            val isBranch = g > r+12 && g > b+6
            val alpha = if(isFlower || isBranch) (chroma*4).coerceIn(0,255) else 0
            output.setPixel(x,y,android.graphics.Color.argb(alpha,r,g,b))
        }
        output.asImageBitmap()
    }
    val suppressMotion = remember(ctx) {
        val power=ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val memory=ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val reduced=runCatching {
            Settings.Global.getFloat(ctx.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f
        }.getOrDefault(false)
        reduced || power?.isPowerSaveMode==true || memory?.isLowRamDevice==true
    }
    val rotation = if(suppressMotion) 0f else {
        val infinite=rememberInfiniteTransition(label="petals")
        val value by infinite.animateFloat(
            initialValue=-1.0f,targetValue=1.0f,
            animationSpec=infiniteRepeatable(
                animation=tween(7000,easing=FastOutSlowInEasing),
                repeatMode=RepeatMode.Reverse
            ),label="gentle-wind"
        )
        value
    }
    Image(
        bitmap=petals,contentDescription=null,contentScale=ContentScale.FillBounds,
        modifier=modifier.graphicsLayer {
            rotationZ=rotation
            transformOrigin=TransformOrigin(0f,0f)
        }
    )
}
