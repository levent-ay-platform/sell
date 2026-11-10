package tn.malak.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import tn.malak.app.R

/**
 * Visual-only V1 map exploration. The approved opening image includes a blurred
 * presentation-card area baked into the bottom of the photograph. When the user
 * swipes to hide the card, showing that blur as "map" was visibly wrong.
 *
 * Crop ONLY the clear coastal portion of the SAME committed photo, not a newly
 * designed picture or a fake map. Cover-crop preserves the coast's proportions
 * instead of stretching houses; a real satellite/3D SDK will replace this layer.
 * This image has NO coordinates, cadastral evidence, or verified status.
 */
@Composable
internal fun CleanImmersivePhoto(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cleanBitmap = remember(context) {
        val source = BitmapFactory.decodeResource(
            context.resources, R.drawable.malak_locked_backdrop
        )
        val startY = (source.height * 0.12f).toInt()
        // This photograph's blurred sheet starts at approximately 70% height.
        val endY = (source.height * 0.698f).toInt()
        val clean = Bitmap.createBitmap(
            source.width, endY - startY, Bitmap.Config.ARGB_8888
        )
        AndroidCanvas(clean).drawBitmap(source, 0f, -startY.toFloat(), null)
        source.recycle()
        clean.asImageBitmap()
    }
    Image(
        bitmap = cleanBitmap,
        contentDescription = "Vue photographique de démonstration, sans carte GPS",
        modifier = modifier,
        contentScale = ContentScale.Crop,
        alignment = Alignment.Center
    )
}
