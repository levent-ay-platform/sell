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

/** Tiny, image-backed branch that sways softly, never circles covering system status icons. */
@Composable
fun BougainvilleaSway(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val lowPower = remember(ctx) {
        val power = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val memory = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val reduced = runCatching {
            Settings.Global.getFloat(
                ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        }.getOrDefault(false)
        (power?.isPowerSaveMode == true) || (memory?.isLowRamDevice == true) || reduced
    }
    val rotation = if (!lowPower) {
        val infinite = rememberInfiniteTransition(label = "bougainvillea-wind")
        val degrees by infinite.animateFloat(
            initialValue = -1.25f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(6700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "sway"
        )
        degrees
    } else 0f
    Image(
        painter = painterResource(id = R.drawable.malak_flower_corner),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                rotationZ = rotation
                transformOrigin = TransformOrigin(1f, 0f)
            }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, Color.White, Color.Transparent),
                        center = Offset(size.width * .45f, size.height * .31f),
                        radius = size.width * .78f
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    )
}
